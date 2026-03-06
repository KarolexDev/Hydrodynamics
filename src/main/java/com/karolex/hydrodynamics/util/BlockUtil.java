package com.karolex.hydrodynamics.util;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.*;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;

import java.lang.reflect.Field;
import java.util.*;

public class BlockUtil {

    public static final byte NONE   = (byte) 0;
    public static final byte WEST   = (byte) 1;
    public static final byte EAST   = (byte) 2;
    public static final byte DOWN   = (byte) 4;
    public static final byte UP     = (byte) 8;
    public static final byte NORTH  = (byte) 16;
    public static final byte SOUTH  = (byte) 32;

    public static final byte[] FACE_BITS = {
            BlockUtil.WEST, BlockUtil.EAST,
            BlockUtil.DOWN, BlockUtil.UP,
            BlockUtil.NORTH, BlockUtil.SOUTH
    };

    public static final Vector3i[] FACE_OFFSETS = {
            new Vector3i(-1,  0,  0),  // WEST
            new Vector3i( 1,  0,  0),  // EAST
            new Vector3i( 0, -1,  0),  // DOWN
            new Vector3i( 0,  1,  0),  // UP
            new Vector3i( 0,  0, -1),  // NORTH
            new Vector3i( 0,  0,  1),  // SOUTH
    };

    private static final Field CONNECTED_BLOCK_SHAPES_FIELD;
    static {
        try {
            CONNECTED_BLOCK_SHAPES_FIELD = CustomConnectedBlockTemplateAsset.class
                    .getDeclaredField("connectedBlockShapes");
            CONNECTED_BLOCK_SHAPES_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    BlockUtil() { /* Utility Class */ }

    public static byte fromVector3i(Vector3i vec) {
        if (vec.x < 0) return WEST;
        if (vec.x > 0) return EAST;
        if (vec.y < 0) return DOWN;
        if (vec.y > 0) return UP;
        if (vec.z < 0) return NORTH;
        if (vec.z > 0) return SOUTH;
        return NONE;
    }

    public static byte opposite(byte face) {
        return switch (face) {
            case WEST  -> EAST;
            case EAST  -> WEST;
            case DOWN  -> UP;
            case UP    -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            default    -> NONE;
        };
    }

    public static byte readFromWorld(WorldChunk chunk, Vector3i pos) {
        BlockType blockType = chunk.getBlockType(pos);
        if (blockType == null) return BlockUtil.NONE;

        ConnectedBlockRuleSet rs = blockType.getConnectedBlockRuleSet();
        if (!(rs instanceof CustomTemplateConnectedBlockRuleSet ctrs))
            return BlockUtil.NONE;

        CustomConnectedBlockTemplateAsset tmpl = ctrs.getShapeTemplateAsset();
        if (tmpl == null) return BlockUtil.NONE;

        int blockTypeIndex = BlockType.getAssetMap().getIndex(blockType.getId());
        Set<String> shapeNames = ctrs.getShapesForBlockType(blockTypeIndex);
        if (shapeNames == null || shapeNames.isEmpty()) return BlockUtil.NONE;

        Map<String, ConnectedBlockShape> connectedBlockShapes;
        try {
            connectedBlockShapes = (Map<String, ConnectedBlockShape>)
                    CONNECTED_BLOCK_SHAPES_FIELD.get(tmpl);
        } catch (IllegalAccessException e) {
            return NONE;
        }

        String shapeName = shapeNames.iterator().next();
        ConnectedBlockShape shape = connectedBlockShapes.get(shapeName);
        if (shape == null || shape.getFaceTags() == null) return BlockUtil.NONE;

        @SuppressWarnings("removal")
        RotationTuple rotation = chunk.getRotation(pos.x, pos.y, pos.z);
        byte mask = 0;
        for (var entry : shape.getFaceTags().getBlockFaceTags().entrySet()) {
            Vector3i worldDir = Rotation.rotate(
                    entry.getKey().clone(),
                    rotation.yaw(),
                    rotation.pitch(),
                    rotation.roll()
            );
            byte f = BlockUtil.fromVector3i(worldDir);
            mask = (byte) (mask | f);
        }
        return mask;
    }

    public static List<Vector3i> getConnections(WorldChunk chunk, Vector3i pos) {
        byte mask = readFromWorld(chunk, pos);
        List<Vector3i> result = new ArrayList<>();
        for (int i = 0; i < FACE_BITS.length; i++) {
            if ((mask & FACE_BITS[i]) != 0) {
                result.add(new Vector3i(pos).add(FACE_OFFSETS[i]));
            }
        }
        return result;
    }

    public static Set<Vector3i> getOccupiedPositions(BlockType blockType, Vector3i origin, WorldChunk chunk) {
        if (blockType == null) return Set.of(origin);

        BlockBoundingBoxes hitbox = BlockBoundingBoxes.getAssetMap().getAsset(blockType.getHitboxTypeIndex());
        if (hitbox == null || !hitbox.protrudesUnitBox()) return Set.of(origin);

        @SuppressWarnings("removal")
        RotationTuple rotation = chunk.getRotation(origin.x, origin.y, origin.z);
        BlockBoundingBoxes.RotatedVariantBoxes variant = hitbox.get(rotation.yaw(), rotation.pitch(), rotation.roll());

        Set<Vector3i> result = new LinkedHashSet<>();
        FillerBlockUtil.forEachFillerBlock(variant, (x, y, z) ->
                result.add(new Vector3i(origin.x + x, origin.y + y, origin.z + z))
        );
        return result;
    }
}
