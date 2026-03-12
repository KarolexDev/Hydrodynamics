package com.karolex.hydrodynamics.gasnetwork;

import com.karolex.hydrodynamics.HydrodynamicsPlugin;
import com.karolex.hydrodynamics.blocknetwork.BlockNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Gas network component based on ideal gas law (pV = nRT).
 *
 * State is stored as extensive variables: amount [mol] and energy [J].
 * Pressure and temperature are derived quantities.
 *
 * Flux represents a steady-state molar flow rate [mol/tick] driven by
 * a pressure gradient between two nodes. Energy is carried convectively
 * alongside the molar flux (adiabatic transport).
 *
 * Convergence is achieved iteratively via Gauß-Seidel relaxation:
 * each tick, a node adjusts its state based on incoming/outgoing flux
 * from its edges. Nodes sleep once |ΔP| < PRESSURE_EPSILON, and wake
 * again when neighbours change (via the existing update-wave mechanism).
 *
 * SOURCE/SINK nodes have a fixed target pressure (internalPressure) and
 * a maximum generation/consumption rate. Backpressure propagates naturally:
 * if the network resists, the source simply generates less (or nothing).
 *
 * Edge conductance is derived from node block count (larger node = longer
 * pipe segment = higher resistance = lower conductance).
 */
public class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent>, Component<ChunkStore> {

    // -------------------------------------------------------------------------
    // Physical constants
    // -------------------------------------------------------------------------

    public static final double R           = 8.314;   // J/(mol·K)
    public static final double CV_FACTOR   = 1.5;     // Cv = 1.5R for ideal monatomic gas

    // -------------------------------------------------------------------------
    // Numerical safety thresholds
    // -------------------------------------------------------------------------

    /** Minimum mol count to prevent division-by-zero in temperature calculations. */
    public static final double MIN_AMOUNT = 1e-10;

    /** Minimum internal energy to prevent negative temperatures. */
    public static final double MIN_ENERGY = 1e-10;

    /**
     * Pressure difference [Pa] below which a junction node is considered
     * converged and is allowed to sleep (computeDelay returns null).
     */
    public static final double PRESSURE_EPSILON = 0.01; // 0.01 Pa ≈ negligible

    /**
     * Tick interval while a node is actively relaxing [ms].
     * Lower = faster convergence, higher CPU cost.
     */
    public static final long ACTIVE_TICK_MS = 50L;

    /**
     * Fallback tick interval for SOURCE/SINK nodes [ms].
     * They must keep ticking to maintain their boundary condition.
     */
    public static final long BOUNDARY_TICK_MS = 50L;

    /**
     * Base conductance per unit length [mol/(s·Pa)].
     * Scales inversely with the number of blocks in a node (i.e. pipe length).
     * G = CONDUCTANCE_BASE / blockCount
     */
    public static final double CONDUCTANCE_BASE = 1.0;

    /**
     * Over-relaxation factor ω for SOR (Successive Over-Relaxation).
     * ω = 1.0 → plain Gauß-Seidel.
     * ω ∈ (1, 2) → faster convergence (typically 1.3–1.7 for pipe networks).
     */
    public static final double OMEGA = 1.4;

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final BuilderCodec<GasNetworkComponent> CODEC;

    static {
        BuilderCodec.Builder<GasNetworkComponent> b =
                BuilderCodec.builder(GasNetworkComponent.class, GasNetworkComponent::new);

        b = (BuilderCodec.Builder<GasNetworkComponent>) b
                .append(new KeyedCodec<>("Amount",          Codec.DOUBLE),
                        (c, v) -> c.amount          = v, c -> c.amount).add()
                .append(new KeyedCodec<>("Energy",          Codec.DOUBLE),
                        (c, v) -> c.energy          = v, c -> c.energy).add()
                .append(new KeyedCodec<>("Type",            new EnumCodec<>(GasNetworkType.class)),
                        (c, v) -> c.type            = v, c -> c.type).add()
                .append(new KeyedCodec<>("Volume",          Codec.DOUBLE),
                        (c, v) -> c.volume          = v, c -> c.volume).add()
                .append(new KeyedCodec<>("TargetPressure",  Codec.DOUBLE),
                        (c, v) -> c.targetPressure  = v, c -> c.targetPressure).add()
                .append(new KeyedCodec<>("MaxRate",         Codec.DOUBLE),
                        (c, v) -> c.maxRate         = v, c -> c.maxRate).add()
                .append(new KeyedCodec<>("IsExtendable",    Codec.BOOLEAN),
                        (c, v) -> c.isExtendable    = v, c -> c.isExtendable).add();

        CODEC = b.build();
    }

