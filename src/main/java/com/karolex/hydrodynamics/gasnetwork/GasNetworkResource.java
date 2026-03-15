package com.karolex.hydrodynamics.gasnetwork;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;
import com.karolex.hydrodynamics.HydrodynamicsPlugin;
import com.karolex.hydrodynamics.blocknetwork.BlockNetwork;
import com.karolex.hydrodynamics.blocknetwork.BlockNetworkManager;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class GasNetworkResource extends BlockNetworkManager<GasNetworkComponent, GasNetworkResource.GasNetwork>
        implements Resource<EntityStore> {

    public void onValveToggled(Vector3i pos) {
        GasNetworkComponent comp = getComponent(pos);
        if (comp == null) return;
        comp.isClosed = !comp.isClosed;
    }

    public static class GasNetwork extends BlockNetwork<GasNetworkComponent> {
        public GasNetwork() {
            super(Universe.get().getDefaultWorld(), GasNetwork::new);
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
        return HydrodynamicsPlugin.getInstance().geGasNetworkResourceType();
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
