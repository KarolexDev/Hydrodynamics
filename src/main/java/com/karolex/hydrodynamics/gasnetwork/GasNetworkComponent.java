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

public class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent>, Component<ChunkStore> {

    public static final double R = 8.314; // J/(mol·K)

    public static final double MIN_AMOUNT = 1e-10;  // min particle count (to avoid division by zero)
    public static final double MIN_ENERGY = 1e-10;  // min energy count (to avoid division by zero)

    public static final BuilderCodec CODEC;

    public boolean isFrozen = false;

    static {
        BuilderCodec.Builder<GasNetworkComponent> builder = BuilderCodec.builder(GasNetworkComponent.class, GasNetworkComponent::new);
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("InitialAmount",          Codec.DOUBLE),                          (c, v) -> c.amount          = v, (c) -> c.amount).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("InitialEnergy",          Codec.DOUBLE),                          (c, v) -> c.energy          = v, (c) -> c.energy).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec<>("Type", new EnumCodec(GasNetworkType.class)), (c, v) -> c.type = (GasNetworkType) v, (c) -> c.type).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("Volume",          Codec.DOUBLE),                          (c, v) -> c.volume          = v, (c) -> c.volume).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("InternalPressure",Codec.DOUBLE),                          (c, v) -> c.internalPressure= v, (c) -> c.internalPressure).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("GenerationRate",  Codec.DOUBLE),                          (c, v) -> c.generationRate  = v, (c) -> c.generationRate).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("ConsumptionRate", Codec.DOUBLE),                          (c, v) -> c.consumptionRate = v, (c) -> c.consumptionRate).add();
        builder = (BuilderCodec.Builder<GasNetworkComponent>) builder.append(new KeyedCodec("IsExtendable", Codec.BOOLEAN),                          (c, v) -> c.isExtendable = v, (c) -> c.isExtendable).add();
        CODEC = builder.build();
    }

    // Dynamic Variables
    public double amount;   // mol
    public double energy;   // J

    // Structural Parameters
    public GasNetworkType type = GasNetworkType.NONE;
    public double volume;   // m³
    public double internalPressure; // Pa - TODO: Rename field.
    public double generationRate;   // TODO: rethink...
    public double consumptionRate;  // same...
    public boolean isExtendable;

    public GasNetworkComponent() {
        this(0, 0, GasNetworkType.NONE, 0, 0, 0, 0, false);
    }

    public GasNetworkComponent(double amount, double energy, GasNetworkType type, double volume, double internalPressure, double generationRate, double consumptionRate, boolean isExtendable) {
        this.amount = amount;
        this.energy = energy;
        this.type = type;
        this.volume = volume;
        this.internalPressure = internalPressure;
        this.generationRate = generationRate;
        this.consumptionRate = consumptionRate;
        this.isExtendable = isExtendable;
    }

    public GasNetworkComponent(double amount, double energy) { this(amount, energy, GasNetworkType.NONE, 0, 0, 0, 0, false); }

    public double pressure() {
        return 2 * energy / (3 * volume);
    }

    public double temperature() {
        if (amount <= 0) return 293.0;
        return energy / (amount * 1.5 * R);
    }

    @Override
    public GasNetworkComponent calculateFlux(float dt, GasNetworkComponent from, GasNetworkComponent to) {
        GasNetworkComponent flux = zero();

        if (from.volume <= 0 || to.volume <= 0) return flux;

        double T1 = from.temperature();
        double T2 = to.temperature();
        if (T1 <= 0 || T2 <= 0) return flux;

        double pFrom = from.pressure();
        double pTo   = to.pressure();

        double totalMoles  = from.amount + to.amount;
        double totalEnergy = from.energy + to.energy;
        double pEq = totalMoles * R / (from.volume / T1 + to.volume / T2);

        double nEq_from = pEq * from.volume / (R * T1);

        double deficit = from.amount - nEq_from;

        double pDiff = pFrom - pTo;
        double pMax  = Math.max(pFrom, pTo);
        if (pMax <= 0) return flux;

        if (deficit * pDiff < 0) return flux;

        double conductance = 5e3d;
        double alpha = 1.0 - Math.exp(-conductance * Math.abs(pDiff) / pMax * dt);
        alpha = Math.clamp(alpha, 0.0, 0.5); // MAX 0.5 verhindert Overshoot

        flux.amount = deficit * alpha;

        if (flux.amount > 0) {
            flux.amount = Math.min(flux.amount,  from.amount - MIN_AMOUNT);
            flux.energy = flux.amount * T1 * 1.5 * R;
        } else if (flux.amount < 0) {
            flux.amount = Math.max(flux.amount, -(to.amount   - MIN_AMOUNT));
            flux.energy = flux.amount * T2 * 1.5 * R;
        }

        return flux;
    }

    @Override
    public GasNetworkComponent mergeComponents(GasNetworkComponent other) {
        amount += other.amount;
        energy += other.energy;
        volume += other.volume;
        return this;
    }

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

    @Override
    public GasNetworkComponent[] partition(int left_size, int right_size) {
        double frac = (double) left_size / (left_size + right_size);

        GasNetworkComponent left  = copy();
        left.amount = this.amount * frac;
        left.energy = this.energy * frac;
        left.volume = this.volume * frac;

        GasNetworkComponent right = copy();
        right.amount = this.amount * (1 - frac);
        right.energy = this.energy * (1 - frac);
        right.volume = this.volume * (1 - frac);

        return new GasNetworkComponent[]{left, right};
    }

    @Override
    public GasNetworkComponent zero() {
        return new GasNetworkComponent();
    }

    @Override
    public GasNetworkComponent copy() {
        GasNetworkComponent c = new GasNetworkComponent();
        c.amount          = this.amount;
        c.energy          = this.energy;
        c.type            = this.type;
        c.volume          = this.volume;
        c.internalPressure= this.internalPressure;
        c.generationRate  = this.generationRate;
        c.consumptionRate = this.consumptionRate;
        c.isExtendable = this.isExtendable;
        return c;
    }

    @Override
    public boolean requiresWorldUpdate() {
        return false;
    }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {}

    @Override
    public float computeDelay(float dt, GasNetworkComponent previous) {
        double dndt = Math.abs((previous.amount - amount) / dt);
        double dEdt = Math.abs((previous.energy - energy) / dt);

        // if (dndt < 1e-12 && dEdt < 1e-12) return 5.0f; // ruhender Zustand → langer Delay

//        float dt1 = (dndt > 1e-12) ? (float)(1e-3 * amount / dndt) : Float.MAX_VALUE;
//        float dt2 = (dEdt > 1e-12) ? (float)(1e-3 * energy / dEdt) : Float.MAX_VALUE;

        float dt1 = (float)(1e-3 * amount / dndt);
        float dt2 = (float)(1e-3 * energy / dEdt);

        return Math.min(dt1, dt2);

        // return Math.clamp(Math.min(dt1, dt2), 0.05f, 5.0f);
    }

    @Override
    public boolean isFrozen() {
        return isFrozen;
    }

    @Override
    public boolean shouldMerge(GasNetworkComponent other) {
        return this.isExtendable && other.isExtendable && (this.type == other.type);    // TODO: could make stricter, i.e. require same BlockType. Temp. solution only!
    }

    public boolean isPipe() {
        return this.type == GasNetworkType.PIPE;
    }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return copy();
    }

    public static ComponentType<ChunkStore, GasNetworkComponent> getComponentType() { return HydrodynamicsPlugin.getInstance().getGasNetworkComponentType(); }

    public String toString() {
        return String.format("Amount: %.1f mol\nEnergy: %.1f kJ\n\nPressure: %.1f bar\nTemperature: %.1f °C\n\nVolume: %.2f m³",
                this.amount, this.energy * 1e-3, this.pressure() * 1e-5, this.temperature() - 273.15, this.volume);
    }

    public void tick(float dt) {
        if (isFrozen) return;

        switch (type) {
            case SOURCE -> {
                if (volume <= 0) break;
                double pActual = pressure();
                double pDiff = internalPressure - pActual;
                if (pDiff <= 0) break;

                double T = temperature();
                if (T <= 0) break;

                double targetAmount = internalPressure * volume / (R * T);
                double deficit = targetAmount - amount;

                double pMax = Math.max(internalPressure, pActual);
                double alpha = 1.0 - Math.exp(-5e3d * pDiff / pMax * dt);
                alpha = Math.clamp(alpha, 0.0, 0.5);

                double dn = Math.clamp(deficit * alpha, 0.0, generationRate * dt);
                amount += dn;
                energy += dn * T * 1.5 * R;
            }
            case SINK -> {
                if (volume <= 0) break;
                double pActual = pressure();
                double pDiff = pActual - internalPressure;
                if (pDiff <= 0) break;

                double T = temperature();
                if (T <= 0) break;

                double targetAmount = internalPressure * volume / (R * T);
                double surplus = amount - targetAmount;

                double pMax = Math.max(internalPressure, pActual);
                double alpha = 1.0 - Math.exp(-5e3d * pDiff / pMax * dt);
                alpha = Math.clamp(alpha, 0.0, 0.5);

                double dn = Math.clamp(surplus * alpha, 0.0, consumptionRate * dt);
                amount = Math.max(MIN_AMOUNT, amount - dn);
                energy = Math.max(MIN_ENERGY, energy - dn * T * 1.5 * R);
            }
            case TANK, PIPE, NONE -> {}
        }
    }
}