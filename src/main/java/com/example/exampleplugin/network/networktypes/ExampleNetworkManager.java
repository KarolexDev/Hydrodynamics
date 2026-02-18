package com.example.exampleplugin.network.networktypes;

import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.network.BlockNetworkManager;
import com.hypixel.hytale.math.vector.Vector3i;

/**
 * Example network manager — demonstrates the single-class API.
 *
 * <p>Pass the two predicates to the constructor. Override {@code on*} hooks
 * for lifecycle callbacks.
 */
public class ExampleNetworkManager extends BlockNetworkManager<ExampleComponent> {

    public ExampleNetworkManager() {
        super(
            /* isAlwaysNode     */ pos -> false,
            /* isExtendableNode */ pos -> false
        );
    }

    // Example overrides:
    //
    // @Override
    // protected void onNetworkCreated(NetworkState network) { ... }
    //
    // @Override
    // protected void onBlockAdded(NetworkState network) { ... }
    //
    // @Override
    // protected boolean areConnected(Vector3i a, Vector3i b) {
    //     return super.areConnected(a, b) && someExtraCondition(a, b);
    // }
}
