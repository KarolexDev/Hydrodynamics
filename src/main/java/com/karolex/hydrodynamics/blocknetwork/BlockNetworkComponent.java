package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.time.Duration;

public interface BlockNetworkComponent<C extends BlockNetworkComponent<C>> {

    // Arithmetic Methods
    C add(float dt, C flux);
    C del(float dt, C flux);
    C mergeComponents(C flux);
    C calculateFlux(float dt, C from, C to);
    C[] partition(int left_size, int right_size);
    C zero();

    // Misc.
    default boolean requiresWorldUpdate() { return false; }
    default void onWorldUpdate(Vector3i pos, World world) {}

    C copy();

    boolean shouldMerge(C other);
    default boolean isPipe() { return false; };

    void tick(float dt);

    @Nullable
    Duration computeDelay(float dt, C previous, boolean isCapped);
    boolean isActive();
}
