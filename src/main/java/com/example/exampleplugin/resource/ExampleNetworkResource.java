package com.example.exampleplugin.resource;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetwork;
import com.example.exampleplugin.network.BlockNetworkManager;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;
import com.example.exampleplugin.util.BlockNetworkSerialization.*;

public class ExampleNetworkResource extends BlockNetworkManager<ExampleComponent, ExampleNetworkResource.ExampleNetwork>
        implements Resource<EntityStore> {

    public static class ExampleNetwork extends BlockNetwork<ExampleComponent> {

        @Override
        public void runOnBlockAdded() {}

        @Override
        public void runOnBlockRemoved() {}

        @Override
        protected BuilderCodec<ExampleComponent> getComponentCodec() {
            return ExampleComponent.CODEC;
        }
    }

    public static final BuilderCodec<ExampleNetworkResource> CODEC =
            BlockNetworkManager.createCodec(
                    ExampleNetworkResource.class,
                    ExampleNetworkResource::new,
                    BlockNetwork.createCodec(
                            ExampleNetwork.class,
                            ExampleNetwork::new,
                            ExampleComponent.CODEC
                    )
            );

    public ExampleNetworkResource() {
        super(ExampleNetwork::new);
    }

    public static ResourceType<EntityStore, ExampleNetworkResource> getResourceType() {
        return ExamplePlugin.getInstance().getExampleNetworkResourceType();
    }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return null;
    }
}
