package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NetworkUtil {
    private NetworkUtil() {}

    public static final Vector3i EAST = new Vector3i(1, 0, 0);
    public static final Vector3i WEST = new Vector3i(-1, 0, 0);
    public static final Vector3i UP = new Vector3i(0, 1, 0);
    public static final Vector3i DOWN = new Vector3i(0, -1, 0);
    public static final Vector3i SOUTH = new Vector3i(0, 0, 1);
    public static final Vector3i NORTH = new Vector3i(0, 0, -1);

    public static final List<Vector3i> DIRECTIONS = List.of(
            EAST, WEST, UP, DOWN, SOUTH, NORTH
    );

    public enum Direction {
        UP(0,1,0), DOWN(0,-1,0),
        NORTH(0,0,-1), SOUTH(0,0,1),
        WEST(-1,0,0), EAST(1,0,0);

        public final int dx, dy, dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx; this.dy = dy; this.dz = dz;
        }

        public Vector3i toVec() {
            return new Vector3i(this.dx, this.dy, this.dz);
        }
    }

    public static Set<Vector3i> getAdjacent(Vector3i pos) {
        Set<Vector3i> result = new HashSet<>();
        for (Vector3i direction : DIRECTIONS) {
            result.add(pos.add(direction));
        }
        return result;
    }

    public static boolean areAdjacent(Vector3i a, Vector3i b) {
        int dx = Math.abs(a.x - b.x);
        int dy = Math.abs(a.y - b.y);
        int dz = Math.abs(a.z - b.z);
        return (dx + dy + dz) == 1;
    }
}
