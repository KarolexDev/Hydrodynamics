package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Tauscht Energie mit der Umgebung aus, ohne Teilchen zu transferieren.
 *
 * JSON-Beispiel:
 * {
 *   "type": "heat_exchanger",
 *   "volume": 1.0,
 *   "initialParticles": 5.0,
 *   "initialEnergy": 2000.0,
 *   "ambientTemperature": 293.0,
 *   "heatTransferFactor": 10.0
 * }
 */
public class HeatExchangerComponent extends GasNetworkComponent {

    public double ambientTemperature = 293.0; // K
    public double heatTransferFactor;         // J/(s·K)

    public static final BuilderCodec<HeatExchangerComponent> CODEC = BuilderCodec
            .builder(HeatExchangerComponent.class, HeatExchangerComponent::new)
            .append(new KeyedCodec<>("volume",             Codec.DOUBLE),
                    (c, v) -> c.volume               = v, c -> c.volume).add()
            .append(new KeyedCodec<>("initialParticles",   Codec.DOUBLE),
                    (c, v) -> c.particleCount         = v, c -> c.particleCount).add()
            .append(new KeyedCodec<>("initialEnergy",      Codec.DOUBLE),
                    (c, v) -> c.energy               = v, c -> c.energy).add()
            .append(new KeyedCodec<>("ambientTemperature", Codec.DOUBLE),
                    (c, v) -> c.ambientTemperature   = v, c -> c.ambientTemperature).add()
            .append(new KeyedCodec<>("heatTransferFactor", Codec.DOUBLE),
                    (c, v) -> c.heatTransferFactor   = v, c -> c.heatTransferFactor).add()
            .build();

    @Override public String getType() { return "heat_exchanger"; }

    @Override public boolean requiresWorldUpdate() { return true; }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {
        double dE = heatTransferFactor * (ambientTemperature - temperature());
        energy += dE;
    }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        // Nur Teilchenfluss, kein direkter Energietransfer über Edges
        HeatExchangerComponent flux = new HeatExchangerComponent();
        double dp = from.pressure() - to.pressure();
        flux.particleCount = dp * from.defaultFlowFactor(to);
        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        HeatExchangerComponent c = new HeatExchangerComponent();
        c.particleCount      = this.particleCount;
        c.energy             = this.energy;
        c.volume             = this.volume;
        c.ambientTemperature = this.ambientTemperature;
        c.heatTransferFactor = this.heatTransferFactor;
        return c;
    }
}