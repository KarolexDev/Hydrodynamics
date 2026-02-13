package com.example.exampleplugin.system;

import com.example.exampleplugin.component.ThermalComponent;
import com.example.exampleplugin.physics.systems.Environment;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static com.example.exampleplugin.system.ConnectedPipeUtil.getConnectedPipes;

public class PipeFlowUpdateSystem extends EntityTickingSystem<ChunkStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final ComponentType<ChunkStore, ThermalComponent> thermodynamicEnsembleComponentType = ThermalComponent.getComponentType();
    private final Query<ChunkStore> query = Query.and(new Query[]{ThermalComponent.getComponentType()});

    public PipeFlowUpdateSystem() {
        super();
    }

    @Override
    public void tick(
            float dt,
            int archetypeChunkIndex,
            @NonNull ArchetypeChunk<ChunkStore> chunk,
            @NonNull Store<ChunkStore> store,
            @NonNull CommandBuffer<ChunkStore> commandBuffer
    ) {
        BlockModule.BlockStateInfo stateInfo = chunk.getComponent(archetypeChunkIndex, BlockModule.BlockStateInfo.getComponentType());
        if (stateInfo == null) { return; }
        WorldChunk wc = commandBuffer.getComponent(stateInfo.getChunkRef(), WorldChunk.getComponentType());
        World world = wc.getWorld();

        int blockIndex = stateInfo.getIndex();

        int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
        int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);

        int worldX = ChunkUtil.worldCoordFromLocalCoord(wc.getX(), localX);
        // worldY is the same as localY
        int worldZ = ChunkUtil.worldCoordFromLocalCoord(wc.getZ(), localZ);

        Vector3i originCoords = new Vector3i(worldX, localY, worldZ);

        commandBuffer.run((s) -> {
            ThermalComponent originBlockThermalComponent = store.getComponent(chunk.getReferenceTo(archetypeChunkIndex), ThermalComponent.getComponentType());

            for (ConnectedPipeUtil.ConnectedPipe connectedPipe : getConnectedPipes(originCoords, chunk, commandBuffer, world)) {
                Vector3i neighborCoords = connectedPipe.position();
                if (!shouldProcessPair(originCoords, neighborCoords)) {
                    continue;
                }
                Ref<ChunkStore> connectedPipeRef = connectedPipe.ref();

                ThermalComponent neighborBlockThermalComponent = store.getComponent(connectedPipeRef, ThermalComponent.getComponentType());

                originBlockThermalComponent.data.equilibrateWith(
                        neighborBlockThermalComponent.data,
                        dt
                );
            }
            // originBlockThermalComponent.data.equilibrateWith(
            //         new Environment(),  // <-- Can be done better
            //         dt
            // );
        });
    }

    private boolean shouldProcessPair(Vector3i origin, Vector3i neighbor) {
        if (origin.x != neighbor.x) {
            return origin.x < neighbor.x;
        }
        if (origin.y != neighbor.y) {
            return origin.y < neighbor.y;
        }
        return origin.z < neighbor.z;
    }

    @Override
    public @Nullable Query<ChunkStore> getQuery() {
        return this.query;
    }
}