package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.*;

import static com.example.exampleplugin.network.NetworkUtil.getAdjacent;

/**
 * Manages all {@link BlockNetwork} instances of a given type.
 *
 * <p>Call {@link #onBlockPlaced} / {@link #onBlockRemoved} whenever a
 * network-relevant block changes in the world. The manager automatically
 * creates, merges, and destroys networks, and delegates graph updates to
 * the network's incremental {@code addBlock} / {@code removeBlock} methods.
 *
 * <p>For network splits (which require full recalculation), call
 * {@link #recalculateNetworks()} explicitly — typically on world load or
 * after bulk removals.
 *
 * @param <T> the component type stored at each block
 * @param <N> the concrete BlockNetwork subclass
 */
public abstract class BlockNetworkManager<T, N extends BlockNetwork<T>> {

    private final Map<Vector3i, N> posToNetwork = new HashMap<>();
    private final Set<N> networks = new LinkedHashSet<>();

    // ----------------------------------------------------------------
    // Abstract hooks
    // ----------------------------------------------------------------

    /** Factory — create a fresh, empty network instance. */
    protected abstract N createEmptyNetwork();

    /**
     * Returns {@code true} if the block currently at {@code pos} should
     * participate in this type of network.
     */
    protected abstract boolean isNetworkBlock(Vector3i pos);

    /**
     * Returns the component object for the block at {@code pos}, or
     * {@code null} if none. Used to populate the network's component map.
     */
    protected abstract T getComponentAt(Vector3i pos);

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** The network that owns {@code pos}, or {@code null}. */
    public N getNetworkAt(Vector3i pos) {
        return posToNetwork.get(pos);
    }

    /** Read-only view of every active network. */
    public Set<N> getAllNetworks() {
        return Collections.unmodifiableSet(networks);
    }

    /**
     * Call when a network-relevant block is placed at {@code pos}.
     *
     * <p>Handles:
     * <ul>
     *   <li>Creating a brand-new single-block network</li>
     *   <li>Extending an existing network (delegated to {@code addBlock})</li>
     *   <li>Merging two or more networks that the new block bridges</li>
     * </ul>
     *
     * @return the network the block was added to, or {@code null} if the
     *     block is not a network block
     */
    public N onBlockPlaced(Vector3i pos) {
        if (!isNetworkBlock(pos)) return null;

        // Collect distinct neighboring networks (use networkId as key to dedupe)
        Map<Integer, N> neighborNets = new LinkedHashMap<>();
        for (Vector3i adj : getAdjacent(pos)) {
            N net = posToNetwork.get(adj);
            if (net != null) {
                neighborNets.putIfAbsent(net.getNetworkId(), net);
            }
        }

        N target;

        if (neighborNets.isEmpty()) {
            // Isolated block — create a brand-new single-block network
            target = createEmptyNetwork();
            networks.add(target);

            T component = getComponentAt(pos);
            target.addComponent(pos, component);
            target.addBlock(pos, component);
            posToNetwork.put(pos, target);

        } else if (neighborNets.size() == 1) {
            // Extends a single existing network — use incremental addBlock
            target = neighborNets.values().iterator().next();

            T component = getComponentAt(pos);
            target.addBlock(pos, component);
            posToNetwork.put(pos, target);

        } else {
            // Bridges multiple networks — merge them
            Iterator<N> it = neighborNets.values().iterator();
            target = it.next();

            // Merge all other networks into the target
            while (it.hasNext()) {
                N other = it.next();

                // Transfer all positions and components from 'other' to 'target'
                Map<Vector3i, T> otherComponents = new HashMap<>();
                for (Vector3i p : other.getPositions()) {
                    otherComponents.put(p, getComponentAt(p));
                    posToNetwork.put(p, target);
                }
                target.addAllComponents(otherComponents);

                networks.remove(other);
                onNetworkDestroyed(other);
            }

            // Now add the new block to the merged target network
            T component = getComponentAt(pos);
            target.addBlock(pos, component);
            posToNetwork.put(pos, target);
        }

        return target;
    }

    /**
     * Call when the block at {@code pos} is removed from the world.
     *
     * <p>Handles:
     * <ul>
     *   <li>Removing the block from its network (delegated to {@code removeBlock})</li>
     *   <li>Destroying an empty network</li>
     * </ul>
     *
     * <p><b>Note:</b> This method does <i>not</i> check for network splits,
     * as that requires a full graph traversal. Call {@link #recalculateNetworks()}
     * explicitly when needed (e.g. on world load or after bulk removals).
     */
    public void onBlockRemoved(Vector3i pos) {
        N network = posToNetwork.remove(pos);
        if (network == null) return;

        network.removeBlock(pos);

        if (network.size() == 0) {
            networks.remove(network);
            onNetworkDestroyed(network);
        }
    }

    /**
     * Recalculates all networks from scratch by scanning {@code posToNetwork}.
     *
     * <p>This method is expensive (full graph traversal) and should only be
     * called when necessary, such as:
     * <ul>
     *   <li>On world/chunk load</li>
     *   <li>After detecting a potential network split (e.g. via
     *       {@link BlockNetwork#getConnectedComponents})</li>
     *   <li>After bulk block removals where incremental updates are impractical</li>
     * </ul>
     *
     * <p>Splits any disconnected networks into separate components, destroys
     * empty networks, and rebuilds the internal graph for each.
     */
    public void recalculateNetworks() {
        // Collect all positions currently tracked
        Set<Vector3i> allPositions = new HashSet<>(posToNetwork.keySet());

        // Clear current state
        networks.clear();
        posToNetwork.clear();

        // Flood-fill to find connected components
        Set<Vector3i> visited = new HashSet<>();
        for (Vector3i start : allPositions) {
            if (visited.contains(start)) continue;
            if (!isNetworkBlock(start)) continue;

            // BFS to find this component
            Set<Vector3i> component = new HashSet<>();
            Queue<Vector3i> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            component.add(start);

            while (!queue.isEmpty()) {
                Vector3i pos = queue.poll();
                for (Vector3i adj : getAdjacent(pos)) {
                    if (visited.contains(adj)) continue;
                    if (!isNetworkBlock(adj)) continue;
                    if (!allPositions.contains(adj)) continue;

                    visited.add(adj);
                    component.add(adj);
                    queue.add(adj);
                }
            }

            // Create a new network for this component
            N network = createEmptyNetwork();
            Map<Vector3i, T> components = new HashMap<>();
            for (Vector3i p : component) {
                T comp = getComponentAt(p);
                components.put(p, comp);
                posToNetwork.put(p, network);
            }
            network.addAllComponents(components);
            network.rebuildGraph();
            networks.add(network);
            onNetworkCreated(network);
        }
    }

    // ----------------------------------------------------------------
    // Optional lifecycle callbacks
    // ----------------------------------------------------------------

    /** Called after a new network is created (via splitting or recalculation). */
    protected void onNetworkCreated(N network) {}

    /** Called before a network is discarded (empty or merged away). */
    protected void onNetworkDestroyed(N network) {}
}