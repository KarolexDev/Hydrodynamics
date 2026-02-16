package com.example.exampleplugin.network;

import java.util.*;

/**
 * Manages all {@link Network} instances of a given type.
 *
 * Call {@link #onBlockPlaced} / {@link #onBlockRemoved} whenever a
 * network-relevant block changes in the world. The manager
 * automatically creates, merges, splits, and destroys networks and
 * keeps the internal graph structures up-to-date.
 *
 * @param <N> the concrete Network subclass
 */
public abstract class NetworkManager<N extends Network> {

    private final Map<BlockPos, N> posToNetwork = new HashMap<>();
    private final Set<N> networks = new LinkedHashSet<>();

    // ----------------------------------------------------------------
    // Abstract hooks
    // ----------------------------------------------------------------

    /** Factory – create a fresh, empty network instance. */
    protected abstract N createEmptyNetwork();

    /**
     * Return {@code true} if the block currently at {@code pos}
     * should participate in this type of network.
     */
    protected abstract boolean isNetworkBlock(BlockPos pos);

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** The network that owns {@code pos}, or {@code null}. */
    public N getNetworkAt(BlockPos pos) {
        return posToNetwork.get(pos);
    }

    /** Read-only view of every active network. */
    public Set<N> getAllNetworks() {
        return Collections.unmodifiableSet(networks);
    }

    /**
     * Call when a network-relevant block is placed at {@code pos}.
     *
     * Handles:
     * <ul>
     *   <li>Creating a brand-new single-block network</li>
     *   <li>Extending an existing network</li>
     *   <li>Merging two or more networks that the new block bridges
     *   </li>
     * </ul>
     *
     * @return the network the block was added to
     */
    public N onBlockPlaced(BlockPos pos) {
        if (!isNetworkBlock(pos)) return null;

        // Collect distinct neighboring networks
        Map<Integer, N> neighborNets = new LinkedHashMap<>();
        for (BlockPos adj : pos.getAdjacent()) {
            N net = posToNetwork.get(adj);
            if (net != null) {
                neighborNets.putIfAbsent(net.getNetworkId(), net);
            }
        }

        N target;

        if (neighborNets.isEmpty()) {
            // Isolated – brand-new network
            target = createEmptyNetwork();
            networks.add(target);
        } else {
            // Pick the first as the merge target
            Iterator<N> it = neighborNets.values().iterator();
            target = it.next();

            // Merge every other network into the target
            while (it.hasNext()) {
                N other = it.next();
                for (BlockPos p : new ArrayList<>(other.getPositions())) {
                    target.addPosition(p);
                    posToNetwork.put(p, target);
                }
                networks.remove(other);
                onNetworkDestroyed(other);
            }
        }

        target.addPosition(pos);
        posToNetwork.put(pos, target);
        target.rebuildGraph();
        return target;
    }

    /**
     * Call when the block at {@code pos} is removed from the world.
     *
     * Handles:
     * <ul>
     *   <li>Removing the block from its network</li>
     *   <li>Destroying an empty network</li>
     *   <li>Splitting a network into separate components</li>
     * </ul>
     */
    public void onBlockRemoved(BlockPos pos) {
        N network = posToNetwork.remove(pos);
        if (network == null) return;

        network.removePosition(pos);

        if (network.size() == 0) {
            networks.remove(network);
            onNetworkDestroyed(network);
            return;
        }

        // Check whether the removal split the network
        List<Set<BlockPos>> components =
                network.getConnectedComponents();

        if (components.size() == 1) {
            // Still one piece – just rebuild the graph
            network.rebuildGraph();
        } else {
            // Split into separate networks
            networks.remove(network);
            onNetworkDestroyed(network);

            for (Set<BlockPos> comp : components) {
                N fresh = createEmptyNetwork();
                fresh.addAllPositions(comp);
                for (BlockPos p : comp) {
                    posToNetwork.put(p, fresh);
                }
                fresh.rebuildGraph();
                networks.add(fresh);
                onNetworkCreated(fresh);
            }
        }
    }

    // ----------------------------------------------------------------
    // Optional lifecycle callbacks
    // ----------------------------------------------------------------

    /** Called after a new network is created via splitting. */
    protected void onNetworkCreated(N network) {}

    /** Called before a network is discarded (empty or merged away). */
    protected void onNetworkDestroyed(N network) {}
}