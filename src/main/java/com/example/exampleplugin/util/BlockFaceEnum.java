package com.example.exampleplugin.util;

public enum BlockFaceEnum {
    NONE((byte) 0b0),
    WEST((byte) 0b1), EAST((byte) 0b10),
    DOWN((byte) 0b100), UP((byte) 0b1000),
    NORTH((byte) 0b10000), SOUTH((byte) 0b100000);

    public final byte bits;

    BlockFaceEnum(byte bits) {
        this.bits = bits;
    }
}
