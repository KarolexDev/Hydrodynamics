package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Drosselt den Teilchenfluss stark.
 *
 * JSON-Beispiel:
 * {
 *   "type": "decompressor",
 *   "volume": 0.1,
 *   "flowSuppression": 0.99
 * }
 */
public class DecompressorComponent extends GasNetworkComponent {

    /** 0..1 – Anteil des unterdrückten Flusses (0.99 = 1% des normalen Flusses) */
    public double flowSuppression = 0.99;

    public static final BuilderCodec<DecompressorComponent> CODEC = BuilderCodec
            .builder(DecompressorComponent.class, DecompressorComponent::new)
            .append(new KeyedCodec<>("volume",          Codec.DOUBLE),
                    (c, v) -> c.volume          = v, c -> c.volume).add()
            .append(new KeyedCodec<>("flowSuppression", Codec.DOUBLE),
                    (c, v) -> c.flowSuppression = v, c -> c.flowSuppression).add()
            .build();

    @Override public String getType() { return "decompressor"; }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        DecompressorComponent flux = new DecompressorComponent();
        double dp = from.pressure() - to.pressure();
        flux.particleCount = dp * from.defaultFlowFactor(to) * (1.0 - flowSuppression);
        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        DecompressorComponent c = new DecompressorComponent();
        c.particleCount   = this.particleCount;
        c.energy          = this.energy;
        c.volume          = this.volume;
        c.flowSuppression = this.flowSuppression;
        return c;
    }
}