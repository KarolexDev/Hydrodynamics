package com.example.exampleplugin.component;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.physics.systems.IdealGas;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

public class ThermodynamicEnsembleComponent implements Component<ChunkStore> {
    public IdealGas data = new IdealGas(0f, 0f, 0f);

    public static final BuilderCodec<ThermodynamicEnsembleComponent> CODEC =
            BuilderCodec.builder(ThermodynamicEnsembleComponent.class, ThermodynamicEnsembleComponent::new)
                    .append(new KeyedCodec<>("InitialTemperature", Codec.FLOAT),
                            (c, v) -> c.data.entropy = IdealGas.toEntropy(v, 64f, 100f), c -> c.data.entropy)
                    .add()
                    .append(new KeyedCodec<>("Volume", Codec.FLOAT),
                            (c, v) -> c.data.volume = v, c -> c.data.volume)
                    .add()
                    .append(new KeyedCodec<>("Molarity", Codec.FLOAT),
                            (c, v) -> c.data.molarity = v, c -> c.data.molarity)
                    .add()
                    .build();

    public ThermodynamicEnsembleComponent() {};

    public ThermodynamicEnsembleComponent(
            float entropy,
            float volume,
            float molarity
    ) {
        this.data = new IdealGas(entropy, volume, molarity);
    }

    public static ComponentType<ChunkStore, ThermodynamicEnsembleComponent> getComponentType() {
        return ExamplePlugin.getInstance().getThermodynamicEnsembleComponentType();
    }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return new ThermodynamicEnsembleComponent(this.data.entropy, this.data.volume, this.data.molarity);
    }
}
