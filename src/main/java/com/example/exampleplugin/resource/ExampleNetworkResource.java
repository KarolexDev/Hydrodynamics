package com.example.exampleplugin.resource;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetwork;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class ExampleNetworkResource extends BlockNetwork<ExampleComponent> implements Resource<EntityStore> {

    public static ResourceType<EntityStore, ExampleNetworkResource> getResourceType() { return ExamplePlugin.getInstance().getExampleNetworkResourceType(); }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return null;
    }

    @Override
    public void runOnBlockAdded() {
        return;
    }

    @Override
    public void runOnBlockRemoved() {
        return;
    }
}
