package com.example.exampleplugin.util;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import java.util.ArrayList;
import java.util.List;

public class MultiBlockUtil {
    private MultiBlockUtil() { /* Utility Class */ }
    public static List<Vector3i> getOccupiedPositions(BlockType blockType, Vector3i origin) {
        BlockBoundingBoxes hitboxAsset = (BlockBoundingBoxes) BlockBoundingBoxes
                .getAssetMap()
                .getAsset(blockType.getHitboxTypeIndex());

        if (hitboxAsset == null) return List.of(origin);

        int rotationIndex = 0; // TODO: Rotation
        BlockBoundingBoxes.RotatedVariantBoxes rotatedBoxes = hitboxAsset.get(rotationIndex);
        Box boundingBox = rotatedBoxes.getBoundingBox();

        Vector3d min = boundingBox.getMin();
        Vector3d max = boundingBox.getMax();

        int minX = (int) Math.floor(min.getX());
        int minY = (int) Math.floor(min.getY());
        int minZ = (int) Math.floor(min.getZ());
        int maxX = (int) Math.ceil(max.getX());
        int maxY = (int) Math.ceil(max.getY());
        int maxZ = (int) Math.ceil(max.getZ());

        List<Vector3i> positions = new ArrayList<>();
        for (int dx = minX; dx < maxX; dx++)
            for (int dy = minY; dy < maxY; dy++)
                for (int dz = minZ; dz < maxZ; dz++)
                    positions.add(new Vector3i(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz));

        return positions;
    }
}
