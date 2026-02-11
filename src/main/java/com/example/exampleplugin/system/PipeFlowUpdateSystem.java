package com.example.exampleplugin.system;

import com.example.exampleplugin.component.FluidStorageComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFace;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class PipeFlowUpdateSystem extends EntityTickingSystem<ChunkStore> {
    
    private final ComponentType<ChunkStore, FluidStorageComponent> fluidStorageType;
    private static int count = 0;
    private static float _totalOriginTransfer = 0f;

    public PipeFlowUpdateSystem(ComponentType<ChunkStore, FluidStorageComponent> fluidStorageType) {
        super();
        this.fluidStorageType = fluidStorageType;
    }

    @Override
    public void tick(float dt, int index,
                     @NonNull ArchetypeChunk<ChunkStore> chunk,
                     @NonNull Store<ChunkStore> store,
                     @NonNull CommandBuffer<ChunkStore> commandBuffer) {

        ++count;
        if (!(count % 10 == 0)) { return; }

        BlockStateInfo stateInfo = chunk.getComponent(index, BlockStateInfo.getComponentType());
        if (stateInfo == null) { return; }
        WorldChunk wc = commandBuffer.getComponent(stateInfo.getChunkRef(), WorldChunk.getComponentType());

        int i = stateInfo.getIndex();
        int x = ChunkUtil.worldCoordFromLocalCoord(wc.getX(), ChunkUtil.xFromBlockInColumn(i));
        int y = ChunkUtil.yFromBlockInColumn(i);
        int z = ChunkUtil.worldCoordFromLocalCoord(wc.getZ(), ChunkUtil.zFromBlockInColumn(i));

        updateNeighborsAndOrigin(new Vector3i(x, y, z), wc.getWorld(), wc, dt, commandBuffer, stateInfo);
    }
    /** Logic for transfering fluid levels between neighboring blocks.
     * For now the method has been naively implemented and it iterates through every neighboring block
     * instead of connected ones.
     * TODO: Implement ConnectedBlocks somehow.
     * TODO: Do logic first, then push to commandBuffer.
     * */
    void updateNeighborsAndOrigin(Vector3i originBlockCoords,
                         @NonNull World world,
                         WorldChunk wc,
                         float dt,
                         CommandBuffer<ChunkStore> commandBuffer,
                         BlockStateInfo stateInfo
    ) {
        Holder<ChunkStore> originBlockComponentHolder = world.getBlockComponentHolder(originBlockCoords.x, originBlockCoords.y, originBlockCoords.z);
        assert originBlockComponentHolder != null;
        FluidStorageComponent originFluidStorageComponent = originBlockComponentHolder.getComponent(fluidStorageType);
        assert originFluidStorageComponent != null;

        float originFluidLevel = originFluidStorageComponent.getCurrentStorage();
        _totalOriginTransfer = 0f;
        
        for (Vector3i neighborBlockCoords : getBlockFaces(originBlockCoords)) {
            commandBuffer.run((store) -> {
                Holder<ChunkStore> neighborBlockComponentHolder = world.getBlockComponentHolder(neighborBlockCoords.x, neighborBlockCoords.y, neighborBlockCoords.z);
                if (neighborBlockComponentHolder == null) {
                    return;
                }
                FluidStorageComponent neighborFluidStorageComponent = neighborBlockComponentHolder.getComponent(fluidStorageType);
                if (neighborFluidStorageComponent == null) {
                    return;
                }

                float neighborFluidLevel = neighborFluidStorageComponent.getCurrentStorage();

                // Fluid Transfer Logic
                float difference = originFluidLevel - neighborFluidLevel;
                float transferRate = 1f;    // Sets min transfer rate
                float transferAmount = transferRate * difference * dt;
                _totalOriginTransfer += transferAmount;

                float newNeighborLevel = neighborFluidLevel + transferAmount;

                neighborFluidStorageComponent.setCurrentStorage(newNeighborLevel);
                if (wc.getBlockComponentEntity(neighborBlockCoords.x, neighborBlockCoords.y, neighborBlockCoords.z) == null) {
                    return;
                }
                commandBuffer.replaceComponent(wc.getBlockComponentEntity(neighborBlockCoords.x, neighborBlockCoords.y, neighborBlockCoords.z), fluidStorageType, new FluidStorageComponent(100f, newNeighborLevel));
                if (count % 10 == 0) { updateBlockState(neighborBlockCoords, "pipe" + Math.round(newNeighborLevel / 10), world, commandBuffer, stateInfo); }
            }
            );
        }
        float newOriginLevel = originFluidLevel - _totalOriginTransfer;
        originFluidStorageComponent.setCurrentStorage(newOriginLevel);
         if (wc.getBlockComponentEntity(originBlockCoords.x, originBlockCoords.y, originBlockCoords.z) == null) { return; }
         commandBuffer.replaceComponent(wc.getBlockComponentEntity(originBlockCoords.x, originBlockCoords.y, originBlockCoords.z), fluidStorageType, new FluidStorageComponent(100f, newOriginLevel));

        if (count % 10 == 0) { updateBlockState(originBlockCoords, "pipe" + Math.round(newOriginLevel / 10), world, commandBuffer, stateInfo); }
    }
    /** Helper function for getting the coordinates of the neighboring blocks. */
    // TODO: There exists better implementation (with ENUM)
    Vector3i[] getBlockFaces(Vector3i origin) {
        Vector3i[] result = new Vector3i[6];
        for(int i = 0; i < 6; i++) {
            result[i] = origin.clone().add(BlockFace.values()[i].getDirection());
        }
        return result;
    }
    /** Helper function for changing the block texture. */
    void updateBlockState(Vector3i currentBlockCoords, String state, World world, CommandBuffer<ChunkStore> command, BlockStateInfo stateInfo) {
        command.run(
            (store) -> {
                WorldChunk wc = store.getComponent(stateInfo.getChunkRef(), WorldChunk.getComponentType());
                wc.setBlockInteractionState(
                        currentBlockCoords.x,
                        currentBlockCoords.y,
                        currentBlockCoords.z,
                        wc.getBlockType(currentBlockCoords),
                        state,
                        true
                );
            }
        );
    }
    @Override
    public @Nullable Query<ChunkStore> getQuery() {
        return fluidStorageType;
    }
}
