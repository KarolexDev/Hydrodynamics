package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.exampleplugin.network.NetworkUtil.areAdjacent;
import static com.example.exampleplugin.network.NetworkUtil.getAdjacent;

/**
 * Abstract graph-based network over a set of block positions.
 *
 * <p>The network maintains a compressed graph representation: blocks that would
 * be simple pass-through segments are stored as edge intermediate blocks rather
 * than nodes, keeping the node/edge count low regardless of cable length.
 *
 * <p>Subclasses must implement {@link #isAlwaysNode} and {@link #isExtendableNode},
 * and may override the hook methods to react to structural changes.
 *
 * <p><b>Coordinate system:</b> every position is a {@link Vector3i} in world space.
 * Multi-block nodes (e.g. tanks) are supported: a single {@link Node} object may
 * cover several adjacent positions.
 *
 * @param <T> the component type stored at each block position
 */
public abstract class BlockNetwork<T> {

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /**
     * A node in the network graph.
     *
     * <p>A node is created for any block that satisfies {@link #isAlwaysNode},
     * or whose connection count is not exactly 2 (i.e. endpoints and junctions).
     * Multi-block nodes (e.g. tanks) share a single Node instance.
     */
    public class Node {

        private final Set<Vector3i> blockPositions = new LinkedHashSet<>();
        private final Set<Edge> connectedEdges = new LinkedHashSet<>();

        Node(Set<Vector3i> blockPositions) {
            this.blockPositions.addAll(blockPositions);
        }

        public Set<Vector3i> getBlockPositions() {
            return Collections.unmodifiableSet(blockPositions);
        }

        public Set<Edge> getConnectedEdges() {
            return Collections.unmodifiableSet(connectedEdges);
        }

        /**
         * Returns the set of block positions immediately adjacent to this node
         * across all connected edges — i.e. the first intermediate block of each
         * edge (or the opposite node's position for a direct link).
         */
        public Set<Vector3i> getConnectedBlockPositions() {
            Set<Vector3i> result = new HashSet<>();
            for (Edge edge : connectedEdges) {
                if (this == edge.start) {
                    // Direct link: no intermediate blocks; the adjacent position is
                    // the opposite node's startPos/endPos recorded on the edge.
                    if (edge.intermediateBlocks.isEmpty()) {
                        result.add(edge.endPos);
                    } else {
                        result.add(edge.intermediateBlocks.getFirst());
                    }
                } else if (this == edge.end) {
                    if (edge.intermediateBlocks.isEmpty()) {
                        result.add(edge.startPos);
                    } else {
                        result.add(edge.intermediateBlocks.getLast());
                    }
                } else {
                    throw new IllegalStateException(
                            "Edge in connectedEdges does not reference this node: " + this
                    );
                }
            }
            return result;
        }

        /** Number of edges connected to this node. */
        public int getDegree() {
            return connectedEdges.size();
        }

        public BlockNetwork<T> getNetwork() {
            return BlockNetwork.this;
        }

        @Override
        public String toString() {
            return "[Network.Node]: " + blockPositions;
        }
    }

    /**
     * An edge in the network graph, connecting two {@link Node}s.
     *
     * <p>An edge may span multiple blocks; those blocks are stored in
     * {@code intermediateBlocks} (not including the node positions at either end).
     * A direct node-to-node connection has an empty intermediate list.
     *
     * <p>{@code startPos} and {@code endPos} are the block positions on the node
     * side of the edge — needed for multi-block nodes where the node's logical
     * position differs from the connection point.
     */
    public class Edge {

        private Node start;
        private Node end;

        /** The block position on the start-node side of this edge. */
        private final Vector3i startPos;
        /** The block position on the end-node side of this edge. */
        private final Vector3i endPos;

        /** Intermediate blocks only — does NOT include node positions. */
        private final List<Vector3i> intermediateBlocks;

        Edge(Node start, Node end, Vector3i startPos, Vector3i endPos,
             List<Vector3i> intermediateBlocks) {
            this.start = start;
            this.end = end;
            this.startPos = startPos;
            this.endPos = endPos;
            this.intermediateBlocks = List.copyOf(intermediateBlocks);
        }

        public Node getStart() { return start; }
        public Node getEnd()   { return end; }

        /**
         * Returns the node at the far end of this edge relative to {@code node}.
         *
         * @throws IllegalArgumentException if {@code node} is not an endpoint
         */
        public Node getOpposite(Node node) {
            if (node == start) return end;
            if (node == end)   return start;
            throw new IllegalArgumentException(
                    "Node is not an endpoint of this edge: " + node
            );
        }

