package com.example.exampleplugin.component;

import com.example.exampleplugin.blocknetwork.BlockNetworkComponent;
import com.example.exampleplugin.component.gasnetworkcomponenttypes.*;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Basisklasse für alle Gas-Netzwerk-Komponenten.
 *
 * Dynamische Variablen (ändern sich zur Laufzeit):
 *   - particleCount  [mol]
 *   - energy         [J]
 *
 * Strukturelle Parameter (aus .json, konstant):
 *   - volume  [m³]  (extensiv → wird bei Node-Erweiterung addiert)
 *   - typ-spezifische Parameter in den jeweiligen Subklassen
 *
 * Dispatch-Codec: liest zuerst "type", wählt dann den richtigen Subklassen-Codec.
 */
public abstract class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent> {

    public static final double R = 8.314; // J/(mol·K)

    // -------------------------------------------------------------------------
    // Dynamische Variablen
    // -------------------------------------------------------------------------
    public double particleCount;
    public double energy;

    // -------------------------------------------------------------------------
    // Strukturelle Parameter (gemeinsam für alle Typen)
    // -------------------------------------------------------------------------
    public double volume;

    // -------------------------------------------------------------------------
    // Dispatch-Codec
    // -------------------------------------------------------------------------
    public static final Codec<GasNetworkComponent> CODEC = new Codec<>() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        private final Map<String, BuilderCodec<? extends GasNetworkComponent>> registry;
        {
            Map m = new HashMap();
            m.put("storage",        StorageComponent.CODEC);
            m.put("source",         SourceSinkComponent.SOURCE_CODEC);
            m.put("sink",           SourceSinkComponent.SINK_CODEC);
            m.put("reactor",        ReactorComponent.CODEC);
            m.put("heat_exchanger", HeatExchangerComponent.CODEC);
            m.put("decompressor",   DecompressorComponent.CODEC);
            m.put("pump",           PumpComponent.CODEC);
            m.put("valve",          ValveComponent.CODEC);
            registry = (Map<String, BuilderCodec<? extends GasNetworkComponent>>) m;
        }

        @Override
        public GasNetworkComponent decode(BsonValue bsonValue, ExtraInfo extraInfo) {
            BsonDocument doc = bsonValue.asDocument();
            String type = doc.getString("type").getValue();
            BuilderCodec<? extends GasNetworkComponent> codec = registry.get(type);
            if (codec == null) throw new IllegalArgumentException("Unknown gas component type: " + type);
            return codec.decode(bsonValue, extraInfo);
        }

        @Override
        @SuppressWarnings("unchecked")
        public BsonValue encode(GasNetworkComponent value, ExtraInfo extraInfo) {
            String type = value.getType();
            BuilderCodec<GasNetworkComponent> codec = (BuilderCodec<GasNetworkComponent>) registry.get(type);
            if (codec == null) throw new IllegalArgumentException("Unknown gas component type: " + type);
            BsonDocument doc = codec.encode(value, extraInfo);
            doc.put("type", new BsonString(type));
            return doc;
        }

        @Override
        public Schema toSchema(SchemaContext context) {
            return new ObjectSchema();
        }
    };

    public abstract String getType();

    // -------------------------------------------------------------------------
    // Physik-Hilfsmethoden
    // -------------------------------------------------------------------------

    public double pressure() {
        if (volume <= 0) return 0;
        return (particleCount * R * temperature()) / volume;
    }

    public double temperature() {
        if (particleCount <= 0) return 293.0;
        return energy / (particleCount * 1.5 * R);
    }

    // -------------------------------------------------------------------------
    // BlockNetworkComponent – gemeinsame Implementierungen
    // -------------------------------------------------------------------------

    @Override
    public GasNetworkComponent add(GasNetworkComponent other) {
        particleCount += other.particleCount;
        energy        += other.energy;
        volume        += other.volume; // extensiv
        return this;
    }

    @Override
    public GasNetworkComponent del(GasNetworkComponent flux) {
        particleCount -= flux.particleCount;
        energy        -= flux.energy;
        return this;
    }

    @Override
    public GasNetworkComponent[] partition(int leftSize, int rightSize) {
        double frac = (double) leftSize / (leftSize + rightSize);

        GasNetworkComponent left  = copy();
        left.particleCount  = this.particleCount * frac;
        left.energy         = this.energy        * frac;
        left.volume         = this.volume        * frac;

        GasNetworkComponent right = copy();
        right.particleCount = this.particleCount * (1 - frac);
        right.energy        = this.energy        * (1 - frac);
        right.volume        = this.volume        * (1 - frac);

        return new GasNetworkComponent[]{left, right};
    }

    @Override
    public GasNetworkComponent zero() {
        return (GasNetworkComponent) new StorageComponent();
    }

    @Override
    public float changeRate(GasNetworkComponent previous) {
        double dn = Math.abs(particleCount - previous.particleCount);
        double dE = Math.abs(energy        - previous.energy);
        double normN = particleCount > 0 ? dn / particleCount : dn;
        double normE = energy        > 0 ? dE / energy        : dE;
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
    public boolean requiresWorldUpdate() { return false; }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {}

    // Hilfsmethode für Flux-Berechnung
    public double defaultFlowFactor(GasNetworkComponent other) {
        return ((this.volume + other.volume) / 2.0) * 0.01;
    }

    // Konvektiver Energietransport
    public double convectiveEnergy(double particleFlux, GasNetworkComponent source) {
        return particleFlux * source.temperature() * 1.5 * R;
    }
}