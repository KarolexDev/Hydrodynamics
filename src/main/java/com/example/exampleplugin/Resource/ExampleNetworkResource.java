package com.example.exampleplugin.Resource;

import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetwork;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ExampleNetworkResource extends BlockNetwork<ExampleComponent> implements Resource<EntityStore> {

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return null;
    }

    @Override
    protected String fromConnectionMaskToBlockID(byte bits) {
        return null;
    }

    @Override
    protected void updateConnectionOnCoords(Vector3i currentBlockCoords, byte connectionMask,
                                            int index,
                                            @NonNull ArchetypeChunk<ChunkStore> chunk,
                                            @NonNull CommandBuffer<ChunkStore> commandBuffer
    ) {
        String state = ""; // TODO
        commandBuffer.run(
                (store) -> {
                    BlockModule.BlockStateInfo stateInfo = chunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType());
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


}
