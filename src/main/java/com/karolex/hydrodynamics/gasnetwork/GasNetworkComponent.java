package com.karolex.hydrodynamics.gasnetwork;

import com.karolex.hydrodynamics.HydrodynamicsPlugin;
import com.karolex.hydrodynamics.blocknetwork.BlockNetworkComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

public class GasNetworkComponent implements BlockNetworkComponent<GasNetworkComponent>, Component<ChunkStore> {

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return null;
    }

    @Override
    public GasNetworkComponent add(GasNetworkComponent flux) {
        return null;
    }

    @Override
    public GasNetworkComponent del(GasNetworkComponent flux) {
        return null;
    }

    @Override
    public GasNetworkComponent mergeComponents(GasNetworkComponent flux) {
        return null;
    }

    @Override
    public GasNetworkComponent calculateFlux(GasNetworkComponent from, GasNetworkComponent to) {
        return null;
    }

    @Override
    public GasNetworkComponent[] partition(int left_size, int right_size) {
        return new GasNetworkComponent[0];
    }

    @Override
    public GasNetworkComponent zero() {
        return null;
    }

    @Override
    public GasNetworkComponent copy() {
        return null;
    }

    @Override
    public boolean shouldMerge(GasNetworkComponent other) {
        return false;
    }

    @Override
    public void tick(float dt) {

    }

    @Override
    public @Nullable Duration computeDelay(float dt, GasNetworkComponent previous, boolean isCapped) {
        return null;
    }

    @Override
    public boolean isActive() {
        return false;
    }
}