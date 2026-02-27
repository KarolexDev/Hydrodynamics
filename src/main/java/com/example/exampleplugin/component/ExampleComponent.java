package com.example.exampleplugin.component;

import com.example.exampleplugin.network.BlockNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

public class ExampleComponent implements BlockNetworkComponent, Component<ChunkStore> {

    public double val;

    public static final BuilderCodec<ExampleComponent> CODEC =
            BuilderCodec.builder(ExampleComponent.class, ExampleComponent::new)
                    .append(new KeyedCodec<>("ExampleValue", Codec.DOUBLE),
                            (c, v) -> c.val = v, c -> c.val)
                    .add()
                    .build();

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return null;
    }

    @Override
    public void del(BlockNetworkComponent flux) {

    }

    @Override
    public void add(BlockNetworkComponent flux) {

    }
}
