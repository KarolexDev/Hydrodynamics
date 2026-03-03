package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Spielergesteuertes Ventil – öffnet oder schließt den Gasfluss.
 *
 * JSON-Beispiel:
 * {
 *   "type": "valve",
 *   "volume": 0.2,
 *   "open": true
 * }
 */
public class ValveComponent extends GasNetworkComponent {

    public boolean open = true;

    public static final BuilderCodec<ValveComponent> CODEC = BuilderCodec
            .builder(ValveComponent.class, ValveComponent::new)
            .append(new KeyedCodec<>("volume", Codec.DOUBLE),
                    (c, v) -> c.volume = v, c -> c.volume).add()
            .append(new KeyedCodec<>("open",   Codec.BOOLEAN),
                    (c, v) -> c.open   = v, c -> c.open).add()
            .build();

    @Override public String getType() { return "valve"; }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        ValveComponent flux = new ValveComponent();
        if (!open) return flux; // geschlossen → kein Fluss

        double dp = from.pressure() - to.pressure();
        flux.particleCount = dp * from.defaultFlowFactor(to);
        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        ValveComponent c = new ValveComponent();
        c.particleCount = this.particleCount;
        c.energy        = this.energy;
        c.volume        = this.volume;
        c.open          = this.open;
        return c;
    }
}
