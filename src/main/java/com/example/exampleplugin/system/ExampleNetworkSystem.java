package com.example.exampleplugin.system;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.Resource.ExampleNetworkResource;
import com.example.exampleplugin.component.ExampleComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public class ExampleNetworkSystem {
    public ExampleNetworkSystem() { /* Utility Class */ }

//    public static class NetworkTickingSystem extends EntityTickingSystem<EntityStore> {
//
//        @Override
//        public void tick(float v, int i, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> store, @NonNull CommandBuffer<EntityStore> commandBuffer) {
//            ExamplePlugin.getInstance();
//        }
//
//        @Override
//        public @Nullable Query<EntityStore> getQuery() {
//            return null;
//        }
//    }

    public static class NetworkBlockPlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        public NetworkBlockPlaceEventSystem() { super(PlaceBlockEvent.class); }


        @Override
        public void handle(int index, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> store, @NonNull CommandBuffer<EntityStore> commandBuffer, @NonNull PlaceBlockEvent placeBlockEvent) {
            try {
                World world = Universe.get().getDefaultWorld();
                if (world == null) {
                    return;
                }

                Vector3i targetPos = placeBlockEvent.getTargetBlock();
                int x = targetPos.getX();
                int y = targetPos.getY();
                int z = targetPos.getZ();
                world.execute(() -> {
                    BlockType bt = world.getBlockType(x, y, z);
                    Holder<ChunkStore> blockEntity = bt.getBlockEntity();
                    if (blockEntity == null) {
                        return;
                    }
                    // Break Block Event
                    else if (Objects.equals(bt.getId(), "Empty")) {
                        return;
                    }
                    // Place Block Event
                    else {
                        ExampleComponent component = blockEntity.getComponent(ExampleComponent.getComponentType());
                        if (component != null) {
                            ExampleNetworkResource network = store.getResource(ExampleNetworkResource.getResourceType());
                            network.onBlockPlaced(
                                    new Vector3i(x, y, z),
                                    blockEntity.getComponent(WorldChunk.getComponentType()),
                                    ExampleComponent.zero()
                            );
                        }
                    }
                    // SignalSourceManager.updateNetwork(world, x, y, z);
                    // SignalSourceManager.updateNeighborShapes(world, x, y, z);
                });
            } catch (Exception e) {

            }
        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }
}