    // -------------------------------------------------------------------------
    // State (extensive variables – serialized)
    // -------------------------------------------------------------------------

    /** Total amount of gas in this node [mol]. */
    public double amount;

    /** Total internal energy of gas in this node [J]. */
    public double energy;

    // -------------------------------------------------------------------------
    // Structural parameters (set at block placement, serialized)
    // -------------------------------------------------------------------------

    /** Role of this node in the network. */
    public GasNetworkType type = GasNetworkType.NONE;

    /** Total volume of this node [m³]. Scales with block count via mergeComponents(). */
    public double volume;

    /**
     * Target pressure for SOURCE/SINK [Pa].
     * SOURCE: pressure the node tries to maintain (pumping gas in if below).
     * SINK:   pressure the node tries to drain to  (consuming gas if above).
     */
    public double targetPressure;

    /**
     * Maximum generation (SOURCE) or consumption (SINK) rate [mol/s].
     * Limits how fast the boundary condition can drive the network.
     * Backpressure is naturally enforced: when the network is at targetPressure,
     * no further gas is generated regardless of this limit.
     */
    public double maxRate;

    /** Whether this component may be merged with spatially adjacent peers. */
    public boolean isExtendable;

    // -------------------------------------------------------------------------
    // Transient state (not serialized)
    // -------------------------------------------------------------------------

    /**
     * Cached pressure from the previous tick, used by computeDelay() to detect
     * convergence. Not serialized; recomputed on first tick after load.
     */
    private double previousPressure = Double.NaN;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public GasNetworkComponent() {
        this(0, 0, GasNetworkType.NONE, 0, 0, 0, false);
    }

    public GasNetworkComponent(double amount, double energy,
                               GasNetworkType type, double volume,
                               double targetPressure, double maxRate,
                               boolean isExtendable) {
        this.amount         = amount;
        this.energy         = energy;
        this.type           = type;
        this.volume         = volume;
        this.targetPressure = targetPressure;
        this.maxRate        = maxRate;
        this.isExtendable   = isExtendable;
    }

    // -------------------------------------------------------------------------
    // Derived thermodynamic quantities
    // -------------------------------------------------------------------------

    /**
     * Kinetic-theory pressure from internal energy: P = 2U / (3V).
     * Equivalent to ideal gas law P = nRT/V when energy = n·Cv·T.
     */
    public double pressure() {
        if (volume <= 0) return 0.0;
        return 2.0 * energy / (3.0 * volume);
    }

    /**
     * Temperature derived from equipartition: T = U / (n·Cv).
     * Returns ambient temperature (293 K) as a safe fallback when nearly empty.
     */
    public double temperature() {
        if (amount <= MIN_AMOUNT) return 293.0;
        return energy / (amount * CV_FACTOR * R);
    }

