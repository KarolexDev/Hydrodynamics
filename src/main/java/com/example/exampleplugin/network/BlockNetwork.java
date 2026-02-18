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
 * <p>Every {@link Node} carries exactly one component, and every {@link Edge}
 * carries exactly one component. The {@code componentMap} maps <em>every</em>
 * block position to the component of the graph element it belongs to:
 * node-block positions point to the node's component, and intermediate edge
 * block positions point to the edge's component.
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
public abstract class BlockNetwork<T extends NetworkComponent> {

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /**
     * A node in the network graph.
     *
     * <p>A node is created for any block that satisfies {@link #isAlwaysNode},
     * or whose connection count is not exactly 2 (i.e. endpoints and junctions).
     * Multi-block nodes (e.g. tanks) share a single Node instance and a single
     * component.
     */
    public class Node {

        private T component;

        private final Set<Vector3i> blockPositions = new LinkedHashSet<>();
        private final Set<Edge> connectedEdges = new LinkedHashSet<>();

        Node(Set<Vector3i> blockPositions, T component) {
            this.blockPositions.addAll(blockPositions);
            this.component = component;
        }

        public T getComponent() { return component; }
        public Set<Vector3i> getBlockPositions() { return Collections.unmodifiableSet(blockPositions); }
        public Set<Edge> getConnectedEdges() { return Collections.unmodifiableSet(connectedEdges); }

