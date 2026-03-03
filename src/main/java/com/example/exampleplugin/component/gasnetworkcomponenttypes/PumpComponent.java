package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Pumpt Teilchen in Richtung pumpDirection bis targetPressureDelta erreicht ist.
 * Rückfluss wird nicht unterdrückt – dafür soll ein Ventil sorgen.
 *
 * JSON-Beispiel:
 * {
 *   "type": "pump",
 *   "volume": 0.5,
 *   "targetPressureDelta": 50000.0,
 *   "pumpFlowFactor": 0.005
 * }
 */
public class PumpComponent extends GasNetworkComponent {

    /** Gewünschte Druckdifferenz Eingang → Ausgang [Pa] */
    public double targetPressureDelta;

    /** mol/(s·Pa) */
    public double pumpFlowFactor;

    public static final BuilderCodec<PumpComponent> CODEC = BuilderCodec
            .builder(PumpComponent.class, PumpComponent::new)
            .append(new KeyedCodec<>("volume",              Codec.DOUBLE),
                    (c, v) -> c.volume              = v, c -> c.volume).add()
            .append(new KeyedCodec<>("targetPressureDelta", Codec.DOUBLE),
                    (c, v) -> c.targetPressureDelta = v, c -> c.targetPressureDelta).add()
            .append(new KeyedCodec<>("pumpFlowFactor",      Codec.DOUBLE),
                    (c, v) -> c.pumpFlowFactor      = v, c -> c.pumpFlowFactor).add()
            .build();

    @Override public String getType() { return "pump"; }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        PumpComponent flux = new PumpComponent();

        // Pumpe ist aktiv wenn der aktuelle Delta kleiner als der Ziel-Delta ist
        double currentDelta = from.pressure() - to.pressure();
        double deficit = targetPressureDelta - currentDelta;

        if (deficit > 0) {
            flux.particleCount = deficit * pumpFlowFactor;
        }
        // deficit <= 0: Zieldruck erreicht, Pumpe fördert nichts
        // Rückfluss (deficit << 0) wird bewusst nicht unterdrückt

        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        PumpComponent c = new PumpComponent();
        c.particleCount       = this.particleCount;
        c.energy              = this.energy;
        c.volume              = this.volume;
        c.targetPressureDelta = this.targetPressureDelta;
        c.pumpFlowFactor      = this.pumpFlowFactor;
        return c;
    }
}