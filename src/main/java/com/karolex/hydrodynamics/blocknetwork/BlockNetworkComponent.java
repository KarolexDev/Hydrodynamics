package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface BlockNetworkComponent<C extends BlockNetworkComponent<C>> {

    // Arithmetic Methods
    C add(C flux);
    C del(C flux);
    C mergeComponents(C flux);
    C calculateFlux(C from, C to, String fromType, String toType);
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

    // To be implemented in default position (as for example in the blockbench editor). Return null for default.
    Map<Vector3i, String> getConnectionPoints();

    <C extends BlockNetworkComponent<C>> List<C> divideFluxFlow(List<Map.Entry<String,C>> incomingFlux, List<Map.Entry<String,C>> outgoingFlux);

    <C extends BlockNetworkComponent<C>> Integer computeDelay(List<Map.Entry<String,C>> incomingFlux, List<Map.Entry<String,C>> outgoingFlux);
}
