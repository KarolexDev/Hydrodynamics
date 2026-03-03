package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Quelle oder Senke – erzeugt bzw. entfernt Teilchen proportional zum
 * Druckgradienten zwischen dem konstanten Quelldruck und dem Nachbar-Node.
 *
 * Quelle JSON-Beispiel:
 * {
 *   "type": "source",
 *   "volume": 0.5,
 *   "sourcePressure": 200000.0,
 *   "flowFactor": 0.001
 * }
 *
 * Senke JSON-Beispiel:
 * {
 *   "type": "sink",
 *   "volume": 0.5,
 *   "sourcePressure": 50000.0,
 *   "flowFactor": 0.001
 * }
 */
public class SourceSinkComponent extends GasNetworkComponent {

    /** Konstanter Druck der Quelle/Senke [Pa] */
    public double sourcePressure;

    /** Proportionalitätsfaktor [mol/(s·Pa)] */
    public double flowFactor;

    /** true = Quelle, false = Senke */
    public boolean isSource;

    public static final BuilderCodec<SourceSinkComponent> SOURCE_CODEC = buildCodec(true);
    public static final BuilderCodec<SourceSinkComponent> SINK_CODEC   = buildCodec(false);

    private static BuilderCodec<SourceSinkComponent> buildCodec(boolean source) {
        return BuilderCodec
                .builder(SourceSinkComponent.class, () -> {
                    SourceSinkComponent c = new SourceSinkComponent();
                    c.isSource = source;
                    return c;
                })
                .append(new KeyedCodec<>("volume",         Codec.DOUBLE),
                        (c, v) -> c.volume         = v, c -> c.volume).add()
                .append(new KeyedCodec<>("sourcePressure", Codec.DOUBLE),
                        (c, v) -> c.sourcePressure = v, c -> c.sourcePressure).add()
                .append(new KeyedCodec<>("flowFactor",     Codec.DOUBLE),
                        (c, v) -> c.flowFactor     = v, c -> c.flowFactor).add()
                .build();
    }

    @Override public String getType() { return isSource ? "source" : "sink"; }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        SourceSinkComponent flux = new SourceSinkComponent();

        // Quelle auf der from-Seite: pusht Teilchen in den Nachbar-Node
        if (from instanceof SourceSinkComponent src) {
            double dp = src.sourcePressure - to.pressure();
            flux.particleCount = dp * src.flowFactor;
        }
        // Senke auf der to-Seite: saugt Teilchen aus dem from-Node
        else if (to instanceof SourceSinkComponent sink) {
            double dp = from.pressure() - sink.sourcePressure;
            flux.particleCount = dp * sink.flowFactor;
        }

        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        SourceSinkComponent c = new SourceSinkComponent();
        c.particleCount  = this.particleCount;
        c.energy         = this.energy;
        c.volume         = this.volume;
        c.sourcePressure = this.sourcePressure;
        c.flowFactor     = this.flowFactor;
        c.isSource       = this.isSource;
        return c;
    }
}