        /** Intermediate blocks only — does not include the endpoint node positions. */
        public List<Vector3i> getIntermediateBlocks() {
            return intermediateBlocks;
        }

        /**
         * Returns the two positions adjacent to {@code pos} within this edge.
         * For the first and last intermediate blocks, the adjacent position on
         * the node side is {@code startPos}/{@code endPos} respectively.
         *
         * <p>Only valid for positions that are in {@code intermediateBlocks}.
         */
        Set<Vector3i> getNeighborsOf(Vector3i pos) {
            Set<Vector3i> result = new HashSet<>();
            int index = intermediateBlocks.indexOf(pos);

            if (index == 0) {
                result.add(startPos);
                if (intermediateBlocks.size() > 1) {
                    result.add(intermediateBlocks.get(1));
                }
            } else if (index == intermediateBlocks.size() - 1) {
                result.add(intermediateBlocks.get(index - 1));
                result.add(endPos);
            } else {
                result.add(intermediateBlocks.get(index - 1));
                result.add(intermediateBlocks.get(index + 1));
            }
            return result;
        }

        /** Number of block-to-block segments in the edge (intermediate blocks + 1). */
        public int getLength() {
            return intermediateBlocks.size() + 1;
        }

        public BlockNetwork<T> getNetwork() {
            return BlockNetwork.this;
        }

