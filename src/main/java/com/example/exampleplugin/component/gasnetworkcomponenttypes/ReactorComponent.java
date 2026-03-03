package com.example.exampleplugin.component.gasnetworkcomponenttypes;

import com.example.exampleplugin.component.GasNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Reaktorkammer – wandelt Edukte in Produkte um.
 * Konvektiver Teilchenfluss mit Nachbar-Nodes läuft normal über calculateFlux.
 * Die eigentliche Reaktion passiert in onWorldUpdate.
 *
 * JSON-Beispiel (2H₂ + O₂ → 2H₂O, exotherm):
 * {
 *   "type": "reactor",
 *   "volume": 2.0,
 *   "initialParticles": 10.0,
 *   "initialEnergy": 1000.0,
 *   "reactantCoefficients": [2.0, 1.0],
 *   "productCoefficients":  [2.0],
 *   "reactionEnergy": 484000.0,
 *   "reactionRate": 0.01
 * }
 *
 * Hinweis: gasType-Unterscheidung ist hier vereinfacht weggelassen.
 * Falls mehrere Gastypen nötig sind, muss particleCount zu Map<String,Double>.
 */
public class ReactorComponent extends GasNetworkComponent {

    /** Stöchiometrische Koeffizienten der Edukte */
    public double[] reactantCoefficients;

    /** Stöchiometrische Koeffizienten der Produkte */
    public double[] productCoefficients;

    /** J/mol – positiv = exotherm, negativ = endotherm */
    public double reactionEnergy;

    /** mol/s – maximale Reaktionsrate */
    public double reactionRate;

    public static final BuilderCodec<ReactorComponent> CODEC = BuilderCodec
            .builder(ReactorComponent.class, ReactorComponent::new)
            .append(new KeyedCodec<>("volume",                Codec.DOUBLE),
                    (c, v) -> c.volume                 = v, c -> c.volume).add()
            .append(new KeyedCodec<>("initialParticles",      Codec.DOUBLE),
                    (c, v) -> c.particleCount           = v, c -> c.particleCount).add()
            .append(new KeyedCodec<>("initialEnergy",         Codec.DOUBLE),
                    (c, v) -> c.energy                 = v, c -> c.energy).add()
            .append(new KeyedCodec<>("reactantCoefficients", Codec.DOUBLE_ARRAY),
                    (c, v) -> c.reactantCoefficients = v, c -> c.reactantCoefficients).add()
            .append(new KeyedCodec<>("productCoefficients",  Codec.DOUBLE_ARRAY),
                    (c, v) -> c.productCoefficients  = v, c -> c.productCoefficients).add()
            .append(new KeyedCodec<>("reactionEnergy",        Codec.DOUBLE),
                    (c, v) -> c.reactionEnergy         = v, c -> c.reactionEnergy).add()
            .append(new KeyedCodec<>("reactionRate",          Codec.DOUBLE),
                    (c, v) -> c.reactionRate           = v, c -> c.reactionRate).add()
            .build();

    @Override public String getType() { return "reactor"; }

    @Override
    public boolean requiresWorldUpdate() { return true; }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {
        if (reactantCoefficients == null || productCoefficients == null) return;

        // Limitierenden Reaktant bestimmen
        double maxExtent = reactionRate;
        for (double coeff : reactantCoefficients) {
            if (coeff > 0) maxExtent = Math.min(maxExtent, particleCount / coeff);
        }
        if (maxExtent <= 0) return;

        // Edukte verbrauchen (vereinfacht: alle teilen sich particleCount)
        double totalReactants = 0;
        for (double c : reactantCoefficients) totalReactants += c;
        particleCount -= totalReactants * maxExtent;

        // Produkte erzeugen
        double totalProducts = 0;
        for (double c : productCoefficients) totalProducts += c;
        particleCount += totalProducts * maxExtent;

        // Energie
        energy += reactionEnergy * maxExtent;
    }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        // Normaler konvektiver Fluss
        ReactorComponent flux = new ReactorComponent();
        double dp = from.pressure() - to.pressure();
        flux.particleCount = dp * from.defaultFlowFactor(to);
        flux.energy = from.convectiveEnergy(flux.particleCount, flux.particleCount > 0 ? from : to);
        return flux;
    }

    @Override
    public GasNetworkComponent copy() {
        ReactorComponent c = new ReactorComponent();
        c.particleCount        = this.particleCount;
        c.energy               = this.energy;
        c.volume               = this.volume;
        c.reactantCoefficients = this.reactantCoefficients;
        c.productCoefficients  = this.productCoefficients;
        c.reactionEnergy       = this.reactionEnergy;
        c.reactionRate         = this.reactionRate;
        return c;
    }
}
