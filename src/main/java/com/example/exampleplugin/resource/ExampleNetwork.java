package com.example.exampleplugin.resource;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.network.BlockNetworkManager;
import com.example.exampleplugin.network.NetworkComponent;
import com.example.exampleplugin.network.NetworkUtil;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

public class ExampleNetwork extends BlockNetworkManager<ExampleNetwork.Component> implements Resource<EntityStore> {
    @Override
    public @Nullable Resource<EntityStore> clone() {
        return new ExampleNetwork();
    }

    public static ResourceType<EntityStore, ExampleNetwork> getResourceType() { return ExamplePlugin.getInstance().getExampleNetworkResourceType(); }

    // -------------------------------------------------------------------------
    // Component
    // -------------------------------------------------------------------------

    /**
     * Tracks the total energy capacity of a graph element, proportional to
     * its length in blocks.
     */
    public static class Component implements NetworkComponent {

        public final int capacity;

        public Component(int capacity) {
            this.capacity = capacity;
        }

        /** Returns a component representing {@code length} blocks. */
        @Override
        @SuppressWarnings("unchecked")
        public Component fromLength(int length) {
            return new Component(length * 100);   // 100 capacity per block
        }

        /** Combines two components (e.g. when merging edges/nodes). */
        @Override
        @SuppressWarnings("unchecked")
        public Component add(NetworkComponent other) {
            return new Component(this.capacity + ((Component) other).capacity);
        }

        /**
         * Removes {@code other}'s contribution from this component (e.g. when
         * a block is detached).
         */
        @Override
        @SuppressWarnings("unchecked")
        public Component del(NetworkComponent other) {
            return new Component(Math.max(0, this.capacity - ((Component) other).capacity));
        }

        /**
         * Splits this component into two parts proportional to {@code a} and
         * {@code b} (e.g. when splitting an edge).
         */
        @Override
        @SuppressWarnings("unchecked")
        public NetworkUtil.Pair<Component, Component> partition(int a, int b) {
            int total = a + b;
            int left  = (int) Math.round((double) capacity * a / total);
            int right = capacity - left;
            return new NetworkUtil.Pair<>(new Component(left), new Component(right));
        }

        @Override
        public String toString() {
            return "Component{capacity=" + capacity + "}";
        }
    }

    // -------------------------------------------------------------------------
    // Block-type registry
    // -------------------------------------------------------------------------

    // In a real plugin these sets would be replaced by world/block-registry queries.
    private static final java.util.Set<Vector3i> machinePositions = new java.util.HashSet<>();
    private static final java.util.Set<Vector3i> tankPositions    = new java.util.HashSet<>();

    /** Mark a position as a machine (always a graph node). */
    public void registerMachine(Vector3i pos) { machinePositions.add(pos); }

    /** Mark a position as a tank segment (extendable multi-block node). */
    public void registerTank(Vector3i pos)    { tankPositions.add(pos); }

    /** Remove any special designation for {@code pos}. */
    public void unregister(Vector3i pos) {
        machinePositions.remove(pos);
        tankPositions.remove(pos);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ExampleNetwork() {
        super(
                /* isAlwaysNode     */ pos -> false,   // replaced by instance method below
                /* isExtendableNode */ pos -> false    // replaced by instance method below
        );
    }

    /**
     * Use this factory method instead of the constructor so that the predicates
     * can reference {@code this} (the instance isn't available in a super() call).
     */
    public static ExampleNetwork create() {
        // We create the instance with dummy predicates, then return a properly
        // wired subclass via an anonymous class — or simply override areConnected
        // and rely on the fact that the predicates are stored as fields we can
        // replace after construction. The cleanest approach: use a thin wrapper.
        return new ExampleNetwork() {
            @Override
            protected boolean isAlwaysNodeImpl(Vector3i pos) {
                return machinePositions.contains(pos);
            }

            @Override
            protected boolean isExtendableNodeImpl(Vector3i pos) {
                return tankPositions.contains(pos);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Overridable node-type predicates
    // -------------------------------------------------------------------------

    /**
     * Override to determine whether {@code pos} must always be a graph node.
     * The default checks {@link #machinePositions}.
     */
    protected boolean isAlwaysNodeImpl(Vector3i pos) {
        return machinePositions.contains(pos);
    }

    /**
     * Override to determine whether {@code pos} is part of a multi-block node.
     * The default checks {@link #tankPositions}.
     */
    protected boolean isExtendableNodeImpl(Vector3i pos) {
        return tankPositions.contains(pos);
    }

    // -------------------------------------------------------------------------
    // Lifecycle hooks
    // -------------------------------------------------------------------------

    @Override
    protected void onNetworkCreated(NetworkState network) {
        System.out.println("Network created: id=" + network.getNetworkId()
                + " size=" + network.size());
    }

    @Override
    protected void onNetworkDestroyed(NetworkState network) {
        System.out.println("Network destroyed: id=" + network.getNetworkId());
    }

    @Override
    protected void onBlockAdded(NetworkState network) {
        System.out.println("Block added → network " + network.getNetworkId()
                + " now has " + network.size() + " blocks, "
                + network.getNodes().size() + " nodes, "
                + network.getEdges().size() + " edges");
    }

    @Override
    protected void onBlockRemoved(NetworkState network) {
        System.out.println("Block removed → network " + network.getNetworkId()
                + " now has " + network.size() + " blocks");
    }
}