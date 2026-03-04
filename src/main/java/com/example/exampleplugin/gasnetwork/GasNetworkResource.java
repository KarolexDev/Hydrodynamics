package com.example.exampleplugin.gasnetwork;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.blocknetwork.BlockNetwork;
import com.example.exampleplugin.blocknetwork.BlockNetworkManager;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class GasNetworkResource extends BlockNetworkManager<GasNetworkComponent, GasNetworkResource.GasNetwork>
        implements Resource<EntityStore> {

    public static class GasNetwork extends BlockNetwork<GasNetworkComponent> {
        public GasNetwork() {
            super(GasNetwork::new);
        }

        @Override
        public void runOnBlockAdded() {}

        @Override
        public void runOnBlockRemoved() {}

        @Override
        protected BuilderCodec<GasNetworkComponent> getComponentCodec() {
            return GasNetworkComponent.CODEC;
        }
    }

    public static final BuilderCodec<GasNetworkResource> CODEC =
            BlockNetworkManager.createCodec(
                    GasNetworkResource.class,
                    GasNetworkResource::new,
                    BlockNetwork.createCodec(
                            GasNetwork.class,
                            GasNetwork::new,
                            GasNetworkComponent.CODEC
                    )
            );

    public GasNetworkResource() {
        super(GasNetwork::new);
    }

    public static ResourceType<EntityStore, GasNetworkResource> getResourceType() {
        return ExamplePlugin.getInstance().geGasNetworkResourceType();
    }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        GasNetworkResource clone = new GasNetworkResource();
        for (GasNetwork network : networks) {
            GasNetwork clonedNetwork = new GasNetwork();
            clonedNetwork.deserializeNodes(network.serializeNodes());
            clonedNetwork.deserializeEdges(network.serializeEdges());
            clone.networks.add(clonedNetwork);
        }
        return clone;
    }
}
