package com.example.exampleplugin.system;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.resource.ExampleNetwork;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.view.event.block.BlockEventType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExampleNetworkSystem {

    public static class NetworkAddedSystem extends HolderSystem<EntityStore> {

        @Override
        public void onEntityAdd(@NonNull Holder<EntityStore> holder, @NonNull AddReason addReason, @NonNull Store<EntityStore> store) {

        }

        @Override
        public void onEntityRemoved(@NonNull Holder<EntityStore> holder, @NonNull RemoveReason removeReason, @NonNull Store<EntityStore> store) {

        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return null;
        }
    }

    public static class NetworkBlockPlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        public NetworkBlockPlaceEventSystem() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public void handle(
                int archetypeChunkIndex,
                @NonNull ArchetypeChunk<EntityStore> chunk,
                @NonNull Store<EntityStore> store,
                @NonNull CommandBuffer<EntityStore> commandBuffer,
                @NonNull PlaceBlockEvent placeBlockEvent) {
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
                    Holder<ChunkStore> cockEntity = bt.getBlockEntity();
                    if (cockEntity == null) {
                        return;
                    }
                    // Break Block Event
                    else if (Objects.equals(bt.getId(), "Empty")) {
                        return;
                    }
                    // Place Block Event
                    else {
                        ExampleComponent component = cockEntity.getComponent(ExampleComponent.getComponentType());
                        if (component != null) {
                            ExampleNetwork network = store.getResource(ExampleNetwork.getResourceType());
                            network.onBlockPlaced(
                                    new Vector3i(x, y, z),
                                    new ExampleNetwork.Component(420)
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

    public static class NetworkBlockBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        public NetworkBlockBreakEventSystem() {
            super(BreakBlockEvent.class);
        }

        @Override
        public void handle(
                int archetypeChunkIndex,
                @NonNull ArchetypeChunk<EntityStore> chunk,
                @NonNull Store<EntityStore> store,
                @NonNull CommandBuffer<EntityStore> commandBuffer,
                @NonNull BreakBlockEvent breakBlockEvent) {
            try {
                Vector3i targetPos = breakBlockEvent.getTargetBlock();
                BlockType bt = breakBlockEvent.getBlockType();
                if (targetPos == null || Objects.equals(bt.getId(), "Empty")) {
                    return;
                }

                World world = Universe.get().getDefaultWorld();
                if (world == null) {
                    return;
                }

                int x = targetPos.getX();
                int y = targetPos.getY();
                int z = targetPos.getZ();

                world.execute(() -> {
                    Holder<ChunkStore> cockEntity = bt.getBlockEntity();
                    if (cockEntity == null || Objects.equals(bt.getId(), "Empty")) {
                        return;
                    }
                    ExampleComponent component = cockEntity.getComponent(ExampleComponent.getComponentType());
                    if (component != null) {
                        ExampleNetwork network = store.getResource(ExampleNetwork.getResourceType());
                        network.onBlockRemoved(
                                new Vector3i(x, y, z)
                        );
                    }
                });
            } catch (Exception e) {

            }
        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    public static class NetworkTickingSystem extends EntityTickingSystem<EntityStore> {

        @Override
        public void tick(float v, int i, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> store, @NonNull CommandBuffer<EntityStore> commandBuffer) {

        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return null;
        }
    }
}