        @Override
        public String toString() {
            return "[Network.Edge]: " + start + " -> " + end
                    + " | " + getLength() + " seg]";
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static final AtomicInteger ID_GEN = new AtomicInteger(0);

    private final int networkId;

    /** All component blocks that belong to this network, keyed by position. */
    private final Map<Vector3i, T> componentMap = new LinkedHashMap<>();

    /** Maps every block position of a node to its Node object. */
    private final Map<Vector3i, Node> nodeMap = new HashMap<>();

    /** Maps every intermediate block position to the Edge it belongs to. */
    private final Map<Vector3i, Edge> edgeBlockMap = new HashMap<>();

    /** All edges in the network. */
    private final Set<Edge> edges = new LinkedHashSet<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public BlockNetwork() {
        this.networkId = ID_GEN.getAndIncrement();
    }

    // -------------------------------------------------------------------------
    // Abstract / overridable hooks
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the block at {@code pos} must always be
     * represented as a node, regardless of its connection count.
     * Override for machines, generators, tanks, etc.
     */
    protected abstract boolean isAlwaysNode(Vector3i pos);

    /**
     * Returns {@code true} if the block at {@code pos} is part of an
     * extendable / multi-block node (e.g. a tank segment).
     * Adjacent extendable blocks are merged into a single Node.
     */
    protected abstract boolean isExtendableNode(Vector3i pos);

    /**
     * Returns {@code true} if two in-network positions should be considered
     * connected. The default requires both positions to be in the network and
     * physically adjacent. Override for directional or type-based filtering.
     */
    protected boolean areConnected(Vector3i a, Vector3i b) {
        return componentMap.containsKey(a)
                && componentMap.containsKey(b)
                && areAdjacent(a, b);
    }

    /** Called after the graph structure has been rebuilt or modified. */
    protected void onGraphUpdated() {}

    /** Called after a block has been added to the network. */
    protected void onBlockAdded() {}

    /** Called after a block has been removed from the network. */
    protected void onBlockRemoved() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public int getNetworkId() { return networkId; }

    public int size() { return componentMap.size(); }

    public boolean contains(Vector3i pos) { return componentMap.containsKey(pos); }

    public Set<Vector3i> getPositions() {
        return Collections.unmodifiableSet(componentMap.keySet());
    }

    public Collection<Node> getNodes() {
        return Collections.unmodifiableCollection(nodeMap.values());
    }

    public Set<Edge> getEdges() {
        return Collections.unmodifiableSet(edges);
    }

    /** @return the Node at {@code pos}, or {@code null} if none. */
    public @Nullable Node getNodeAt(Vector3i pos) { return nodeMap.get(pos); }

    /** @return the Edge whose intermediate blocks include {@code pos}, or {@code null}. */
    public @Nullable Edge getEdgeAt(Vector3i pos) { return edgeBlockMap.get(pos); }

    public boolean isNode(Vector3i pos) { return nodeMap.containsKey(pos); }

    // -------------------------------------------------------------------------
    // Package-private component management
    // -------------------------------------------------------------------------

    void addComponent(Vector3i pos, T component) { componentMap.put(pos, component); }

    void addAllComponents(Map<Vector3i, T> components) { componentMap.putAll(components); }

    // -------------------------------------------------------------------------
    // Graph queries
    // -------------------------------------------------------------------------

    /**
     * Returns the network neighbors of {@code pos} using the pre-built graph
     * (fast). Requires the graph to be up to date.
     *
     * <p>Note: for multi-block nodes this does not return positions within the
     * same node — only positions across edges.
     */
    Set<Vector3i> getNetworkNeighbors(Vector3i pos) {
        if (isNode(pos)) {
            return nodeMap.get(pos).getConnectedBlockPositions();
        } else {
            return edgeBlockMap.get(pos).getNeighborsOf(pos);
        }
    }

    /**
     * Calculates the network neighbors of {@code pos} from scratch using only
     * the component map. Use this instead of {@link #getNetworkNeighbors} when
     * the edge/node maps may not yet reflect the current component map (e.g.
     * during {@link #rebuildGraph}).
     */
    Set<Vector3i> calculateNetworkNeighbors(Vector3i pos) {
        Set<Vector3i> result = new HashSet<>();
        for (Vector3i adj : getAdjacent(pos)) {
            if (areConnected(pos, adj)) {
                result.add(adj);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if {@code pos} should be represented as a node.
     * A position is a node if it is always-a-node, or if it does not have
     * exactly 2 network neighbors (0–1 → endpoint, 3+ → junction).
     */
    private boolean shouldBeNode(Vector3i pos) {
        if (isAlwaysNode(pos)) return true;
        return calculateNetworkNeighbors(pos).size() != 2;
    }

    // -------------------------------------------------------------------------
    // Graph mutations (structural)
    // -------------------------------------------------------------------------

    /**
     * Merges {@code node2} into {@code node1}. All edges and block positions of
     * {@code node2} are transferred to {@code node1}, and {@code node2}'s entries
     * are removed from the node map.
     */
    void mergeNodes(Node node1, Node node2) {
        for (Edge edge : node2.connectedEdges) {
            if (node2 == edge.start) edge.start = node1;
            else if (node2 == edge.end) edge.end = node1;
        }
        node1.connectedEdges.addAll(node2.connectedEdges);

        for (Vector3i pos : node2.blockPositions) {
            nodeMap.remove(pos);
            nodeMap.put(pos, node1);
        }
        node1.blockPositions.addAll(node2.blockPositions);
    }

    /**
     * Collapses a degree-2, non-always-node into the edge formed by its two
     * neighbours, merging the two edges on either side into one.
     */
    private void removeRedundantNode(Node node) {
        if (node == null) return;
        if (isAlwaysNode(node.blockPositions.iterator().next())) return;
        if (node.getDegree() != 2) return;

        Iterator<Edge> it = node.connectedEdges.iterator();
        Edge e1 = it.next();
        Edge e2 = it.next();

        Node a = e1.getOpposite(node);
        Node b = e2.getOpposite(node);

        // Build the merged intermediate path: e1-blocks + node-position + e2-blocks,
        // oriented so it runs from a to b.
        List<Vector3i> mergedPath = new ArrayList<>();

        if (e1.getStart() == a) {
            mergedPath.addAll(e1.getIntermediateBlocks());
        } else {
            List<Vector3i> reversed = new ArrayList<>(e1.getIntermediateBlocks());
            Collections.reverse(reversed);
            mergedPath.addAll(reversed);
        }

        mergedPath.add(node.blockPositions.iterator().next());

        if (e2.getStart() == node) {
            mergedPath.addAll(e2.getIntermediateBlocks());
        } else {
            List<Vector3i> reversed = new ArrayList<>(e2.getIntermediateBlocks());
            Collections.reverse(reversed);
            mergedPath.addAll(reversed);
        }

        deregisterEdge(e1);
        deregisterEdge(e2);

        for (Vector3i pos : node.blockPositions) {
            nodeMap.remove(pos);
        }

        // Derive startPos/endPos from the edges' orientation relative to a and b.
        Vector3i mergedStartPos = (e1.getStart() == a) ? e1.startPos : e1.endPos;
        Vector3i mergedEndPos   = (e2.getEnd()   == b) ? e2.endPos   : e2.startPos;

        registerEdge(new Edge(a, b, mergedStartPos, mergedEndPos, mergedPath));
    }

    /**
     * Splits an edge at {@code position} by inserting a new node there.
     * The original edge is replaced by two shorter edges on either side of the
     * new node.
     *
     * @throws IllegalArgumentException if {@code position} is not an intermediate
     *     block of {@code edge}
     */
    void splitEdge(Edge edge, Vector3i position) {
        if (edge == null) return;

        List<Vector3i> blocks = edge.getIntermediateBlocks();
        int index = blocks.indexOf(position);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Position is not part of edge intermediate blocks: " + position
            );
        }

        Node startNode = edge.getStart();
        Node endNode   = edge.getEnd();

        List<Vector3i> firstPath  = (index == 0)
                ? Collections.emptyList()
                : new ArrayList<>(blocks.subList(0, index));
        List<Vector3i> secondPath = (index == blocks.size() - 1)
                ? Collections.emptyList()
                : new ArrayList<>(blocks.subList(index + 1, blocks.size()));

        deregisterEdge(edge);

        Node newNode = new Node(Set.of(position));
        nodeMap.put(position, newNode);

        registerEdge(new Edge(startNode, newNode, edge.startPos, position, firstPath));
        registerEdge(new Edge(newNode, endNode,   position, edge.endPos,   secondPath));
    }

    // -------------------------------------------------------------------------
    // Full graph rebuild
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the entire node/edge graph from the current component map.
     * Should only be called on network instantiation; for incremental changes
     * use {@link #addBlock} / {@link #removeBlock}.
     *
     * <p>TODO: Extend to handle multi-block nodes correctly.
     */
    void rebuildGraph() {
        nodeMap.clear();
        edgeBlockMap.clear();
        edges.clear();

        for (Vector3i pos : componentMap.keySet()) {
            if (shouldBeNode(pos)) {
                nodeMap.put(pos, new Node(Set.of(pos)));
            }
        }

        Set<Vector3i>         visitedIntermediate = new HashSet<>();
        Set<Set<Vector3i>>    visitedDirectLinks  = new HashSet<>();

        for (Node node : new ArrayList<>(nodeMap.values())) {
            for (Vector3i pos : node.blockPositions) {
                for (Vector3i neighbor : calculateNetworkNeighbors(pos)) {

                    if (nodeMap.containsKey(neighbor)) {
                        Node neighborNode = nodeMap.get(neighbor);
                        if (neighborNode == node) continue;

                        if (isExtendableNode(pos) && isExtendableNode(neighbor)) {
                            // Adjacent extendable blocks → merge into one node.
                            mergeNodes(node, neighborNode);
                        } else {
                            // Direct node-to-node link — record each pair once.
                            Set<Vector3i> key = Set.of(pos, neighbor);
                            if (visitedDirectLinks.contains(key)) continue;
                            visitedDirectLinks.add(key);

                            registerEdge(new Edge(
                                    node, neighborNode, pos, neighbor,
                                    Collections.emptyList()
                            ));
                        }
                        continue;
                    }

                    // Neighbor is an intermediate block — trace the path until
                    // another node is reached. Junctions are always nodes
                    // (shouldBeNode returns true for degree != 2), so the linear
                    // walk below is safe.
                    if (visitedIntermediate.contains(neighbor)) continue;

                    List<Vector3i> path     = new ArrayList<>();
                    Vector3i current        = neighbor;
                    Vector3i previous       = pos;

                    while (current != null && !nodeMap.containsKey(current)) {
                        path.add(current);
                        visitedIntermediate.add(current);

                        Vector3i next = null;
                        for (Vector3i adj : calculateNetworkNeighbors(current)) {
                            if (!adj.equals(previous)) { next = adj; break; }
                        }
                        previous = current;
                        current  = next;
                    }

                    if (current != null && nodeMap.containsKey(current)) {
                        registerEdge(new Edge(
                                node, nodeMap.get(current), pos, current, path
                        ));
                    }
                }
            }
        }

        onGraphUpdated();
    }

    // -------------------------------------------------------------------------
    // Incremental graph updates
    // -------------------------------------------------------------------------

    /**
     * Adds a block to the network and updates the graph incrementally.
     */
    void addBlock(Vector3i pos, T component) {
        componentMap.put(pos, component);

        if (shouldBeNode(pos)) {

            Node node = new Node(Set.of(pos));
            nodeMap.put(pos, node);

            for (Vector3i neighbor : calculateNetworkNeighbors(pos)) {

                if (isNode(neighbor)) {
                    Node neighborNode = getNodeAt(neighbor);

                    if (isExtendableNode(pos) && isExtendableNode(neighbor)) {
                        mergeNodes(node, neighborNode);
                    } else {
                        registerEdge(new Edge(
                                node, neighborNode, pos, neighbor,
                                Collections.emptyList()
                        ));
                        // The neighbor may no longer be a junction after this connection.
                        if (!shouldBeNode(neighbor)) {
                            removeRedundantNode(neighborNode);
                        }
                    }

                } else {
                    // Neighbor is an intermediate block — split its edge to create
                    // a node there, then connect the new node to pos.
                    splitEdge(getEdgeAt(neighbor), neighbor);
                    registerEdge(new Edge(
                            node, getNodeAt(neighbor), pos, neighbor,
                            Collections.emptyList()
                    ));
                }
            }

        } else {

            // pos is a pass-through block (exactly 2 neighbors).
            Set<Vector3i> neighbors = calculateNetworkNeighbors(pos);
            if (neighbors.size() != 2) {
                throw new IllegalStateException(
                        "Non-node block must have exactly 2 network neighbors, got: "
                                + neighbors.size()
                );
            }

            Iterator<Vector3i> it = neighbors.iterator();
            Vector3i n1 = it.next();
            Vector3i n2 = it.next();

            boolean n1IsNode = isNode(n1);
            boolean n2IsNode = isNode(n2);

            if (n1IsNode && n2IsNode) {
                // Both neighbors are nodes → simple bridging edge.
                registerEdge(new Edge(
                        getNodeAt(n1), getNodeAt(n2), n1, n2, List.of(pos)
                ));

            } else if (n1IsNode ^ n2IsNode) {
                // One node, one edge → split the edge, then bridge.
                Vector3i nodePos  = n1IsNode ? n1 : n2;
                Vector3i edgePos  = n1IsNode ? n2 : n1;

                splitEdge(getEdgeAt(edgePos), edgePos);

                registerEdge(new Edge(
                        getNodeAt(nodePos), getNodeAt(edgePos),
                        nodePos, edgePos, List.of(pos)
                ));

            } else {
                // Both neighbors are intermediate blocks.
                Edge e1 = getEdgeAt(n1);
                Edge e2 = getEdgeAt(n2);

                if (e1 == e2) {
                    // n1 and n2 are on the same edge (e.g. closing a loop).
                    // Split at n1 first, then re-fetch the edge containing n2
                    // (it will be a different Edge object after the split).
                    splitEdge(e1, n1);
                    splitEdge(getEdgeAt(n2), n2);
                } else {
                    splitEdge(e1, n1);
                    splitEdge(e2, n2);
                }

                registerEdge(new Edge(
                        getNodeAt(n1), getNodeAt(n2), n1, n2, List.of(pos)
                ));
            }
        }

        onBlockAdded();
        onGraphUpdated();
    }

    /**
     * Removes a block from the network and updates the graph incrementally.
     * This is the inverse of {@link #addBlock}.
     */
    void removeBlock(Vector3i pos) {
        if (!componentMap.containsKey(pos)) return;

        if (isNode(pos)) {

            Node node = nodeMap.get(pos);

            // For a multi-block node, only detach this one position.
            if (node.blockPositions.size() > 1) {
                node.blockPositions.remove(pos);
                nodeMap.remove(pos);
                componentMap.remove(pos);
                onBlockRemoved();
                onGraphUpdated();
                return;
            }

            // Collect neighbor nodes before tearing down, so we can check
            // redundancy after the edges are removed.
            Set<Node> formerNeighbors = new HashSet<>();
            for (Edge edge : node.connectedEdges) {
                formerNeighbors.add(edge.getOpposite(node));
            }

            for (Edge edge : new ArrayList<>(node.connectedEdges)) {
                deregisterEdge(edge);

                List<Vector3i> path = edge.getIntermediateBlocks();
                if (path.isEmpty()) {
                    // Direct link — the opposite node simply loses this edge.
                    continue;
                }

                // The intermediate path is severed on the pos-side. The block
                // adjacent to pos becomes a new degree-1 node (dead end), and
                // the rest of the path becomes a new edge to the opposite node.
                Node oppositeNode = edge.getOpposite(node);
                Vector3i orphanEnd;
                List<Vector3i> remainingPath;

                if (edge.getStart() == node) {
                    orphanEnd     = path.getFirst();
                    remainingPath = path.size() > 1
                            ? new ArrayList<>(path.subList(1, path.size()))
                            : Collections.emptyList();
                    Node orphanNode = new Node(Set.of(orphanEnd));
                    nodeMap.put(orphanEnd, orphanNode);
                    registerEdge(new Edge(
                            orphanNode, oppositeNode, orphanEnd, edge.endPos, remainingPath
                    ));
                } else {
                    orphanEnd     = path.getLast();
                    remainingPath = path.size() > 1
                            ? new ArrayList<>(path.subList(0, path.size() - 1))
                            : Collections.emptyList();
                    Node orphanNode = new Node(Set.of(orphanEnd));
                    nodeMap.put(orphanEnd, orphanNode);
                    registerEdge(new Edge(
                            oppositeNode, orphanNode, edge.startPos, orphanEnd, remainingPath
                    ));
                }
            }

            nodeMap.remove(pos);
            componentMap.remove(pos);

            // A former neighbor with degree 2 may now be redundant.
            for (Node neighbor : formerNeighbors) {
                if (neighbor.getDegree() == 2
                        && !isAlwaysNode(neighbor.blockPositions.iterator().next())) {
                    removeRedundantNode(neighbor);
                }
            }

        } else {

            Edge edge = edgeBlockMap.get(pos);
            if (edge == null) {
                // Disconnected block — nothing to unlink from the graph.
                componentMap.remove(pos);
                onBlockRemoved();
                onGraphUpdated();
                return;
            }

            List<Vector3i> blocks = edge.getIntermediateBlocks();
            int index = blocks.indexOf(pos);

            List<Vector3i> firstPath  = index > 0
                    ? new ArrayList<>(blocks.subList(0, index))
                    : Collections.emptyList();
            List<Vector3i> secondPath = index < blocks.size() - 1
                    ? new ArrayList<>(blocks.subList(index + 1, blocks.size()))
                    : Collections.emptyList();

            Node startNode = edge.getStart();
            Node endNode   = edge.getEnd();

            deregisterEdge(edge);
            componentMap.remove(pos);

            // First half: startNode → gap.
            // If non-empty, the tip block (adjacent to the gap) becomes a new
            // degree-1 node; the remaining blocks form a new edge to startNode.
            if (!firstPath.isEmpty()) {
                Vector3i tip = firstPath.getLast();
                Node tipNode = new Node(Set.of(tip));
                nodeMap.put(tip, tipNode);

                List<Vector3i> innerPath = firstPath.size() > 1
                        ? new ArrayList<>(firstPath.subList(0, firstPath.size() - 1))
                        : Collections.emptyList();

                registerEdge(new Edge(startNode, tipNode, edge.startPos, tip, innerPath));
            }

            // Second half: gap → endNode.
            if (!secondPath.isEmpty()) {
                Vector3i tip = secondPath.getFirst();
                Node tipNode = new Node(Set.of(tip));
                nodeMap.put(tip, tipNode);

                List<Vector3i> innerPath = secondPath.size() > 1
                        ? new ArrayList<>(secondPath.subList(1, secondPath.size()))
                        : Collections.emptyList();

                registerEdge(new Edge(tipNode, endNode, tip, edge.endPos, innerPath));
            }

            // Either endpoint may have become redundant (degree 2, not always-node).
            for (Node endpoint : List.of(startNode, endNode)) {
                if (endpoint.getDegree() == 2
                        && !isAlwaysNode(endpoint.blockPositions.iterator().next())) {
                    removeRedundantNode(endpoint);
                }
            }
        }

        onBlockRemoved();
        onGraphUpdated();
    }

    // -------------------------------------------------------------------------
    // Internal graph registration helpers
    // -------------------------------------------------------------------------

    private void registerEdge(Edge edge) {
        edges.add(edge);
        edge.start.connectedEdges.add(edge);
        edge.end.connectedEdges.add(edge);
        for (Vector3i pos : edge.intermediateBlocks) {
            edgeBlockMap.put(pos, edge);
        }
    }

    private void deregisterEdge(Edge edge) {
        edges.remove(edge);
        edge.start.connectedEdges.remove(edge);
        edge.end.connectedEdges.remove(edge);
        for (Vector3i pos : edge.intermediateBlocks) {
            edgeBlockMap.remove(pos, edge);
        }
    }

    // -------------------------------------------------------------------------
    // Misc.
    // -------------------------------------------------------------------------

    /** Hook for subclasses; returns the node that should be updated next, if any. */
    @Nullable Node getUpdateNode() { return null; }
}