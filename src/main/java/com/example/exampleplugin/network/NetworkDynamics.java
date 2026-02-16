package com.example.exampleplugin.network;

import com.example.exampleplugin.network.BlockNetwork;
import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages physics simulation for a {@link BlockNetwork}.
 *
 * <p>This class handles:
 * <ul>
 *   <li>State storage for all nodes and edges</li>
 *   <li>Update scheduling and propagation</li>
 *   <li>Flux calculation and application</li>
 *   <li>Thread-safe access (optional, enabled via constructor)</li>
 * </ul>
 *
 * <p><b>Thread-Safety:</b> If multi-threaded mode is enabled, the dynamics system
 * works on snapshots of the network topology and coordinates with the main thread
 * via read-write locks. Single-threaded mode has no synchronization overhead.
 *
 * @param <T> the component type from BlockNetwork
 * @param <S> the ComponentState implementation
 * @param <F> the FluxVector implementation
 */
public class NetworkDynamics<T, S extends ComponentState, F extends FluxVector> {

    private final BlockNetwork<T> network;
    private final DynamicsHandler<S, F> handler;
    private final boolean multiThreaded;
    private final ReadWriteLock lock;

    // -------------------------------------------------------------------------
    // State Storage
    // -------------------------------------------------------------------------

    /** Current state for each network component (thread-safe if multiThreaded). */
    private final Map<Vector3i, S> stateMap;

    /** Edge constraints for each edge (keyed by a representative intermediate block position). */
    private final Map<Vector3i, EdgeConstraints> edgeConstraints;

    /** Previous flux values for convergence detection. */
    private final Map<Vector3i, Map<Vector3i, F>> previousFluxes;

    // -------------------------------------------------------------------------
    // Update Scheduling
    // -------------------------------------------------------------------------

    /** Priority queue of scheduled updates, ordered by tick then priority. */
    private final PriorityQueue<UpdateTrigger> scheduledUpdates;

    /** Map of pending updates for deduplication (position -> trigger). */
    private final Map<Vector3i, UpdateTrigger> pendingUpdates;

    /** Current tick counter. */
    private int currentTick;

    /** Timestamp of last tick, for delta-time calculation. */
    private long lastTickNanos;

    /** Flag indicating topology changed and snapshot needs rebuild. */
    private volatile boolean topologyDirty;

    // -------------------------------------------------------------------------
    // Topology Snapshot (for thread-safe reads)
    // -------------------------------------------------------------------------

    private TopologySnapshot cachedSnapshot;

    private static class TopologySnapshot {
        final Map<Vector3i, Set<Vector3i>> nodeNeighbors;
        final Map<Vector3i, EdgeInfo> edgeInfos;

        TopologySnapshot(Map<Vector3i, Set<Vector3i>> nodeNeighbors,
                         Map<Vector3i, EdgeInfo> edgeInfos) {
            this.nodeNeighbors = Collections.unmodifiableMap(nodeNeighbors);
            this.edgeInfos = Collections.unmodifiableMap(edgeInfos);
        }
    }

    private static class EdgeInfo {
        final Vector3i start;
        final Vector3i end;
        final double length;

