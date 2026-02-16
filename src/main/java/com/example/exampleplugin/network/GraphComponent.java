package com.example.exampleplugin.network;

public class GraphComponent<T> {
    private BlockPos blockPos;
    private T component;

    public GraphComponent(BlockPos blockPos, T component) {
        this.blockPos = blockPos;
        this.component = component;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public T getComponent() {
        return component;
    }
}
