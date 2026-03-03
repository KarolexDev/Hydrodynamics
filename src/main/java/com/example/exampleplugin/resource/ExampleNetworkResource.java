package com.example.exampleplugin.resource;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.blocknetwork.BlockNetwork;
import com.example.exampleplugin.blocknetwork.BlockNetworkManager;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class ExampleNetworkResource extends BlockNetworkManager<ExampleComponent, ExampleNetworkResource.ExampleNetwork>
        implements Resource<EntityStore> {

    public static class ExampleNetwork extends BlockNetwork<ExampleComponent> {
        public ExampleNetwork() {
            super(ExampleNetwork::new);
        }

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
        ExampleNetworkResource clone = new ExampleNetworkResource();
        for (ExampleNetwork network : networks) {
            ExampleNetwork clonedNetwork = new ExampleNetwork();
            clonedNetwork.deserializeNodes(network.serializeNodes());
            clonedNetwork.deserializeEdges(network.serializeEdges());
            clone.networks.add(clonedNetwork);
        }
        return clone;
    }
}
