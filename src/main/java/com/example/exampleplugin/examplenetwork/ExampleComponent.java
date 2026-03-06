package com.example.exampleplugin.examplenetwork;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.blocknetwork.BlockNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;
@Deprecated
public class ExampleComponent implements BlockNetworkComponent<ExampleComponent>, Component<ChunkStore> {

    public double val;

    public static final BuilderCodec<ExampleComponent> CODEC =
            BuilderCodec.builder(ExampleComponent.class, ExampleComponent::new)
                    .append(new KeyedCodec<>("ExampleValue", Codec.DOUBLE),
                            (c, v) -> c.val = v, c -> c.val)
                    .add()
                    .build();

    public static ComponentType<ChunkStore, ExampleComponent> getComponentType() { return ExamplePlugin.getInstance().getExampleComponentType(); }

    public ExampleComponent() { this.val = 0; }

    public ExampleComponent(double val) { this.val = val; }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return new ExampleComponent(this.val);
    }

    @Override
    public ExampleComponent add(ExampleComponent flux) {
        return null;
    }

    @Override
    public ExampleComponent del(ExampleComponent flux) {
        return this;
    }

    @Override
    public ExampleComponent mergeComponents(ExampleComponent flux) {
        return this;
    }

    @Override
    public ExampleComponent calculateFlux(float dt, ExampleComponent from, ExampleComponent to) {
        return this;
    }

    public ExampleComponent zero() {
        return new ExampleComponent(0d);
    }

    @Override
    public boolean requiresWorldUpdate() {
        return false;
    }

    @Override
    public void onWorldUpdate(Vector3i pos, World world) {}

    @Override
    public ExampleComponent copy() {
        return new ExampleComponent(this.val);
    }

    @Override
    public float changeRate(ExampleComponent previous) {
        return 0;
    }

    @Override
    public float computeDelay(float changeRate) {
        return 0;
    }

    @Override
    public boolean shouldMerge(ExampleComponent other) {
        return false;
    }

    @Override
    public ExampleComponent[] partition(int left_size, int right_size) {
        ExampleComponent[] result = new ExampleComponent[2];
        result[0] = new ExampleComponent();
        result[1] = new ExampleComponent();
        return result;
    }
}
