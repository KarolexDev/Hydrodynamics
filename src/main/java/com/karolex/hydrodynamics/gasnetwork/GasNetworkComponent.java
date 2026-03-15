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

public class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent>, Component<ChunkStore> {

    public static final double R           = 8.314;
    public static final double TEMPERATURE = 293.0;  // K, fix
    public static final double MIN_AMOUNT  = 1e-10;
    public static final long   TICK_MS     = 50L;

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
                .append(new KeyedCodec<>("IsExtendable",   Codec.BOOLEAN), (c, v) -> c.isExtendable   = v, c -> c.isExtendable).add()
                .append(new KeyedCodec<>("IsClosed",   Codec.BOOLEAN), (c, v) -> c.isClosed   = v, c -> c.isClosed).add();

        CODEC = b.build();
    }

    public double amount;          // mol
    public GasNetworkType type = GasNetworkType.NONE;
    public double volume;          // m³
    public double targetPressure;  // Pa
    public double maxRate;         // mol/s
    public boolean isExtendable;
    public boolean isClosed;

    public GasNetworkComponent() {
        this(0, GasNetworkType.NONE, 0, 0, 0, false, false);
    }

    public GasNetworkComponent(double amount, GasNetworkType type,
                               double volume, double targetPressure,
                               double maxRate, boolean isExtendable,
                               boolean isClosed) {
        this.amount         = amount;
        this.type           = type;
        this.volume         = volume;
        this.targetPressure = targetPressure;
        this.maxRate        = maxRate;
        this.isExtendable   = isExtendable;
        this.isClosed = isClosed;
    }

    public double pressure() {
        if (volume <= 0) return 0.0;
        return amount * R * TEMPERATURE / volume;
    }

    @Override
    public @Nullable Component<ChunkStore> clone() { return copy(); }

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
        return this;
    }


    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        GasNetworkComponent flux = zero();
        if ((from.isClosed && from.type == GasNetworkType.VALVE) || (to.isClosed && to.type == GasNetworkType.VALVE)) return flux;
        if (from.volume <= 0 || to.volume <= 0) return flux;

        double totalAmount = from.amount + to.amount;
        double totalVolume = from.volume + to.volume;
        double eqAmountFrom = totalAmount * (from.volume / totalVolume);

        double transfer_ratio = 0.6;

        double delta = (from.amount - eqAmountFrom) * transfer_ratio;

        double lowerBound = -(to.amount - MIN_AMOUNT) * transfer_ratio;
        double upperBound = (from.amount - MIN_AMOUNT) * transfer_ratio;

        if (upperBound < lowerBound) {
            flux.amount = 0;
            return flux;
        }

        delta = Math.clamp(delta, lowerBound, upperBound);

        flux.amount = delta;
        return flux;
    }

    @Override
    public GasNetworkComponent[] partition(int leftSize, int rightSize) {
        double frac = (leftSize + rightSize > 0)
                ? (double) leftSize / (leftSize + rightSize) : 0.5;
        GasNetworkComponent left  = copy(); left.amount  = amount * frac;       left.volume  = volume * frac;
        GasNetworkComponent right = copy(); right.amount = amount * (1 - frac); right.volume = volume * (1 - frac);
        return new GasNetworkComponent[]{ left, right };
    }

    @Override
    public GasNetworkComponent zero() { return new GasNetworkComponent(); }

    @Override
    public GasNetworkComponent copy() {
        return new GasNetworkComponent(amount, type, volume, targetPressure, maxRate, isExtendable, isClosed);
    }

    @Override
    public boolean shouldMerge(GasNetworkComponent other) {
        return this.isExtendable && other.isExtendable && this.type == other.type && this.type == GasNetworkType.PIPE;
    }


    @Override
    public void tick(float dt) {
        if (volume <= 0 || dt <= 0) return;
        switch (type) {
            case SOURCE -> {
                double target = targetPressure * volume / (R * TEMPERATURE);
                double deficit = target - amount;
                if (deficit <= 0) return;
                amount += Math.min(deficit * 0.5, maxRate * dt);
            }
            case SINK -> {
                double target = targetPressure * volume / (R * TEMPERATURE);
                double surplus = amount - target;
                if (surplus <= 0) return;
                amount = Math.max(MIN_AMOUNT, amount - Math.min(surplus * 0.5, maxRate * dt));
            }
            default -> {}
        }
    }

    @Override
    public Duration computeDelay(float dt, GasNetworkComponent previous, boolean isActive) {
        if (isActive) return Duration.ofMillis(TICK_MS);
        double dP = Math.abs(pressure() - previous.pressure());
        return dP < 0.01 ? null : Duration.ofMillis(TICK_MS);
    }

    @Override
    public boolean isActive() {
        return type == GasNetworkType.SOURCE || type == GasNetworkType.SINK;
    }

    @Override
    public boolean isPipe() { return type == GasNetworkType.PIPE; }

    @Override
    public boolean requiresWorldUpdate() { return false; }

    public static ComponentType<ChunkStore, GasNetworkComponent> getComponentType() {
        return HydrodynamicsPlugin.getInstance().getGasNetworkComponentType();
    }

    @Override
    public String toString() {
        return String.format("Amount: %.3f mol  |  Pressure: %.3f bar  |  Volume: %.4f m³  |  Type: %s",
                amount, pressure() * 1e-5, volume, type);
    }
}