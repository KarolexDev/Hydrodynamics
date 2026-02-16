package com.example.exampleplugin.network;

import java.util.List;
import java.util.Objects;

public class BlockPos implements Comparable<BlockPos> {

    public final int x, y, z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public List<BlockPos> getAdjacent() {
        return List.of(
                offset(1, 0, 0),
                offset(-1, 0, 0),
                offset(0, 1, 0),
                offset(0, -1, 0),
                offset(0, 0, 1),
                offset(0, 0, -1)
        );
    }

    public boolean isAdjacentTo(BlockPos other) {
        int dx = Math.abs(x - other.x);
        int dy = Math.abs(y - other.y);
        int dz = Math.abs(z - other.z);
        return (dx + dy + dz) == 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPos bp)) return false;
        return x == bp.x && y == bp.y && z == bp.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public int compareTo(BlockPos o) {
        if (x != o.x) return Integer.compare(x, o.x);
        if (y != o.y) return Integer.compare(y, o.y);
        return Integer.compare(z, o.z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
