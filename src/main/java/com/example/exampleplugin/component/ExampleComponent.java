package com.example.exampleplugin.component;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.network.NetworkComponent;
import com.example.exampleplugin.network.NetworkUtil;
import com.example.exampleplugin.physics.EntropicSystem;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class ExampleComponent implements Component<ChunkStore>, NetworkComponent {

    public double val;

    public static final BuilderCodec<ExampleComponent> CODEC =
            BuilderCodec.builder(ExampleComponent.class, ExampleComponent::new)
                    .append(new KeyedCodec<>("ExampleValue", Codec.DOUBLE),
                            (c, v) -> c.val = v, c -> c.val)
                    .add()
                    .build();

    public ExampleComponent() {}

    public ExampleComponent(double val) { this.val = val; }

    public static ComponentType<ChunkStore, ExampleComponent> getComponentType() { return ExamplePlugin.getInstance().getExampleComponentType(); }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return new ExampleComponent(this.val);
    }

    @Override
    public NetworkComponent add(NetworkComponent otherComponent) {
        return null;
    }

    @Override
    public <T extends NetworkComponent> T fromLength(int length) {
        return null;
    }

    @Override
    public <T extends NetworkComponent> NetworkUtil.Pair<T, T> partition(int a, int b) {
        return null;
    }

    @Override
    public <T extends NetworkComponent> T del(NetworkComponent otherComponent) {
        return null;
    }
}
