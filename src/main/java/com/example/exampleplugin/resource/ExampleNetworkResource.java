package com.example.exampleplugin.resource;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetwork;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;
import com.example.exampleplugin.util.BlockNetworkSerialization.*;

public class ExampleNetworkResource extends BlockNetwork<ExampleComponent> implements Resource<EntityStore> {

    public static final BuilderCodec<ExampleNetworkResource> CODEC;

    public ExampleNetworkResource() {
        super();
    }

    public static ResourceType<EntityStore, ExampleNetworkResource> getResourceType() { return ExamplePlugin.getInstance().getExampleNetworkResourceType(); }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        ExampleNetworkResource clone = new ExampleNetworkResource();

        // Nodes wiederherstellen
        NodeDTO[] nodeDTOs = this.serializeNodes();
        clone.deserializeNodes(nodeDTOs);

        // Edges wiederherstellen
        EdgeDTO[] edgeDTOs = this.serializeEdges();
        clone.deserializeEdges(edgeDTOs);

        return clone;
    }

    @Override
    public void runOnBlockAdded() {
        return;
    }

    @Override
    public void runOnBlockRemoved() {
        return;
    }

    @Override
    protected BuilderCodec<ExampleComponent> getComponentCodec() {
        return ExampleComponent.CODEC;
    }

    static {
        CODEC =
                BlockNetwork.createCodec(
                        ExampleNetworkResource.class,
                        ExampleNetworkResource::new,
                        ExampleComponent.CODEC
                );
    }
}
