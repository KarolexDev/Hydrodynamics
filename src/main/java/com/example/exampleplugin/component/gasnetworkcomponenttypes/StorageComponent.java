package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Rohre und Tanks – speichert Gas und transferiert es zwischen Nodes.
 * Erweiterbar: beim Aneinanderreihen wird volume addiert.
 *
 * JSON-Beispiel:
 * {
 *   "type": "storage",
 *   "volume": 1.0,
 *   "initialParticles": 0.0,
 *   "initialEnergy": 500.0
 * }
 */
public class StorageComponent extends GasNetworkComponent {

    public static final BuilderCodec<StorageComponent> CODEC = BuilderCodec
            .builder(StorageComponent.class, StorageComponent::new)
            .append(new KeyedCodec<>("volume",          Codec.DOUBLE),
                    (c, v) -> c.volume         = v, c -> c.volume).add()
            .append(new KeyedCodec<>("initialParticles", Codec.DOUBLE),
                    (c, v) -> c.particleCount  = v, c -> c.particleCount).add()
            .append(new KeyedCodec<>("initialEnergy",    Codec.DOUBLE),
                    (c, v) -> c.energy         = v, c -> c.energy).add()
            .build();

    @Override public String getType() { return "storage"; }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        StorageComponent flux = new StorageComponent();
        double dp = from.pressure() - to.pressure();
        flux.particleCount = dp * from.defaultFlowFactor(to);
        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        StorageComponent c = new StorageComponent();
        c.particleCount = this.particleCount;
        c.energy        = this.energy;
        c.volume        = this.volume;
        return c;
    }
}