        EdgeInfo(Vector3i start, Vector3i end, double length) {
            this.start = start;
            this.end = end;
            this.length = length;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new dynamics system for the given network.
     *
     * @param network the BlockNetwork this dynamics system manages
     * @param handler the physics handler for calculations
     * @param multiThreaded if {@code true}, enables thread-safe operations
     */
    public NetworkDynamics(BlockNetwork<T> network, DynamicsHandler<S, F> handler,
                           boolean multiThreaded) {
        this.network = network;
        this.handler = handler;
        this.multiThreaded = multiThreaded;
        this.lock = multiThreaded ? new ReentrantReadWriteLock() : null;

        this.stateMap = multiThreaded 
            ? new ConcurrentHashMap<>() 
            : new HashMap<>();
        this.edgeConstraints = multiThreaded 
            ? new ConcurrentHashMap<>() 
            : new HashMap<>();
        this.previousFluxes = multiThreaded 
            ? new ConcurrentHashMap<>() 
            : new HashMap<>();

        this.scheduledUpdates = new PriorityQueue<>();
        this.pendingUpdates = new HashMap<>();

        this.currentTick = 0;
        this.lastTickNanos = System.nanoTime();
        this.topologyDirty = true;
    }

    // -------------------------------------------------------------------------
    // State Access
    // -------------------------------------------------------------------------

    /**
     * Sets the state for a component (Node or Edge intermediate block).
     * If the component should trigger updates, schedules an initial update.
     */
    public void setState(Vector3i pos, S state) {
        acquireWriteLock();
        try {
            stateMap.put(pos, state);
            if (state.triggersUpdateUponPlacement()) {
                scheduleUpdate(pos, null, currentTick + 1, 1);
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Gets the current state for a component.
     * Thread-safe for reading even during updates (if multiThreaded enabled).
     */
    public @Nullable S getState(Vector3i pos) {
        acquireReadLock();
        try {
            return stateMap.get(pos);
        } finally {
            releaseReadLock();
        }
    }

    /**
     * Sets edge constraints for an edge (represented by any of its intermediate blocks).
     */
    public void setEdgeConstraints(Vector3i edgePos, EdgeConstraints constraints) {
        acquireWriteLock();
        try {
            edgeConstraints.put(edgePos, constraints);
        } finally {
            releaseWriteLock();
        }
    }

    // -------------------------------------------------------------------------
    // Topology Management
    // -------------------------------------------------------------------------

    /**
     * Marks the topology as changed (e.g., after block placement/removal).
     * The next tick will rebuild the topology snapshot.
     */
    public void markTopologyDirty() {
        topologyDirty = true;
    }

    /**
     * Rebuilds the topology snapshot from the current BlockNetwork state.
     * Called automatically when topology is marked dirty.
     */
    private void rebuildTopologySnapshot() {
        acquireReadLock(); // Read-lock on the BlockNetwork
        try {
            Map<Vector3i, Set<Vector3i>> nodeNeighbors = new HashMap<>();
            Map<Vector3i, EdgeInfo> edgeInfos = new HashMap<>();

            // Extract node neighbors
            for (BlockNetwork<T>.Node node : network.getNodes()) {
                for (Vector3i nodePos : node.getBlockPositions()) {
                    Set<Vector3i> neighbors = node.getConnectedBlockPositions();
                    nodeNeighbors.put(nodePos, new HashSet<>(neighbors));
                }
            }

            // Extract edge info (length, endpoints)
            for (BlockNetwork<T>.Edge edge : network.getEdges()) {
                double length = edge.getLength();
                Vector3i start = edge.getStart().getBlockPositions().iterator().next();
                Vector3i end = edge.getEnd().getBlockPositions().iterator().next();

                // Store edge info for each intermediate block
                for (Vector3i intermediatePos : edge.getIntermediateBlocks()) {
                    edgeInfos.put(intermediatePos, new EdgeInfo(start, end, length));
                }
            }

            cachedSnapshot = new TopologySnapshot(nodeNeighbors, edgeInfos);
            topologyDirty = false;

        } finally {
            releaseReadLock();
        }
    }

    // -------------------------------------------------------------------------
    // Main Tick Loop
    // -------------------------------------------------------------------------

    /**
     * Advances the simulation by one tick.
     *
     * <p>Processes all updates scheduled for the current tick, calculates
     * fluxes, updates states, and schedules new updates for neighbors if needed.
     *
     * <p>Delta-time is calculated automatically from the time elapsed since
     * the last tick to ensure physics-accurate simulation regardless of frame rate.
     */
    public void tick() {
        long currentNanos = System.nanoTime();
        double dt = (currentNanos - lastTickNanos) / 1_000_000_000.0; // seconds
        lastTickNanos = currentNanos;

        acquireWriteLock();
        try {
            if (topologyDirty) {
                rebuildTopologySnapshot();
            }

            currentTick++;

            // Process all updates scheduled for this tick
            while (!scheduledUpdates.isEmpty() 
                   && scheduledUpdates.peek().scheduledTick <= currentTick) {
                
                UpdateTrigger trigger = scheduledUpdates.poll();
                pendingUpdates.remove(trigger.position);

                processUpdate(trigger, dt);
            }

        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Processes a single update trigger.
     */
    private void processUpdate(UpdateTrigger trigger, double dt) {
        Vector3i pos = trigger.position;
        S state = stateMap.get(pos);
        if (state == null) return; // Component removed

        // Determine if this is a node or edge
        if (cachedSnapshot.nodeNeighbors.containsKey(pos)) {
            updateNode(pos, state, trigger.source, dt);
        } else if (cachedSnapshot.edgeInfos.containsKey(pos)) {
            updateEdge(pos, state, dt);
        }
    }

    /**
     * Updates a node's state and schedules neighbor updates if needed.
     */
    private void updateNode(Vector3i pos, S state, @Nullable Vector3i source, double dt) {
        Set<Vector3i> neighbors = cachedSnapshot.nodeNeighbors.get(pos);
        if (neighbors == null || neighbors.isEmpty()) return;

        // Calculate fluxes to all neighbors
        Map<Vector3i, F> netFluxes = new HashMap<>();
        for (Vector3i neighbor : neighbors) {
            S neighborState = stateMap.get(neighbor);
            if (neighborState == null) continue;

            // Calculate ideal flux
            EdgeInfo edgeInfo = cachedSnapshot.edgeInfos.get(neighbor);
            double edgeLength = edgeInfo != null ? edgeInfo.length : 1.0;
            F flux = handler.calculateIdealFlux(state, neighborState, edgeLength);

            // Apply edge constraints if present
            EdgeConstraints constraints = edgeConstraints.get(neighbor);
            if (constraints != null && !constraints.isIdeal()) {
                flux = (F) constraints.applyLimits(flux);
            }

            netFluxes.put(neighbor, flux);
        }

        // Check if in equilibrium
        if (handler.isInEquilibrium(state, netFluxes)) {
            return; // No update needed
        }

        // Update state
        S newState = handler.updateNodeState(state, netFluxes, dt);
        stateMap.put(pos, newState);

        // Schedule neighbor updates based on flux changes
        Map<Vector3i, F> oldFluxes = previousFluxes.getOrDefault(pos, Collections.emptyMap());
        for (Map.Entry<Vector3i, F> entry : netFluxes.entrySet()) {
            Vector3i neighbor = entry.getKey();
            F newFlux = entry.getValue();
            F oldFlux = oldFluxes.get(neighbor);

            double fluxChange = oldFlux != null 
                ? Math.abs(newFlux.getMagnitude() - oldFlux.getMagnitude())
                : newFlux.getMagnitude();

            if (fluxChange > 1e-6) { // Threshold for significance
                S neighborState = stateMap.get(neighbor);
                int delay = handler.calculatePropagationDelay(fluxChange, neighborState);
                int priority = handler.calculateUpdatePriority(neighborState, netFluxes);

                if (delay != Integer.MAX_VALUE) {
                    scheduleUpdate(neighbor, pos, currentTick + delay, priority);
                }
            }
        }

        // Store fluxes for next update
        previousFluxes.put(pos, new HashMap<>(netFluxes));
    }

    /**
     * Updates an edge's state based on flow from both ends.
     */
    private void updateEdge(Vector3i pos, S state, double dt) {
        EdgeInfo info = cachedSnapshot.edgeInfos.get(pos);
        if (info == null) return;

        S startState = stateMap.get(info.start);
        S endState = stateMap.get(info.end);
        if (startState == null || endState == null) return;

        // Calculate fluxes at both ends
        F fluxFromStart = handler.calculateIdealFlux(startState, state, info.length / 2);
        F fluxToEnd = handler.calculateIdealFlux(state, endState, info.length / 2);

        // Apply edge constraints
        EdgeConstraints constraints = edgeConstraints.get(pos);
        if (constraints != null && !constraints.isIdeal()) {
            fluxFromStart = (F) constraints.applyLimits(fluxFromStart);
            fluxToEnd = (F) constraints.applyLimits(fluxToEnd);
        }

        // Update edge state
        S newState = handler.updateEdgeState(state, fluxFromStart, fluxToEnd, info.length, dt);
        stateMap.put(pos, newState);

        // Schedule neighbor updates if flux changed significantly
        double totalFluxChange = fluxFromStart.getMagnitude() + fluxToEnd.getMagnitude();
        if (totalFluxChange > 1e-6) {
            int delay = handler.calculatePropagationDelay(totalFluxChange, state);
            if (delay != Integer.MAX_VALUE) {
                int priority = handler.calculateUpdatePriority(state, Map.of(
                    info.start, fluxFromStart,
                    info.end, fluxToEnd
                ));
                scheduleUpdate(info.start, pos, currentTick + delay, priority);
                scheduleUpdate(info.end, pos, currentTick + delay, priority);
            }
        }
    }

    /**
     * Schedules an update for a component.
     * If an update is already pending for the same position, keeps the earlier one.
     */
    private void scheduleUpdate(Vector3i pos, @Nullable Vector3i source, 
                                int scheduledTick, int priority) {
        UpdateTrigger existing = pendingUpdates.get(pos);

        if (existing != null && scheduledTick < existing.scheduledTick) {
            // New update is earlier — replace
            scheduledUpdates.remove(existing);
            UpdateTrigger newTrigger = new UpdateTrigger(pos, source, scheduledTick, priority);
            scheduledUpdates.add(newTrigger);
            pendingUpdates.put(pos, newTrigger);
        } else if (existing == null) {
            // No pending update — add new one
            UpdateTrigger trigger = new UpdateTrigger(pos, source, scheduledTick, priority);
            scheduledUpdates.add(trigger);
            pendingUpdates.put(pos, trigger);
        }
        // Otherwise: existing update is earlier or equal, do nothing
    }

    // -------------------------------------------------------------------------
    // Initial Update Triggers
    // -------------------------------------------------------------------------

    /**
     * Triggers initial updates for all components that request it.
     * Call this after network initialization or world load.
     */
    public void triggerInitialUpdates() {
        acquireWriteLock();
        try {
            for (Map.Entry<Vector3i, S> entry : stateMap.entrySet()) {
                if (entry.getValue().triggersUpdateUponStart()) {
                    scheduleUpdate(entry.getKey(), null, currentTick + 1, 1);
                }
            }
        } finally {
            releaseWriteLock();
        }
    }

    // -------------------------------------------------------------------------
    // Thread Synchronization Helpers
    // -------------------------------------------------------------------------

    private void acquireReadLock() {
        if (multiThreaded) lock.readLock().lock();
    }

    private void releaseReadLock() {
        if (multiThreaded) lock.readLock().unlock();
    }

    private void acquireWriteLock() {
        if (multiThreaded) lock.writeLock().lock();
    }

    private void releaseWriteLock() {
        if (multiThreaded) lock.writeLock().unlock();
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    public int getCurrentTick() {
        return currentTick;
    }

    public int getPendingUpdateCount() {
        acquireReadLock();
        try {
            return pendingUpdates.size();
        } finally {
            releaseReadLock();
        }
    }
}
