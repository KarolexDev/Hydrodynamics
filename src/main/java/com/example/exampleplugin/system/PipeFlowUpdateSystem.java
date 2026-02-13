package com.example.exampleplugin.system;

import com.example.exampleplugin.component.ThermodynamicEnsembleComponent;
import com.example.exampleplugin.physics.ThermodynamicsUtil;
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
    private final ComponentType<ChunkStore, ThermodynamicEnsembleComponent> thermodynamicEnsembleComponentType = ThermodynamicEnsembleComponent.getComponentType();
    private final Query<ChunkStore> query = Query.and(new Query[]{ThermodynamicEnsembleComponent.getComponentType()});

    public PipeFlowUpdateSystem() {
        super();
    }

    @Override
    public void tick(
            float dt,
            int index,
            @NonNull ArchetypeChunk<ChunkStore> chunk,
            @NonNull Store<ChunkStore> store,
            @NonNull CommandBuffer<ChunkStore> commandBuffer
    ) {
        BlockModule.BlockStateInfo stateInfo = chunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType());
        if (stateInfo == null) { return; }
        WorldChunk wc = commandBuffer.getComponent(stateInfo.getChunkRef(), WorldChunk.getComponentType());
        World world = wc.getWorld();

        int x = ChunkUtil.worldCoordFromLocalCoord(wc.getX(), ChunkUtil.xFromBlockInColumn(index));
        int y = ChunkUtil.yFromBlockInColumn(index);
        int z = ChunkUtil.worldCoordFromLocalCoord(wc.getZ(), ChunkUtil.zFromBlockInColumn(index));

        Vector3i originCoords = new Vector3i(x, y, z);

        commandBuffer.run((s) -> {
            ThermodynamicEnsembleComponent originBlockThermalComponent = store.getComponent(chunk.getReferenceTo(index), ThermodynamicEnsembleComponent.getComponentType());

            for (ConnectedPipeUtil.ConnectedPipe connectedPipe : getConnectedPipes(originCoords, chunk, commandBuffer, world)) {
                Vector3i neighborCoords = connectedPipe.position();
                if (!shouldProcessPair(originCoords, neighborCoords)) {
                    continue;
                }
                Ref<ChunkStore> connectedPipeRef = connectedPipe.ref();

                ThermodynamicEnsembleComponent neighborBlockThermalComponent = store.getComponent(connectedPipeRef, ThermodynamicEnsembleComponent.getComponentType());

                ThermodynamicsUtil.equilibrate(
                        dt,
                        originBlockThermalComponent,
                        neighborBlockThermalComponent
                );
            }
            ThermodynamicsUtil.equilibrate_with_environment(
                    dt,
                    originBlockThermalComponent,
                    300f
            );
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