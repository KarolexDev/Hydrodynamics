package com.example.exampleplugin.system;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.NonNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class ConnectedPipeUtil {

    public record ConnectedPipe(Vector3i position, Ref<ChunkStore> ref) { }

    private ConnectedPipeUtil() {
        // Utility class
    }

    public static List<ConnectedPipe> getConnectedPipes(
            Vector3i origin,
            @NonNull ArchetypeChunk<ChunkStore> chunk,
            @NonNull CommandBuffer<ChunkStore> commandBuffer,
            World world
    ) {
        List<ConnectedPipe> result = new ArrayList<>(6);

        for (Vector3i side : Vector3i.BLOCK_SIDES) {
            Vector3i neighborPos = origin.clone().add(side);

            Ref<ChunkStore> neighborRef = getBlockComponentEntityRef(world, neighborPos);

            if (neighborRef == null) {
                continue;   // ALWAYS TRUE?? WHY???
            }

            result.add(new ConnectedPipe(neighborPos, neighborRef));
        }

        return result;
    }

    public static @Nullable Ref<ChunkStore> getBlockComponentEntityRef(World world, Vector3i blockCoords) {
        WorldChunk blockChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(blockCoords.x, blockCoords.z));
        if (blockChunk == null) { return null; }
        return blockChunk.getBlockComponentEntity(blockCoords.x, blockCoords.y, blockCoords.z);
    }
}
