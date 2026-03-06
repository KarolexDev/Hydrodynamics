package com.example.exampleplugin.gasnetwork;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class GasNetworkSystem {
    public GasNetworkSystem() { /* Utility Class */ }

    public static class NetworkTickingSystem extends TickingSystem<EntityStore> {

        @Override
        public void tick(float dt, int index, @NonNull Store<EntityStore> store) {
            GasNetworkResource network = store.getResource(GasNetworkResource.getResourceType());
            World world = Universe.get().getDefaultWorld();
            network.tick(dt, world);
        }
    }

    public static class NetworkBlockPlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        public NetworkBlockPlaceEventSystem() { super(PlaceBlockEvent.class); }

        @Override
        public void handle(int index, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> entityStore, @NonNull CommandBuffer<EntityStore> commandBuffer, @NonNull PlaceBlockEvent placeBlockEvent) {
            try {
                World world = Universe.get().getDefaultWorld();
                if (world == null) return;

                Vector3i targetPos = placeBlockEvent.getTargetBlock();
                int x = targetPos.getX();
                int y = targetPos.getY();
                int z = targetPos.getZ();

                GasNetworkResource network = entityStore.getResource(GasNetworkResource.getResourceType());

                world.execute(() -> {
                    try {
                        BlockType bt = world.getBlockType(x, y, z);
                        Holder<ChunkStore> blockEntity = bt.getBlockEntity();
                        if (blockEntity == null) return;

                        GasNetworkComponent component = blockEntity.getComponent(GasNetworkComponent.getComponentType());
                        if (component == null) return;

                        var chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));

                        // TODO: Improve later: Can get bounding box first, so it doesn't have to get it multiple times (unless it doesn't...?)
                        network.onBlockPlaced(
                                new Vector3i(x, y, z),
                                chunk,
                                component.copy(),    // COPY is important!
                                bt
                        );
                        System.out.println("placed");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    public static class NetworkBlockBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        public NetworkBlockBreakEventSystem() { super(BreakBlockEvent.class); }

        @Override
        public void handle(int i, @NonNull ArchetypeChunk<EntityStore> archetypeChunk, @NonNull Store<EntityStore> entityStore, @NonNull CommandBuffer<EntityStore> commandBuffer, @NonNull BreakBlockEvent breakBlockEvent) {
            try {
                World world = Universe.get().getDefaultWorld();
                if (world == null) return;

                Vector3i targetPos = breakBlockEvent.getTargetBlock();
                int x = targetPos.getX();
                int y = targetPos.getY();
                int z = targetPos.getZ();

                GasNetworkResource network = entityStore.getResource(GasNetworkResource.getResourceType());

                world.execute(() -> {
                    try {
                        BlockType bt = breakBlockEvent.getBlockType();
                        Holder<ChunkStore> blockEntity = bt.getBlockEntity();
                        if (blockEntity == null) return;

                        var chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        network.onBlockRemoved(
                                new Vector3i(x, y, z),
                                chunk,
                                bt
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public @Nullable Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }
}
