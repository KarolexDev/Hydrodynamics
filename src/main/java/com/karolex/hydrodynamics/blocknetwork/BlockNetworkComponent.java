package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

public interface BlockNetworkComponent<C extends BlockNetworkComponent<C>> {

    // Arithmetic Methods
    C add(C flux);
    C del(C flux);
    C mergeComponents(C flux);
    C calculateFlux(float dt, C from, C to);
    C[] partition(int left_size, int right_size);
    C zero();

    // Misc.
    boolean requiresWorldUpdate();
    void onWorldUpdate(Vector3i pos, World world);
    C copy();
    float changeRate(C previous);
    float computeDelay(float changeRate);
    boolean shouldMerge(C other);
    default boolean isPipe() { return false; };
}
