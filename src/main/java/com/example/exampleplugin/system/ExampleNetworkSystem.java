package com.example.exampleplugin.system;

import com.example.exampleplugin.ExamplePlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ExampleNetworkSystem {
    public ExampleNetworkSystem() { /* Utility Class */ }

    public static class NetworkTickingSystem extends EntityTickingSystem<EntityStore> {

        @Override
        public void tick(float v, int i, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> store, @NonNull CommandBuffer<EntityStore> commandBuffer) {
            ExamplePlugin.getInstance();
        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return null;
        }
    }

}
