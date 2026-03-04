package com.example.exampleplugin.gasnetwork;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.blocknetwork.BlockNetworkComponent;
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

    public static final BuilderCodec CODEC;

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
    public GasNetworkType type = GasNetworkType.TANK;
    public double volume;           // m³  – für TANK
    public double internalPressure; // Pa  – konstanter Druck für SOURCE/SINK
    public double generationRate;   // mol/s – für SOURCE
    public double consumptionRate;  // mol/s – für SINK
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


    // Physics
    /** Ideale Gasgleichung p = nRT/V, für TANK und PIPE relevant */
    public double pressure() {
        if ((type != GasNetworkType.PIPE) && (type != GasNetworkType.TANK) || volume <= 0) return internalPressure;
        return (amount * R * temperature()) / volume;
    }

    /** T = E / (n * Cv), Cv = 1.5R für einatomiges Gas */
    public double temperature() {
        if (amount <= 0) return 293.0;
        return energy / (amount * 1.5 * R);
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        GasNetworkComponent flux = zero();

        double pFrom = from.pressure();
        double pTo   = to.pressure();
        double dp    = pFrom - pTo;

        switch (from.type) {
            case TANK, PIPE -> {
                // Normaler Druckausgleich proportional zur Druckdifferenz
                double conductance = ((from.volume + to.volume) / 2.0) * 0.01;
                flux.amount = dp * conductance;
            }
            case SOURCE -> {
                // Quelle: pumpt Teilchen in den Nachbar-Node rein
                // Fluss proportional zur Differenz zwischen internem Druck und Node-Druck
                double sourceDp = from.internalPressure - pTo;
                flux.amount = Math.max(0, sourceDp * from.generationRate);
            }
            case SINK -> {
                // Senke: saugt Teilchen aus dem from-Node heraus
                // Fluss proportional zur Differenz zwischen Node-Druck und internem Druck
                double sinkDp = pFrom - to.internalPressure;
                flux.amount = Math.max(0, sinkDp * to.consumptionRate);
            }
        }

        // Konvektiver Energietransport
        if (flux.amount > 0) {
            flux.energy = flux.amount * from.temperature() * 1.5 * R;
        } else if (flux.amount < 0) {
            flux.energy = flux.amount * to.temperature() * 1.5 * R;
        }

        return flux;
    }

    @Override
    public GasNetworkComponent mergeComponents(GasNetworkComponent other) {
        amount += other.amount;
        energy += other.energy;
        volume += other.volume; // extensiv → wird bei Node-Erweiterung addiert
        return this;
    }

    @Override
    public GasNetworkComponent add(GasNetworkComponent flux) {
        amount += flux.amount;
        energy += flux.energy;
        return this;
    }

    @Override
    public GasNetworkComponent del(GasNetworkComponent flux) {
        amount -= flux.amount;
        energy -= flux.energy;
        assert amount >= 0;
        assert energy >= 0;
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
        return c;
    }

    @Override
    public boolean requiresWorldUpdate() {
        // Source und Sink erzeugen/entfernen Teilchen unabhängig vom Flux
        return type == GasNetworkType.SOURCE || type == GasNetworkType.SINK;
    }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {
        switch (type) {
            case SOURCE -> amount += generationRate;
            case SINK   -> amount  = Math.max(0, amount - consumptionRate);
        }
    }

    @Override
    public float changeRate(GasNetworkComponent previous) {
        double dn = Math.abs(amount - previous.amount);
        double dE = Math.abs(energy - previous.energy);
        double normN = amount > 0 ? dn / amount : dn;
        double normE = energy > 0 ? dE / energy : dE;
        return (float) Math.max(normN, normE);
    }

    @Override
    public float computeDelay(float changeRate) {
        if (changeRate < 0.001f) return 1.0f;
        if (changeRate < 0.01f)  return 0.5f;
        if (changeRate < 0.1f)   return 0.1f;
        return 0.0f;
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

    public static ComponentType<ChunkStore, GasNetworkComponent> getComponentType() { return ExamplePlugin.getInstance().getGasNetworkComponentType(); }

    public String toString() {
        return "Amount: " + this.amount + "\nEnergy: " + this.energy;
    }
}