package com.karolex.hydrodynamics.gasnetwork;

import com.karolex.hydrodynamics.HydrodynamicsPlugin;
import com.karolex.hydrodynamics.blocknetwork.BlockNetwork;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent>, Component<ChunkStore> {

    public static final double R           = 8.314;
    public static final double TEMPERATURE = 293.0;  // K, fix
    public static final double MIN_AMOUNT  = 1e-10;

    public static final BuilderCodec<GasNetworkComponent> CODEC;

    static {
        BuilderCodec.Builder<GasNetworkComponent> b =
                BuilderCodec.builder(GasNetworkComponent.class, GasNetworkComponent::new);
        b = (BuilderCodec.Builder<GasNetworkComponent>) b
                .append(new KeyedCodec<>("Amount",         Codec.DOUBLE),  (c, v) -> c.amount         = v, c -> c.amount).add()
                .append(new KeyedCodec<>("Type",           new EnumCodec<>(GasNetworkType.class)),
                        (c, v) -> c.type           = v, c -> c.type).add()
                .append(new KeyedCodec<>("Volume",         Codec.DOUBLE),  (c, v) -> c.volume         = v, c -> c.volume).add()
                .append(new KeyedCodec<>("TargetPressure", Codec.DOUBLE),  (c, v) -> c.targetPressure = v, c -> c.targetPressure).add()
                .append(new KeyedCodec<>("MaxRate",        Codec.DOUBLE),  (c, v) -> c.maxRate        = v, c -> c.maxRate).add()
                .append(new KeyedCodec<>("Conductance",    Codec.DOUBLE),  (c, v) -> c.conductance    = v, c -> c.conductance).add()
                .append(new KeyedCodec<>("IsExtendable",   Codec.BOOLEAN), (c, v) -> c.isExtendable   = v, c -> c.isExtendable).add()
                .append(new KeyedCodec<>("IsClosed",       Codec.BOOLEAN), (c, v) -> c.isClosed       = v, c -> c.isClosed).add();
        CODEC = b.build();
    }

    public double amount;          // mol
    public GasNetworkType type = GasNetworkType.NONE;
    public double volume;          // m³
    public double targetPressure;  // Pa
    public double maxRate;         // mol/s
    public double conductance;     // mol/(s·Pa)
    public boolean isExtendable;
    public boolean isClosed;

    public GasNetworkComponent() {
        this(0, GasNetworkType.NONE, 0, 0, 0, 1.0, false, false);
    }

    public GasNetworkComponent(double amount, GasNetworkType type,
                               double volume, double targetPressure,
                               double maxRate, double conductance,
                               boolean isExtendable, boolean isClosed) {
        this.amount        = amount;
        this.type          = type;
        this.volume        = volume;
        this.targetPressure = targetPressure;
        this.maxRate       = maxRate;
        this.conductance   = conductance;
        this.isExtendable  = isExtendable;
        this.isClosed      = isClosed;
    }

    public double pressure() {
        if (volume <= 0) return 0.0;
        return amount * R * TEMPERATURE / volume;
    }

    /** Max flux this node can push out per tick: density × volume = amount (mol). */
    private double maxFluxPerTick() {
        return maxRate;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent — Arithmetic
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent add(GasNetworkComponent flux) {
        amount = Math.max(MIN_AMOUNT, amount + flux.amount);
        return this;
    }

    @Override
    public GasNetworkComponent del(GasNetworkComponent flux) {
        amount = Math.max(MIN_AMOUNT, amount - flux.amount);
        return this;
    }

    @Override
    public GasNetworkComponent mergeComponents(GasNetworkComponent other) {
        amount += other.amount;
        volume += other.volume;
        conductance += other.conductance;
        return this;
    }

    /**
     * Ohm's law: flux = conductance × ΔP × dt,
     * capped at [-(to.amount - MIN_AMOUNT), from.amount - MIN_AMOUNT].
     */
    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent fluxCap,
                                             GasNetworkComponent from, GasNetworkComponent to,
                                             String fromType, String toType) {
        GasNetworkComponent flux = zero();
        if (from.isClosed || to.isClosed)       return flux;
        if (from.volume <= 0 || to.volume <= 0) return flux;

        double dP        = from.pressure() - to.pressure();
        double ohmRaw    = from.conductance * dP;
        double maxOut    =  from.amount;
        double maxIn     =  to.amount;
        double ohmCapped = Math.clamp(ohmRaw, -maxIn, maxOut);

        // Quellen und Senken erzeugen/vernichten Flux — externer Cap greift nicht.
        boolean isActiveEdge = from.isActive() || to.isActive();
        if (isActiveEdge) {
            double cap = Math.abs(Math.max(from.pressure(), to.pressure()) * Math.min(from.volume, to.volume) / (R * TEMPERATURE));
            flux.amount = Math.clamp(ohmCapped, -cap, cap);
        } else {
            double cap = Math.min(Math.abs(fluxCap.amount), Math.abs(from.pressure() * Math.min(from.volume, to.volume) / (R * TEMPERATURE)));
            flux.amount = Math.clamp(ohmCapped, -cap, cap);
        }
        return flux;
    }

    /**
     * Distributes total incoming flux evenly across all outgoing edges.
     * Returns a list parallel to {@code outgoingFlux}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<GasNetworkComponent> divideFluxFlow(
            List<Map.Entry<String, GasNetworkComponent>> incomingFlux,
            List<Map.Entry<String, GasNetworkComponent>> outgoingFlux) {

        double totalIn = incomingFlux.stream()
                .mapToDouble(e -> e.getValue().amount)
                .sum();

        int    outCount   = outgoingFlux.size();
        double perOutgoing = outCount > 0 ? totalIn / outCount : 0.0;

        // Cap so we never schedule more out than the node physically holds.
        double maxPerEdge = maxFluxPerTick() / Math.max(outCount, 1);
        double capped     = Math.min(perOutgoing, maxPerEdge);

        List<GasNetworkComponent> result = new ArrayList<>(outCount);
        for (int i = 0; i < outCount; i++) {
            GasNetworkComponent f = zero();
            f.amount = capped;
            result.add(f);
        }
        return result;
    }

    /**
     * Delay in ticks until net influx changes storage by 0.1 %:
     * {@code 0.001 × amount / net_influx}.
     * Returns {@code null} if net influx is zero (no rescheduling needed).
     */
    @SuppressWarnings("unchecked")
    @Override
    public Integer computeDelay(
            List<Map.Entry<String, GasNetworkComponent>> incomingFlux,
            List<Map.Entry<String, GasNetworkComponent>> outgoingFlux) {

        double sumIn  = incomingFlux .stream().mapToDouble(e -> e.getValue().amount).sum();
        double sumOut = outgoingFlux .stream().mapToDouble(e -> e.getValue().amount).sum();
        double net    = Math.abs(sumIn - sumOut);

        if (net < MIN_AMOUNT) return null;
        return (int) Math.max(1, Math.floor(0.001 * amount / net));
    }

    /**
     * {@code true} iff net flux is zero (system locally balanced).
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean fluxAmountsToZero(
            List<Map.Entry<String, GasNetworkComponent>> incomingFlux,
            List<Map.Entry<String, GasNetworkComponent>> outgoingFlux) {

        double sumIn  = incomingFlux .stream().mapToDouble(e -> e.getValue().amount).sum();
        double sumOut = outgoingFlux .stream().mapToDouble(e -> e.getValue().amount).sum();
        return Math.abs(sumIn - sumOut) < MIN_AMOUNT;
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent — Misc.
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent[] partition(int leftSize, int rightSize) {
        double frac   = (leftSize + rightSize > 0) ? (double) leftSize / (leftSize + rightSize) : 0.5;
        GasNetworkComponent left  = copy(); left.amount  = amount * frac;       left.volume  = volume * frac;
        GasNetworkComponent right = copy(); right.amount = amount * (1 - frac); right.volume = volume * (1 - frac);
        return new GasNetworkComponent[]{ left, right };
    }

    @Override
    public GasNetworkComponent zero() { return new GasNetworkComponent(); }

    @Override
    public GasNetworkComponent copy() {
        return new GasNetworkComponent(amount, type, volume, targetPressure,
                maxRate, conductance, isExtendable, isClosed);
    }

    @Override
    public boolean shouldMerge(GasNetworkComponent other) {
        return this.isExtendable && other.isExtendable
                && this.type == other.type
                && this.type == GasNetworkType.PIPE;
    }

    @Override
    public void tick() {
        if (volume <= 0) return;
        switch (type) {
            case SOURCE, SINK -> amount = targetPressure * volume / (R * TEMPERATURE);
            default -> {}
        }
    }

    @Override
    public boolean isActive() {
        return type == GasNetworkType.SOURCE || type == GasNetworkType.SINK;
    }

    @Override
    public Map<Vector3i, String> getConnectionPoints() {
        return switch (type) {
            case VALVE -> Map.of(
                    new Vector3i(-1, 0, 0), BlockNetwork.DEFAULT_CONNECTION_TYPE,
                    new Vector3i( 1, 0, 0), BlockNetwork.DEFAULT_CONNECTION_TYPE
            );
            case PUMP -> Map.of(
                    new Vector3i(-1, 0, 0), "Inlet",
                    new Vector3i( 1, 0, 0), "Outlet"
            );
            default -> null;
        };
    }

    @Override public boolean isPipe()             { return type == GasNetworkType.PIPE; }
    @Override public boolean requiresWorldUpdate() { return false; }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {
        BlockNetworkComponent.super.onWorldUpdate(pos, world);
    }

    @Override
    public @Nullable Component<ChunkStore> clone() { return copy(); }

    @Override
    public @Nullable Component<ChunkStore> cloneSerializable() {
        return Component.super.cloneSerializable();
    }

    public static ComponentType<ChunkStore, GasNetworkComponent> getComponentType() {
        return HydrodynamicsPlugin.getInstance().getGasNetworkComponentType();
    }

    @Override
    public String toString() {
        return String.format("Amount: %.3f mol  |  Pressure: %.3f bar  |  Volume: %.4f m³  |  Type: %s",
                amount, pressure() * 1e-5, volume, type);
    }

    // GasNetworkComponent
    @Override
    public GasNetworkComponent negate() {
        GasNetworkComponent n = copy();
        n.amount = -n.amount;
        return n;
    }
}