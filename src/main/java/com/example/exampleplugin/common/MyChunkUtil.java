package com.example.exampleplugin.common;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;

public class MyChunkUtil {
    private MyChunkUtil() {}

    public static @Nullable Ref<ChunkStore> getBlockComponentEntityRef(World world, Vector3i blockCoords) {
        WorldChunk blockChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(blockCoords.x, blockCoords.z));
        if (blockChunk == null) { return null; }
        return blockChunk.getBlockComponentEntity(blockCoords.x, blockCoords.y, blockCoords.z);
    }

}
