package com.example.exampleplugin.component;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.physics.EntropicSystem;
import com.example.exampleplugin.physics.systems.IdealGas;
import com.google.protobuf.FieldType;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

public class ThermalComponent implements Component<ChunkStore> {
    public IdealGas data = new IdealGas(0f, 0f, 0f);

    public static final BuilderCodec<ThermalComponent> CODEC =
            BuilderCodec.builder(ThermalComponent.class, ThermalComponent::new)
                    .append(new KeyedCodec<>("Energy", Codec.FLOAT),
                            (c, v) -> c.data.addEnergy(1.5f * EntropicSystem.GAS_CONSTANT * 100f * v), c -> c.data.getParticleCount())
                    .add()
                    .append(new KeyedCodec<>("Volume", Codec.FLOAT),
                            (c, v) -> c.data.addVolume(v), c -> c.data.getParticleCount())
                    .add()
                    .append(new KeyedCodec<>("ParticleCount", Codec.FLOAT),
                            (c, v) -> c.data.addParticles(v), c -> c.data.getParticleCount())
                    .add()
                    .build();

    public ThermalComponent() {};

    public ThermalComponent(
            float energy,
            float volume,
            float molarity
    ) {
        this.data = new IdealGas(energy, volume, molarity);
    }

    public static ComponentType<ChunkStore, ThermalComponent> getComponentType() {
        return ExamplePlugin.getInstance().getThermalComponentType();
    }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return new ThermalComponent(this.data.getEnergy(), this.data.getVolume(), this.data.getParticleCount());
    }
}
