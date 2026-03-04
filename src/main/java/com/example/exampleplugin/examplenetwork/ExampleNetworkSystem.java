package com.example.exampleplugin.examplenetwork;

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

import java.util.List;

import static com.example.exampleplugin.util.MultiBlockUtil.getOccupiedPositions;

public class ExampleNetworkSystem {
    public ExampleNetworkSystem() { /* Utility Class */ }

    public static class NetworkTickingSystem extends TickingSystem<EntityStore> {

        @Override
        public void tick(float dt, int index, @NonNull Store<EntityStore> store) {
            ExampleNetworkResource network = store.getResource(ExampleNetworkResource.getResourceType());
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

                ExampleNetworkResource network = entityStore.getResource(ExampleNetworkResource.getResourceType());

                world.execute(() -> {
                    try {
                        BlockType bt = world.getBlockType(x, y, z);
                        Holder<ChunkStore> blockEntity = bt.getBlockEntity();
                        if (blockEntity == null) return;

                        ExampleComponent component = blockEntity.getComponent(ExampleComponent.getComponentType());
                        if (component == null) return;

                        List<Vector3i> occupiedPositions = getOccupiedPositions(bt, targetPos);
                        var chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        network.onBlockPlaced(
                                new Vector3i(x, y, z),
                                occupiedPositions,
                                chunk,
                                new ExampleComponent()
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

                ExampleNetworkResource network = entityStore.getResource(ExampleNetworkResource.getResourceType());

                world.execute(() -> {
                    try {
                        // WICHTIG: BlockType VOR dem Entfernen lesen – nach dem Break ist er weg
                        BlockType bt = world.getBlockType(x, y, z);
                        Holder<ChunkStore> blockEntity = bt.getBlockEntity();
                        if (blockEntity == null) return;  // war auch ein Bug: != statt ==

                        List<Vector3i> occupiedPositions = getOccupiedPositions(bt, targetPos);
                        var chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        network.onBlockRemoved(
                                new Vector3i(x, y, z),
                                occupiedPositions,
                                chunk
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