        /**
         * Returns the set of block positions immediately adjacent to this node
         * across all connected edges — i.e. the first intermediate block of each
         * edge (or the opposite node's position for a direct link).
         */
        public Set<Vector3i> getConnectedBlockPositions() {
            Set<Vector3i> result = new HashSet<>();
            for (Edge edge : connectedEdges) {
                if (this == edge.start) {
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
        public int getDegree() { return connectedEdges.size(); }
        public BlockNetwork<T> getNetwork() { return BlockNetwork.this; }

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

        private T component;

        private Node start;
        private Node end;

        /** The block position on the start-node side of this edge. */
        private final Vector3i startPos;
        /** The block position on the end-node side of this edge. */
        private final Vector3i endPos;

        /** Intermediate blocks only — does NOT include node positions. */
        private final List<Vector3i> intermediateBlocks;

        Edge(Node start, Node end, Vector3i startPos, Vector3i endPos,
             List<Vector3i> intermediateBlocks, T component) {
            this.start = start;
            this.end = end;
            this.startPos = startPos;
            this.endPos = endPos;
            this.intermediateBlocks = List.copyOf(intermediateBlocks);
            this.component = component;
        }

        public T getComponent() { return component; }
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
        public List<Vector3i> getIntermediateBlocks() { return intermediateBlocks; }

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
                } else {
                    result.add(endPos);
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
        public int getLength() { return intermediateBlocks.size() + 1; }
        public BlockNetwork<T> getNetwork() { return BlockNetwork.this; }

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

    /**
     * Maps every block position to the component of its owning graph element:
     * <ul>
     *   <li>Node block positions → the node's component</li>
     *   <li>Intermediate edge block positions → the edge's component</li>
     * </ul>
     */
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

    public BlockNetwork() { this.networkId = ID_GEN.getAndIncrement(); }

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

    public Set<Vector3i> getPositions() { return Collections.unmodifiableSet(componentMap.keySet()); }

    public Collection<Node> getNodes() { return Collections.unmodifiableCollection(nodeMap.values()); }

    public Set<Edge> getEdges() { return Collections.unmodifiableSet(edges); }

    /** @return the Node at {@code pos}, or {@code null} if none. */
    public @Nullable Node getNodeAt(Vector3i pos) { return nodeMap.get(pos); }

    /** @return the Edge whose intermediate blocks include {@code pos}, or {@code null}. */
    public @Nullable Edge getEdgeAt(Vector3i pos) { return edgeBlockMap.get(pos); }

    public boolean isNode(Vector3i pos) { return nodeMap.containsKey(pos); }

    /**
     * Returns the component at {@code pos}, which is the component of the
     * node or edge that owns this position.
     */
    public @Nullable T getComponentAt(Vector3i pos) { return componentMap.get(pos); }

    // -------------------------------------------------------------------------
    // Package-private component management
    // -------------------------------------------------------------------------

    /**
     * Registers an external component for {@code pos} in the componentMap.
     * Used during network construction before the graph is built.
     * After the graph is built, components are managed via node/edge registration.
     */
    void addComponent(Vector3i pos, T component) { componentMap.put(pos, component); }

    void addAllComponents(Map<Vector3i, T> components) { componentMap.putAll(components); }

    /**
     * Returns a read-only view of the raw componentMap.
     * Used by {@link BlockNetworkManager} when merging networks.
     */
    Map<Vector3i, T> getRawComponentMap() { return Collections.unmodifiableMap(componentMap); }

    // -------------------------------------------------------------------------
    // Graph queries
    // -------------------------------------------------------------------------

    /**
     * Returns the network neighbors of {@code pos} using the pre-built graph
     * (fast). Requires the graph to be up to date.
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
     * are removed from the node map. The merged node's component is the combination
     * of both nodes' components.
     */
    @SuppressWarnings("unchecked")
    void mergeNodes(Node node1, Node node2) {
        for (Edge edge : node2.connectedEdges) {
            if (node2 == edge.start) edge.start = node1;
            else if (node2 == edge.end) edge.end = node1;
        }
        node1.connectedEdges.addAll(node2.connectedEdges);

        for (Vector3i pos : node2.blockPositions) {
            nodeMap.put(pos, node1);
            // Update componentMap: these positions now point to node1's merged component
        }
        node1.blockPositions.addAll(node2.blockPositions);

        // Merge the components
        node1.component = (T) node1.component.add(node2.component);

        // Update componentMap for all positions in the merged node
        for (Vector3i pos : node1.blockPositions) {
            componentMap.put(pos, node1.component);
        }
    }

    /**
     * Collapses a degree-2, non-always-node into the edge formed by its two
     * neighbours, merging the two edges on either side into one.
     */
    @SuppressWarnings("unchecked")
    private void removeRedundantNode(Node node) {
        if (node == null) return;
        if (isAlwaysNode(node.blockPositions.iterator().next())) return;
        if (node.getDegree() != 2) return;

        Iterator<Edge> it = node.connectedEdges.iterator();
        Edge e1 = it.next();
        Edge e2 = it.next();

        Node a = e1.getOpposite(node);
        Node b = e2.getOpposite(node);

        // Merged component: e1 + node + e2
        T mergedComponent = (T) e1.component.add(node.component).add(e2.component);

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
            componentMap.remove(pos);
        }

        // Derive startPos/endPos from the edges' orientation relative to a and b.
        Vector3i mergedStartPos = (e1.getStart() == a) ? e1.startPos : e1.endPos;
        Vector3i mergedEndPos   = (e2.getEnd()   == b) ? e2.endPos   : e2.startPos;

        registerEdge(new Edge(a, b, mergedStartPos, mergedEndPos, mergedPath, mergedComponent));
    }

    /**
     * Splits an edge at {@code position} by inserting a new node there.
     * The original edge is replaced by two shorter edges on either side of the
     * new node.
     *
     * <p>The edge's component is partitioned proportionally between the two
     * new edges. The new node at {@code position} receives a
     * {@code fromLength(1)} component.
     *
     * @throws IllegalArgumentException if {@code position} is not an intermediate
     *     block of {@code edge}
     */
    @SuppressWarnings("unchecked")
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

        // The new node at `position` gets a single-block component.
        T nodeComponent = (T) edge.component.fromLength(1);

        // Partition the remaining edge component between the two sides.
        // Left side: blocks[0..index-1]  → size = index
        // Right side: blocks[index+1..end] → size = blocks.size() - index - 1
        int leftSize  = index;           // number of intermediate blocks in first edge
        int rightSize = blocks.size() - index - 1; // number of intermediate blocks in second edge

        // We subtract the node component from the edge component first, then split.
        T remainingComponent = (T) edge.component.del(nodeComponent);

        NetworkUtil.Pair<T, T> split = remainingComponent.partition(
                Math.max(leftSize, 1),
                Math.max(rightSize, 1)
        );
        T firstEdgeComponent  = split.left;
        T secondEdgeComponent = split.right;

        List<Vector3i> firstPath  = (index == 0)
                ? Collections.emptyList()
                : new ArrayList<>(blocks.subList(0, index));
        List<Vector3i> secondPath = (index == blocks.size() - 1)
                ? Collections.emptyList()
                : new ArrayList<>(blocks.subList(index + 1, blocks.size()));

        deregisterEdge(edge);

        Node newNode = new Node(Set.of(position), nodeComponent);
        nodeMap.put(position, newNode);
        componentMap.put(position, nodeComponent);

        registerEdge(new Edge(startNode, newNode, edge.startPos, position, firstPath, firstEdgeComponent));
        registerEdge(new Edge(newNode, endNode, position, edge.endPos, secondPath, secondEdgeComponent));
    }

    // -------------------------------------------------------------------------
    // Full graph rebuild
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the entire node/edge graph from the current component map.
     * Should only be called on network instantiation or after bulk changes;
     * for incremental changes use {@link #addBlock} / {@link #removeBlock}.
     *
     * <p>TODO: Extend to handle multi-block nodes correctly.
     */
    @SuppressWarnings("unchecked")
    void rebuildGraph() {
        nodeMap.clear();
        edgeBlockMap.clear();
        edges.clear();

        // First pass: create nodes for all positions that should be nodes.
        for (Vector3i pos : componentMap.keySet()) {
            if (shouldBeNode(pos)) {
                nodeMap.put(pos, new Node(Set.of(pos), componentMap.get(pos)));
            }
        }

        Set<Vector3i>      visitedIntermediate = new HashSet<>();
        Set<Set<Vector3i>> visitedDirectLinks  = new HashSet<>();

        for (Node node : new ArrayList<>(nodeMap.values())) {
            for (Vector3i pos : node.blockPositions) {
                for (Vector3i neighbor : calculateNetworkNeighbors(pos)) {

                    if (nodeMap.containsKey(neighbor)) {
                        Node neighborNode = nodeMap.get(neighbor);
                        if (neighborNode == node) continue;

                        if (isExtendableNode(pos) && isExtendableNode(neighbor)) {
                            mergeNodes(node, neighborNode);
                        } else {
                            Set<Vector3i> key = Set.of(pos, neighbor);
                            if (visitedDirectLinks.contains(key)) continue;
                            visitedDirectLinks.add(key);

                            // Direct node-to-node edge: no intermediate blocks, so
                            // we create a fromLength(0) component for the edge itself.
                            T edgeComponent = (T) node.component.fromLength(0);
                            registerEdge(new Edge(
                                    node, neighborNode, pos, neighbor,
                                    Collections.emptyList(),
                                    edgeComponent
                            ));
                        }
                        continue;
                    }

                    // Neighbor is an intermediate block — trace the path until
                    // another node is reached.
                    if (visitedIntermediate.contains(neighbor)) continue;

                    List<Vector3i> path    = new ArrayList<>();
                    Vector3i current       = neighbor;
                    Vector3i previous      = pos;

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
                        // Build edge component from intermediate block components.
                        // Start with the component of the first intermediate block,
                        // then add the rest.
                        T edgeComponent = componentMap.get(path.getFirst());
                        for (int i = 1; i < path.size(); i++) {
                            edgeComponent = (T) edgeComponent.add(componentMap.get(path.get(i)));
                        }

                        registerEdge(new Edge(
                                node, nodeMap.get(current), pos, current, path, edgeComponent
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
    @SuppressWarnings("unchecked")
    void addBlock(Vector3i pos, T component) {
        componentMap.put(pos, component);

        if (shouldBeNode(pos)) {

            Node node = new Node(Set.of(pos), component);
            nodeMap.put(pos, node);

            for (Vector3i neighbor : calculateNetworkNeighbors(pos)) {

                if (isNode(neighbor)) {
                    Node neighborNode = getNodeAt(neighbor);

                    if (isExtendableNode(pos) && isExtendableNode(neighbor)) {
                        mergeNodes(node, neighborNode);
                    } else {
                        // Direct node-to-node edge with a zero-length edge component.
                        T edgeComponent = (T) component.fromLength(0);
                        registerEdge(new Edge(
                                node, neighborNode, pos, neighbor,
                                Collections.emptyList(),
                                edgeComponent
                        ));
                        if (!shouldBeNode(neighbor)) {
                            removeRedundantNode(neighborNode);
                        }
                    }

                } else {
                    // Neighbor is an intermediate block — split its edge.
                    splitEdge(getEdgeAt(neighbor), neighbor);
                    // After splitting, neighbor is now a node; connect to it.
                    T edgeComponent = (T) component.fromLength(0);
                    registerEdge(new Edge(
                            node, getNodeAt(neighbor), pos, neighbor,
                            Collections.emptyList(),
                            edgeComponent
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
                // Both neighbors are nodes → simple bridging edge with pos as intermediate.
                registerEdge(new Edge(
                        getNodeAt(n1), getNodeAt(n2), n1, n2, List.of(pos), component
                ));

            } else if (n1IsNode ^ n2IsNode) {
                // One node, one edge → split the edge side, then bridge.
                Vector3i nodePos = n1IsNode ? n1 : n2;
                Vector3i edgePos = n1IsNode ? n2 : n1;

                splitEdge(getEdgeAt(edgePos), edgePos);

                registerEdge(new Edge(
                        getNodeAt(nodePos), getNodeAt(edgePos),
                        nodePos, edgePos, List.of(pos), component
                ));

            } else {
                // Both neighbors are intermediate blocks.
                Edge e1 = getEdgeAt(n1);
                Edge e2 = getEdgeAt(n2);

                if (e1 == e2) {
                    splitEdge(e1, n1);
                    splitEdge(getEdgeAt(n2), n2);
                } else {
                    splitEdge(e1, n1);
                    splitEdge(e2, n2);
                }

                registerEdge(new Edge(
                        getNodeAt(n1), getNodeAt(n2), n1, n2, List.of(pos), component
                ));
            }
        }

        onBlockAdded();
        onGraphUpdated();
    }

    /**
     * Removes a block from the network and updates the graph incrementally.
     */
    @SuppressWarnings("unchecked")
    void removeBlock(Vector3i pos) {
        if (!componentMap.containsKey(pos)) return;

        if (isNode(pos)) {

            Node node = nodeMap.get(pos);

            // For a multi-block node, only detach this one position.
            if (node.blockPositions.size() > 1) {
                node.blockPositions.remove(pos);
                nodeMap.remove(pos);
                componentMap.remove(pos);
                // Update the node's component to reflect the removed block.
                node.component = (T) node.component.del(componentMap.get(pos));
                // Re-sync componentMap for remaining node positions.
                for (Vector3i p : node.blockPositions) {
                    componentMap.put(p, node.component);
                }
                onBlockRemoved();
                onGraphUpdated();
                return;
            }

            // Collect neighbor nodes before tearing down.
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

                Node oppositeNode = edge.getOpposite(node);

                if (edge.getStart() == node) {
                    Vector3i orphanEnd = path.getFirst();
                    List<Vector3i> remainingPath = path.size() > 1
                            ? new ArrayList<>(path.subList(1, path.size()))
                            : Collections.emptyList();

                    // Orphan node component: fromLength(1); remaining edge component: del first block.
                    T orphanNodeComp = (T) edge.component.fromLength(1);
                    T remainingEdgeComp = remainingPath.isEmpty()
                            ? (T) edge.component.fromLength(0)
                            : (T) edge.component.del(orphanNodeComp);

                    Node orphanNode = new Node(Set.of(orphanEnd), orphanNodeComp);
                    nodeMap.put(orphanEnd, orphanNode);
                    componentMap.put(orphanEnd, orphanNodeComp);

                    registerEdge(new Edge(
                            orphanNode, oppositeNode, orphanEnd, edge.endPos,
                            remainingPath, remainingEdgeComp
                    ));
                } else {
                    Vector3i orphanEnd = path.getLast();
                    List<Vector3i> remainingPath = path.size() > 1
                            ? new ArrayList<>(path.subList(0, path.size() - 1))
                            : Collections.emptyList();

                    T orphanNodeComp = (T) edge.component.fromLength(1);
                    T remainingEdgeComp = remainingPath.isEmpty()
                            ? (T) edge.component.fromLength(0)
                            : (T) edge.component.del(orphanNodeComp);

                    Node orphanNode = new Node(Set.of(orphanEnd), orphanNodeComp);
                    nodeMap.put(orphanEnd, orphanNode);
                    componentMap.put(orphanEnd, orphanNodeComp);

                    registerEdge(new Edge(
                            oppositeNode, orphanNode, edge.startPos, orphanEnd,
                            remainingPath, remainingEdgeComp
                    ));
                }
            }

            nodeMap.remove(pos);
            componentMap.remove(pos);

            for (Node neighbor : formerNeighbors) {
                if (neighbor.getDegree() == 2
                        && !isAlwaysNode(neighbor.blockPositions.iterator().next())) {
                    removeRedundantNode(neighbor);
                }
            }

        } else {

            Edge edge = edgeBlockMap.get(pos);
            if (edge == null) {
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

            // Remove the removed block's contribution from the edge component.
            T removedBlockComp = (T) edge.component.fromLength(1);
            T remainingComp    = (T) edge.component.del(removedBlockComp);

            // Split remaining component proportionally.
            int leftSize  = firstPath.size();
            int rightSize = secondPath.size();
            T firstEdgeComp;
            T secondEdgeComp;
            if (leftSize == 0 && rightSize == 0) {
                firstEdgeComp  = (T) edge.component.fromLength(0);
                secondEdgeComp = (T) edge.component.fromLength(0);
            } else if (leftSize == 0) {
                firstEdgeComp  = (T) edge.component.fromLength(0);
                secondEdgeComp = remainingComp;
            } else if (rightSize == 0) {
                firstEdgeComp  = remainingComp;
                secondEdgeComp = (T) edge.component.fromLength(0);
            } else {
                NetworkUtil.Pair<T, T> split = remainingComp.partition(leftSize, rightSize);
                firstEdgeComp  = split.left;
                secondEdgeComp = split.right;
            }

            deregisterEdge(edge);
            componentMap.remove(pos);

            // First half: startNode → gap.
            if (!firstPath.isEmpty()) {
                Vector3i tip = firstPath.getLast();
                T tipNodeComp = (T) firstEdgeComp.fromLength(1);
                T tipEdgeComp = firstPath.size() > 1
                        ? (T) firstEdgeComp.del(tipNodeComp)
                        : (T) firstEdgeComp.fromLength(0);

                Node tipNode = new Node(Set.of(tip), tipNodeComp);
                nodeMap.put(tip, tipNode);
                componentMap.put(tip, tipNodeComp);

                List<Vector3i> innerPath = firstPath.size() > 1
                        ? new ArrayList<>(firstPath.subList(0, firstPath.size() - 1))
                        : Collections.emptyList();

                registerEdge(new Edge(startNode, tipNode, edge.startPos, tip, innerPath, tipEdgeComp));
            }

            // Second half: gap → endNode.
            if (!secondPath.isEmpty()) {
                Vector3i tip = secondPath.getFirst();
                T tipNodeComp = (T) secondEdgeComp.fromLength(1);
                T tipEdgeComp = secondPath.size() > 1
                        ? (T) secondEdgeComp.del(tipNodeComp)
                        : (T) secondEdgeComp.fromLength(0);

                Node tipNode = new Node(Set.of(tip), tipNodeComp);
                nodeMap.put(tip, tipNode);
                componentMap.put(tip, tipNodeComp);

                List<Vector3i> innerPath = secondPath.size() > 1
                        ? new ArrayList<>(secondPath.subList(1, secondPath.size()))
                        : Collections.emptyList();

                registerEdge(new Edge(tipNode, endNode, tip, edge.endPos, innerPath, tipEdgeComp));
            }

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
            componentMap.put(pos, edge.component);
        }
    }

    private void deregisterEdge(Edge edge) {
        edges.remove(edge);
        edge.start.connectedEdges.remove(edge);
        edge.end.connectedEdges.remove(edge);
        for (Vector3i pos : edge.intermediateBlocks) {
            edgeBlockMap.remove(pos);
            componentMap.remove(pos);
        }
    }

    // -------------------------------------------------------------------------
    // Misc.
    // -------------------------------------------------------------------------

    /** Hook for subclasses; returns the node that should be updated next, if any. */
    @Nullable Node getUpdateNode() { return null; }
}