    /**
     * Specific enthalpy [J/mol] of gas leaving this node.
     * Used for convective energy transport: h = Cp·T = (Cv + R)·T.
     */
    public double specificEnthalpy() {
        return (CV_FACTOR + 1.0) * R * temperature();
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – flux calculation
    // -------------------------------------------------------------------------

    /**
     * Calculates the steady-state flux from {@code from} to {@code to}.
     *
     * Molar flow rate (Hagen-Poiseuille analogy):
     *   ṅ = G · (P_from - P_to)
     *
     * where G = CONDUCTANCE_BASE / (blockCount_from + blockCount_to) is a
     * symmetric conductance for the edge, approximating pipe resistance from
     * node sizes.
     *
     * NOTE: {@code from} and {@code to} are the full node storages, not block
     * counts. Conductance therefore uses a fixed base value – callers that have
     * access to block counts may override this via a subclass or pass a
     * pre-scaled storage. For the default case the base conductance is used
     * directly (equivalent to G = CONDUCTANCE_BASE with uniform pipe segments).
     *
     * Energy transport is purely convective (adiabatic):
     *   Ė = ṅ · h_source
     * where h_source is the specific enthalpy of the gas at the source node
     * (the node it is leaving).
     *
     * The returned component represents the flux object stored on the Edge:
     *   flux.amount = ṅ  [mol / tick-normalised unit]
     *   flux.energy = Ė  [J  / tick-normalised unit]
     *
     * Positive values mean flow from → to (consistent with BlockNetwork's
     * del(flux) on the from-node and add(flux) on the to-node).
     */
    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        GasNetworkComponent flux = zero();

        if (from.volume <= 0 || to.volume <= 0) return flux;

        double pFrom = from.pressure();
        double pTo   = to.pressure();
        double dP    = pFrom - pTo;

        if (Math.abs(dP) < PRESSURE_EPSILON) return flux; // already balanced

        // Conductance: inversely proportional to combined node "length".
        // Using a fixed base here; override if you want block-count scaling.
        double G = CONDUCTANCE_BASE;

        // Molar flow rate [mol/s] – positive means from→to.
        double nDot = G * dP;

        // Clamp: never transfer more than half the available amount per tick
        // to prevent overshoot (analogous to the alpha≤0.5 in the old code).
        if (nDot > 0) {
            nDot = Math.min(nDot, (from.amount - MIN_AMOUNT) * 0.5);
        } else {
            nDot = Math.max(nDot, -(to.amount - MIN_AMOUNT) * 0.5);
        }

        flux.amount = nDot;

        // Convective energy transport: Ė = ṅ · h_source
        // When gas flows from→to, it carries enthalpy at from's temperature.
        // When gas flows to→from (nDot < 0), source is 'to'.
        if (nDot >= 0) {
            flux.energy = nDot * from.specificEnthalpy();
        } else {
            flux.energy = nDot * to.specificEnthalpy();
        }

        return flux;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – state update
    // -------------------------------------------------------------------------

    /**
     * Called each tick on active nodes.
     *
     * For JUNCTION/PIPE/TANK nodes: nothing extra needed – flux application
     * in BlockNetwork.Node.update() already performs the Gauß-Seidel step.
     *
     * For SOURCE nodes: inject gas to approach targetPressure, capped by maxRate·dt.
     * For SINK  nodes: consume gas to approach targetPressure, capped by maxRate·dt.
     *
     * Backpressure is natural: if pActual ≥ targetPressure for a SOURCE, dP ≤ 0
     * and no gas is injected.
     */
    @Override
    public void tick(float dt) {
        switch (type) {
            case SOURCE -> tickSource(dt);
            case SINK   -> tickSink(dt);
            default     -> {} // PIPE / TANK / NONE: purely passive, flux handles everything
        }
    }

    private void tickSource(float dt) {
        if (volume <= 0 || dt <= 0) return;

        double pActual = pressure();
        double dP      = targetPressure - pActual;
        if (dP <= 0) return; // network already at or above target – backpressure

        double T = temperature();
        if (T <= 0) return;

        // How many mol are needed to reach targetPressure at current T?
        double targetAmount = targetPressure * volume / (R * T);
        double deficit      = targetAmount - amount;
        if (deficit <= 0) return;

        // Smooth approach: exponential relaxation towards target.
        double pMax  = Math.max(targetPressure, pActual);
        double alpha = 1.0 - Math.exp(-5e3 * dP / pMax * dt);
        alpha = Math.clamp(alpha, 0.0, 0.5);

        // Clamp to maxRate.
        double dn = Math.min(deficit * alpha, maxRate * dt);
        dn = Math.max(dn, 0.0);

        amount += dn;
        energy += dn * specificEnthalpy(); // inject at current temperature
    }

    private void tickSink(float dt) {
        if (volume <= 0 || dt <= 0) return;

        double pActual = pressure();
        double dP      = pActual - targetPressure;
        if (dP <= 0) return; // network already at or below target

        double T = temperature();
        if (T <= 0) return;

        double targetAmount = targetPressure * volume / (R * T);
        double surplus      = amount - targetAmount;
        if (surplus <= 0) return;

        double pMax  = Math.max(targetPressure, pActual);
        double alpha = 1.0 - Math.exp(-5e3 * dP / pMax * dt);
        alpha = Math.clamp(alpha, 0.0, 0.5);

        double dn = Math.min(surplus * alpha, maxRate * dt);
        dn = Math.max(dn, 0.0);

        amount = Math.max(MIN_AMOUNT, amount - dn);
        energy = Math.max(MIN_ENERGY, energy - dn * specificEnthalpy());
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – scheduling
    // -------------------------------------------------------------------------

    /**
     * Convergence check for the adaptive scheduler.
     *
     * A JUNCTION/PIPE/TANK node sleeps when its pressure has barely changed
     * since the last tick (|ΔP| < PRESSURE_EPSILON). It will be woken again
     * automatically by the update-wave when a neighbour changes.
     *
     * SOURCE/SINK nodes always reschedule at BOUNDARY_TICK_MS so they can
     * continuously maintain their boundary condition.
     */
    @Override
    public Duration computeDelay(float dt, GasNetworkComponent previous, boolean isActive) {
        // Boundary nodes always keep ticking.
        if (isActive) return Duration.ofMillis(BOUNDARY_TICK_MS);

        double p     = pressure();
        double pPrev = previous.pressure();

        boolean converged = Math.abs(p - pPrev) < PRESSURE_EPSILON;

        if (converged) return null; // sleep until woken by a neighbour

        // Still relaxing – schedule next tick.
        // Adaptive interval: tick faster when pressure is changing rapidly.
        if (dt <= 0) return Duration.ofMillis(ACTIVE_TICK_MS);

        double pRate = Math.abs(p - pPrev) / dt;

        // Time to move by PRESSURE_EPSILON at current rate, clamped to [50, 5000] ms.
        long ms = Math.clamp((long) (PRESSURE_EPSILON / (pRate + 1e-12) * 1000),
                ACTIVE_TICK_MS, 5000L);
        return Duration.ofMillis(ms);
    }

    @Override
    public boolean isActive() {
        return type == GasNetworkType.SOURCE || type == GasNetworkType.SINK;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – flux application
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent add(GasNetworkComponent flux) {
        amount = Math.max(MIN_AMOUNT, amount + flux.amount);
        energy = Math.max(MIN_ENERGY, energy + flux.energy);
        return this;
    }

    @Override
    public GasNetworkComponent del(GasNetworkComponent flux) {
        amount = Math.max(MIN_AMOUNT, amount - flux.amount);
        energy = Math.max(MIN_ENERGY, energy - flux.energy);
        return this;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – topology helpers
    // -------------------------------------------------------------------------

    /**
     * Merges {@code other} into {@code this} (extensive quantities add up,
     * volume accumulates for correct pressure after merge).
     */
    @Override
    public GasNetworkComponent mergeComponents(GasNetworkComponent other) {
        amount += other.amount;
        energy += other.energy;
        volume += other.volume;
        // Structural parameters: keep this node's values (the caller decides which
        // node is the target; boundary nodes should not be merged into pipes anyway
        // since shouldMerge() prevents that).
        return this;
    }

    /**
     * Partitions this component proportionally between two segments.
     * Both extensive quantities and volume scale with size ratio.
     */
    @Override
    public GasNetworkComponent[] partition(int leftSize, int rightSize) {
        double total = leftSize + rightSize;
        double frac  = (total > 0) ? (double) leftSize / total : 0.5;

        GasNetworkComponent left  = copy();
        left.amount = this.amount * frac;
        left.energy = this.energy * frac;
        left.volume = this.volume * frac;

        GasNetworkComponent right = copy();
        right.amount = this.amount * (1.0 - frac);
        right.energy = this.energy * (1.0 - frac);
        right.volume = this.volume * (1.0 - frac);

        return new GasNetworkComponent[]{ left, right };
    }

    @Override
    public boolean shouldMerge(GasNetworkComponent other) {
        // Only merge passive pipe segments of the same gas type.
        // Boundary nodes (SOURCE/SINK) and tanks remain as distinct nodes so
        // the relaxation solver can treat them as proper boundary conditions.
        return this.isExtendable
                && other.isExtendable
                && this.type  == other.type
                && this.type  == GasNetworkType.PIPE;
    }

    @Override
    public boolean isPipe() {
        return this.type == GasNetworkType.PIPE;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – world update hook
    // -------------------------------------------------------------------------

    @Override
    public boolean requiresWorldUpdate() {
        double p = pressure();
        boolean changed = !Double.isNaN(previousPressure)
                && Math.abs(p - previousPressure) >= PRESSURE_EPSILON;
        previousPressure = p;
        return changed;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent zero() {
        return new GasNetworkComponent(); // amount=0, energy=0, all defaults
    }

    @Override
    public GasNetworkComponent copy() {
        GasNetworkComponent c = new GasNetworkComponent();
        c.amount          = this.amount;
        c.energy          = this.energy;
        c.type            = this.type;
        c.volume          = this.volume;
        c.targetPressure  = this.targetPressure;
        c.maxRate         = this.maxRate;
        c.isExtendable    = this.isExtendable;
        c.previousPressure= this.previousPressure;
        return c;
    }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return copy();
    }

    public static ComponentType<ChunkStore, GasNetworkComponent> getComponentType() {
        return HydrodynamicsPlugin.getInstance().getGasNetworkComponentType();
    }

    @Override
    public String toString() {
        return String.format(
                "Amount: %.3f mol  |  Energy: %.2f kJ%n" +
                        "Pressure: %.3f bar  |  Temperature: %.1f °C%n" +
                        "Volume: %.4f m³  |  Type: %s",
                amount, energy * 1e-3,
                pressure() * 1e-5, temperature() - 273.15,
                volume, type
        );
    }
}