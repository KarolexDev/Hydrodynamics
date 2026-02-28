package com.example.exampleplugin.util;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

public class BlockFaceEnum {

    public static final byte NONE   = (byte) 0;
    public static final byte WEST   = (byte) 1;
    public static final byte EAST   = (byte) 2;
    public static final byte DOWN   = (byte) 4;
    public static final byte UP     = (byte) 8;
    public static final byte NORTH  = (byte) 16;
    public static final byte SOUTH  = (byte) 32;

    public static final byte[] FACE_BITS = {
            BlockFaceEnum.WEST, BlockFaceEnum.EAST,
            BlockFaceEnum.DOWN, BlockFaceEnum.UP,
            BlockFaceEnum.NORTH, BlockFaceEnum.SOUTH
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

    BlockFaceEnum() { /* Utility Class */ }

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
        if (blockType == null) return BlockFaceEnum.NONE;

        ConnectedBlockRuleSet rs = blockType.getConnectedBlockRuleSet();
        if (!(rs instanceof CustomTemplateConnectedBlockRuleSet ctrs))
            return BlockFaceEnum.NONE;

        CustomConnectedBlockTemplateAsset tmpl = ctrs.getShapeTemplateAsset();
        if (tmpl == null) return BlockFaceEnum.NONE;

        int blockTypeIndex = BlockType.getAssetMap().getIndex(blockType.getId());
        Set<String> shapeNames = ctrs.getShapesForBlockType(blockTypeIndex);
        if (shapeNames == null || shapeNames.isEmpty()) return BlockFaceEnum.NONE;

        Map<String, ConnectedBlockShape> connectedBlockShapes;
        try {
            connectedBlockShapes = (Map<String, ConnectedBlockShape>)
                    CONNECTED_BLOCK_SHAPES_FIELD.get(tmpl);
        } catch (IllegalAccessException e) {
            return NONE;
        }

        String shapeName = shapeNames.iterator().next();
        ConnectedBlockShape shape = connectedBlockShapes.get(shapeName);
        if (shape == null || shape.getFaceTags() == null) return BlockFaceEnum.NONE;

        RotationTuple rotation = chunk.getRotation(pos.x, pos.y, pos.z);
        byte mask = 0;
        for (var entry : shape.getFaceTags().getBlockFaceTags().entrySet()) {
            Vector3i worldDir = Rotation.rotate(
                    entry.getKey().clone(),
                    rotation.yaw(),
                    rotation.pitch(),
                    rotation.roll()
            );
            byte f = BlockFaceEnum.fromVector3i(worldDir);
            mask = (byte) (mask | f);
        }
        return mask;
    }
}
