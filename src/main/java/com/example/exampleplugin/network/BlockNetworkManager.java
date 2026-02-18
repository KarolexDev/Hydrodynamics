package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.example.exampleplugin.network.NetworkUtil.areAdjacent;
import static com.example.exampleplugin.network.NetworkUtil.getAdjacent;

/**
 * Manages all block-network instances of a given component type.
 *
 * <p>The manager owns the full lifecycle of every network: creating, merging,
 * splitting, and destroying them as blocks are placed or removed. Each
 * contiguous group of connected blocks forms one {@link NetworkState}, which
 * holds the compressed graph (nodes + edges) for that region.
 *
 * <p><b>Graph model:</b> blocks that are simple pass-through segments are
 * stored as <em>edge intermediate blocks</em> rather than nodes, keeping the
 * node/edge count low regardless of cable length. Every {@link Node} carries
 * exactly one component, and every {@link Edge} carries exactly one component.
 * The internal {@code componentMap} of each {@link NetworkState} maps every
 * block position to the component of the graph element that owns it:
 * node-block positions → node component; intermediate edge-block positions →
 * edge component.
 *
 * <p><b>Usage:</b>
 * <ol>
 *   <li>Instantiate with two {@link Predicate}s: {@code isAlwaysNode} and
 *       {@code isExtendableNode}.</li>
 *   <li>Call {@link #onBlockPlaced} / {@link #onBlockRemoved} whenever a
 *       network-relevant block changes in the world.</li>
 *   <li>For network splits (which require a full traversal) call
 *       {@link #recalculateNetworks(Function)} explicitly — typically on world
 *       load or after bulk removals.</li>
 * </ol>
 *
 * <p>Override the {@code on*} hook methods to react to structural changes.
 *
 * @param <C> the component type stored at each block position
 */
public class BlockNetworkManager<C extends NetworkComponent> {

    // =========================================================================
    // Node
    // =========================================================================

    /**
     * A node in a network graph.
     *
     * <p>A node is created for any block that satisfies {@code isAlwaysNode},
     * or whose connection count is not exactly 2 (endpoints and junctions).
     * Multi-block nodes (e.g. tanks) share a single {@code Node} instance and
     * a single component.
     */
    public class Node {

        C component;

        final Set<Vector3i> blockPositions = new LinkedHashSet<>();
        final Set<Edge>     connectedEdges  = new LinkedHashSet<>();

        private NetworkState owner;

        Node(NetworkState owner, Set<Vector3i> positions, C component) {
            this.owner = owner;
            this.blockPositions.addAll(positions);
            this.component = component;
        }

        public C getComponent() { return component; }

        public Set<Vector3i> getBlockPositions() {
            return Collections.unmodifiableSet(blockPositions);
        }

        public Set<Edge> getConnectedEdges() {
            return Collections.unmodifiableSet(connectedEdges);
        }

        /**
         * Returns the block positions immediately adjacent to this node across
         * all connected edges — i.e. the first intermediate block of each edge,
         * or the opposite node's position for a direct link.
         */
        public Set<Vector3i> getConnectedBlockPositions() {
            Set<Vector3i> result = new HashSet<>();
            for (Edge edge : connectedEdges) {
                if (this == edge.start) {
                    result.add(edge.intermediateBlocks.isEmpty()
                            ? edge.endPos
                            : edge.intermediateBlocks.getFirst());
                } else if (this == edge.end) {
                    result.add(edge.intermediateBlocks.isEmpty()
                            ? edge.startPos
                            : edge.intermediateBlocks.getLast());
                } else {
                    throw new IllegalStateException(
                            "Edge in connectedEdges does not reference this node: " + this);
                }
            }
            return result;
        }

        /** Number of edges connected to this node. */
        public int getDegree() { return connectedEdges.size(); }

        /** The {@link NetworkState} that owns this node. */
        public NetworkState getNetwork() { return owner; }

        @Override
        public String toString() { return "[Node]: " + blockPositions; }
    }

    // =========================================================================
    // Edge
    // =========================================================================

    /**
     * An edge in a network graph, connecting two {@link Node}s.
     *
     * <p>An edge may span multiple blocks; those intermediate blocks are stored
     * in {@code intermediateBlocks} (node positions at either end are excluded).
     * A direct node-to-node connection has an empty intermediate list.
     *
     * <p>{@code startPos}/{@code endPos} are the block positions on each node
     * side of the edge — needed for multi-block nodes where the node's logical
     * position differs from the connection point.
     */
    public class Edge {

        C component;

        Node start;
        Node end;

        /** Block position on the start-node side of this edge. */
        final Vector3i startPos;
        /** Block position on the end-node side of this edge. */
        final Vector3i endPos;

        /** Intermediate blocks only — does NOT include node positions. */
        final List<Vector3i> intermediateBlocks;

        Edge(Node start, Node end, Vector3i startPos, Vector3i endPos,
             List<Vector3i> intermediateBlocks, C component) {
            this.start = start;
            this.end = end;
            this.startPos = startPos;
            this.endPos = endPos;
            this.intermediateBlocks = List.copyOf(intermediateBlocks);
            this.component = component;
        }

        public C getComponent() { return component; }
        public Node getStart()   { return start; }
        public Node getEnd()     { return end; }

        /**
         * Returns the node at the far end of this edge relative to
         * {@code node}.
         *
         * @throws IllegalArgumentException if {@code node} is not an endpoint
         */
        public Node getOpposite(Node node) {
            if (node == start) return end;
            if (node == end)   return start;
            throw new IllegalArgumentException(
                    "Node is not an endpoint of this edge: " + node);
        }

        /** Intermediate blocks only — does not include the endpoint node positions. */
        public List<Vector3i> getIntermediateBlocks() { return intermediateBlocks; }

        /**
         * Returns the two positions adjacent to {@code pos} within this edge.
         * For the first/last intermediate block the adjacent position on the
         * node side is {@code startPos}/{@code endPos} respectively.
         * Only valid for positions that are in {@code intermediateBlocks}.
         */
        Set<Vector3i> getNeighborsOf(Vector3i pos) {
            Set<Vector3i> result = new HashSet<>();
            int index = intermediateBlocks.indexOf(pos);
            if (index == 0) {
                result.add(startPos);
                result.add(intermediateBlocks.size() > 1
                        ? intermediateBlocks.get(1)
                        : endPos);
            } else if (index == intermediateBlocks.size() - 1) {
                result.add(intermediateBlocks.get(index - 1));
                result.add(endPos);
            } else {
                result.add(intermediateBlocks.get(index - 1));
                result.add(intermediateBlocks.get(index + 1));
            }
            return result;
        }

        /** Number of block-to-block segments (intermediate blocks + 1). */
        public int getLength() { return intermediateBlocks.size() + 1; }

        @Override
        public String toString() {
            return "[Edge]: " + start + " -> " + end + " | " + getLength() + " seg]";
        }
    }

    // =========================================================================
    // NetworkState
    // =========================================================================

    /**
     * Holds the full graph state for one contiguous network region.
     *
     * <p>Instances are created and destroyed exclusively by
     * {@link BlockNetworkManager}. External code reads graph data through the
     * public getters; mutations happen through the manager's public API.
     */
    public class NetworkState {

        private static final AtomicInteger ID_GEN = new AtomicInteger(0);

        private final int networkId = ID_GEN.getAndIncrement();

        /**
         * Maps every block position to the component of its owning graph
         * element: node-block positions → node component; intermediate
         * edge-block positions → edge component.
         */
        private final Map<Vector3i, C> componentMap = new LinkedHashMap<>();

        /** Maps every node-block position to its {@link Node}. */
        private final Map<Vector3i, Node> nodeMap = new HashMap<>();

        /** Maps every intermediate edge-block position to its {@link Edge}. */
        private final Map<Vector3i, Edge> edgeBlockMap = new HashMap<>();

        /** All edges in this network. */
        private final Set<Edge> edges = new LinkedHashSet<>();

        // ---- public read API ------------------------------------------------

        public int getNetworkId()   { return networkId; }
        public int size()           { return componentMap.size(); }

        public boolean contains(Vector3i pos) {
            return componentMap.containsKey(pos);
        }

        public Set<Vector3i> getPositions() {
            return Collections.unmodifiableSet(componentMap.keySet());
        }

        public Collection<Node> getNodes() {
            return Collections.unmodifiableCollection(nodeMap.values());
        }

        public Set<Edge> getEdges() {
            return Collections.unmodifiableSet(edges);
        }

        public @Nullable Node getNodeAt(Vector3i pos) { return nodeMap.get(pos); }
        public @Nullable Edge getEdgeAt(Vector3i pos) { return edgeBlockMap.get(pos); }

        public boolean isNode(Vector3i pos) { return nodeMap.containsKey(pos); }

        /**
         * Returns the component at {@code pos} — the component of the node
         * or edge that owns this block position.
         */
        public @Nullable C getComponentAt(Vector3i pos) { return componentMap.get(pos); }

        // ---- internal component helpers (package-private) -------------------

        void putComponent(Vector3i pos, C component) {
            componentMap.put(pos, component);
        }

        void putAllComponents(Map<Vector3i, C> components) {
            componentMap.putAll(components);
        }

        Map<Vector3i, C> getRawComponentMap() {
            return Collections.unmodifiableMap(componentMap);
        }

        // ---- graph queries --------------------------------------------------

        Set<Vector3i> getNetworkNeighbors(Vector3i pos) {
            if (isNode(pos)) return nodeMap.get(pos).getConnectedBlockPositions();
            return edgeBlockMap.get(pos).getNeighborsOf(pos);
        }

        Set<Vector3i> calculateNetworkNeighbors(Vector3i pos) {
            Set<Vector3i> result = new HashSet<>();
            for (Vector3i adj : getAdjacent(pos)) {
                if (componentMap.containsKey(adj) && areConnected(pos, adj)) {
                    result.add(adj);
                }
            }
            return result;
        }

        private boolean shouldBeNode(Vector3i pos) {
            if (isAlwaysNode.test(pos)) return true;
            return calculateNetworkNeighbors(pos).size() != 2;
        }

        // ---- graph mutations ------------------------------------------------

        @SuppressWarnings("unchecked")
        void mergeNodes(Node node1, Node node2) {
            for (Edge edge : node2.connectedEdges) {
                if (node2 == edge.start) edge.start = node1;
                else if (node2 == edge.end) edge.end = node1;
            }
            node1.connectedEdges.addAll(node2.connectedEdges);
            for (Vector3i pos : node2.blockPositions) nodeMap.put(pos, node1);
            node1.blockPositions.addAll(node2.blockPositions);
            node1.component = (C) node1.component.add(node2.component);
            for (Vector3i pos : node1.blockPositions) componentMap.put(pos, node1.component);
        }

        @SuppressWarnings("unchecked")
        void removeRedundantNode(Node node) {
            if (node == null) return;
            if (isAlwaysNode.test(node.blockPositions.iterator().next())) return;
            if (node.getDegree() != 2) return;

            Iterator<Edge> it = node.connectedEdges.iterator();
            Edge e1 = it.next();
            Edge e2 = it.next();

            Node a = e1.getOpposite(node);
            Node b = e2.getOpposite(node);

            C mergedComponent = (C) e1.component.add(node.component).add(e2.component);

            List<Vector3i> mergedPath = new ArrayList<>();
            if (e1.start == a) {
                mergedPath.addAll(e1.intermediateBlocks);
            } else {
                List<Vector3i> rev = new ArrayList<>(e1.intermediateBlocks);
                Collections.reverse(rev);
                mergedPath.addAll(rev);
            }
            mergedPath.add(node.blockPositions.iterator().next());
            if (e2.start == node) {
                mergedPath.addAll(e2.intermediateBlocks);
            } else {
                List<Vector3i> rev = new ArrayList<>(e2.intermediateBlocks);
                Collections.reverse(rev);
                mergedPath.addAll(rev);
            }

            deregisterEdge(e1);
            deregisterEdge(e2);
            for (Vector3i pos : node.blockPositions) {
                nodeMap.remove(pos);
                componentMap.remove(pos);
            }

            Vector3i mergedStartPos = (e1.start == a) ? e1.startPos : e1.endPos;
            Vector3i mergedEndPos   = (e2.end   == b) ? e2.endPos   : e2.startPos;
            registerEdge(new Edge(a, b, mergedStartPos, mergedEndPos, mergedPath, mergedComponent));
        }

        @SuppressWarnings("unchecked")
        void splitEdge(Edge edge, Vector3i position) {
            if (edge == null) return;

            List<Vector3i> blocks = edge.intermediateBlocks;
            int index = blocks.indexOf(position);
            if (index < 0) throw new IllegalArgumentException(
                    "Position is not part of edge intermediate blocks: " + position);

            Node startNode = edge.start;
            Node endNode   = edge.end;

            C nodeComponent      = (C) edge.component.fromLength(1);
            C remainingComponent = (C) edge.component.del(nodeComponent);

            int leftSize  = index;
            int rightSize = blocks.size() - index - 1;

            NetworkUtil.Pair<C, C> split = remainingComponent.partition(
                    Math.max(leftSize, 1), Math.max(rightSize, 1));
            C firstEdgeComponent  = split.left;
            C secondEdgeComponent = split.right;

            List<Vector3i> firstPath  = index == 0
                    ? Collections.emptyList()
                    : new ArrayList<>(blocks.subList(0, index));
            List<Vector3i> secondPath = index == blocks.size() - 1
                    ? Collections.emptyList()
                    : new ArrayList<>(blocks.subList(index + 1, blocks.size()));

            deregisterEdge(edge);

            Node newNode = new Node(this, Set.of(position), nodeComponent);
            nodeMap.put(position, newNode);
            componentMap.put(position, nodeComponent);

            registerEdge(new Edge(startNode, newNode, edge.startPos, position,
                    firstPath, firstEdgeComponent));
            registerEdge(new Edge(newNode, endNode, position, edge.endPos,
                    secondPath, secondEdgeComponent));
        }

        // ---- full graph rebuild ---------------------------------------------

        @SuppressWarnings("unchecked")
        void rebuildGraph() {
            nodeMap.clear();
            edgeBlockMap.clear();
            edges.clear();

            for (Vector3i pos : componentMap.keySet()) {
                if (shouldBeNode(pos)) {
                    nodeMap.put(pos, new Node(this, Set.of(pos), componentMap.get(pos)));
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

                            if (isExtendableNode.test(pos) && isExtendableNode.test(neighbor)) {
                                mergeNodes(node, neighborNode);
                            } else {
                                Set<Vector3i> key = Set.of(pos, neighbor);
                                if (visitedDirectLinks.contains(key)) continue;
                                visitedDirectLinks.add(key);
                                C edgeComponent = (C) node.component.fromLength(0);
                                registerEdge(new Edge(node, neighborNode, pos, neighbor,
                                        Collections.emptyList(), edgeComponent));
                            }
                            continue;
                        }

                        if (visitedIntermediate.contains(neighbor)) continue;

                        List<Vector3i> path = new ArrayList<>();
                        Vector3i current    = neighbor;
                        Vector3i previous   = pos;

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
                            C edgeComponent = componentMap.get(path.getFirst());
                            for (int i = 1; i < path.size(); i++) {
                                edgeComponent = (C) edgeComponent.add(componentMap.get(path.get(i)));
                            }
                            registerEdge(new Edge(node, nodeMap.get(current), pos, current,
                                    path, edgeComponent));
                        }
                    }
                }
            }

            onGraphUpdated(this);
        }

        // ---- incremental updates --------------------------------------------

        @SuppressWarnings("unchecked")
        void addBlock(Vector3i pos, C component) {
            componentMap.put(pos, component);

            if (shouldBeNode(pos)) {

                Node node = new Node(this, Set.of(pos), component);
                nodeMap.put(pos, node);

                for (Vector3i neighbor : calculateNetworkNeighbors(pos)) {
                    if (isNode(neighbor)) {
                        Node neighborNode = getNodeAt(neighbor);
                        if (isExtendableNode.test(pos) && isExtendableNode.test(neighbor)) {
                            mergeNodes(node, neighborNode);
                        } else {
                            C edgeComponent = (C) component.fromLength(0);
                            registerEdge(new Edge(node, neighborNode, pos, neighbor,
                                    Collections.emptyList(), edgeComponent));
                            if (!shouldBeNode(neighbor)) removeRedundantNode(neighborNode);
                        }
                    } else {
                        splitEdge(getEdgeAt(neighbor), neighbor);
                        C edgeComponent = (C) component.fromLength(0);
                        registerEdge(new Edge(node, getNodeAt(neighbor), pos, neighbor,
                                Collections.emptyList(), edgeComponent));
                    }
                }

            } else {

                Set<Vector3i> neighbors = calculateNetworkNeighbors(pos);
                if (neighbors.size() != 2) throw new IllegalStateException(
                        "Non-node block must have exactly 2 network neighbors, got: "
                                + neighbors.size());

                Iterator<Vector3i> it = neighbors.iterator();
                Vector3i n1 = it.next();
                Vector3i n2 = it.next();

                boolean n1IsNode = isNode(n1);
                boolean n2IsNode = isNode(n2);

                if (n1IsNode && n2IsNode) {
                    registerEdge(new Edge(getNodeAt(n1), getNodeAt(n2),
                            n1, n2, List.of(pos), component));

                } else if (n1IsNode ^ n2IsNode) {
                    Vector3i nodePos = n1IsNode ? n1 : n2;
                    Vector3i edgePos = n1IsNode ? n2 : n1;
                    splitEdge(getEdgeAt(edgePos), edgePos);
                    registerEdge(new Edge(getNodeAt(nodePos), getNodeAt(edgePos),
                            nodePos, edgePos, List.of(pos), component));

                } else {
                    Edge e1 = getEdgeAt(n1);
                    Edge e2 = getEdgeAt(n2);
                    if (e1 == e2) {
                        splitEdge(e1, n1);
                        splitEdge(getEdgeAt(n2), n2);
                    } else {
                        splitEdge(e1, n1);
                        splitEdge(e2, n2);
                    }
                    registerEdge(new Edge(getNodeAt(n1), getNodeAt(n2),
                            n1, n2, List.of(pos), component));
                }
            }

            onBlockAdded(this);
            onGraphUpdated(this);
        }

        @SuppressWarnings("unchecked")
        void removeBlock(Vector3i pos) {
            if (!componentMap.containsKey(pos)) return;

            if (isNode(pos)) {

                Node node = nodeMap.get(pos);

                // Multi-block node: detach only this one position.
                if (node.blockPositions.size() > 1) {
                    C removedComp = componentMap.get(pos);
                    node.blockPositions.remove(pos);
                    nodeMap.remove(pos);
                    componentMap.remove(pos);
                    node.component = (C) node.component.del(removedComp);
                    for (Vector3i p : node.blockPositions) componentMap.put(p, node.component);
                    onBlockRemoved(this);
                    onGraphUpdated(this);
                    return;
                }

                Set<Node> formerNeighbors = new HashSet<>();
                for (Edge edge : node.connectedEdges) formerNeighbors.add(edge.getOpposite(node));

                for (Edge edge : new ArrayList<>(node.connectedEdges)) {
                    deregisterEdge(edge);
                    List<Vector3i> path = edge.intermediateBlocks;
                    if (path.isEmpty()) continue;   // direct link — nothing to rewire

                    Node oppositeNode = edge.getOpposite(node);
                    C orphanNodeComp  = (C) edge.component.fromLength(1);

                    if (edge.start == node) {
                        Vector3i orphanEnd = path.getFirst();
                        List<Vector3i> remain = path.size() > 1
                                ? new ArrayList<>(path.subList(1, path.size()))
                                : Collections.emptyList();
                        C remainEdgeComp = remain.isEmpty()
                                ? (C) edge.component.fromLength(0)
                                : (C) edge.component.del(orphanNodeComp);
                        Node orphanNode = new Node(this, Set.of(orphanEnd), orphanNodeComp);
                        nodeMap.put(orphanEnd, orphanNode);
                        componentMap.put(orphanEnd, orphanNodeComp);
                        registerEdge(new Edge(orphanNode, oppositeNode,
                                orphanEnd, edge.endPos, remain, remainEdgeComp));
                    } else {
                        Vector3i orphanEnd = path.getLast();
                        List<Vector3i> remain = path.size() > 1
                                ? new ArrayList<>(path.subList(0, path.size() - 1))
                                : Collections.emptyList();
                        C remainEdgeComp = remain.isEmpty()
                                ? (C) edge.component.fromLength(0)
                                : (C) edge.component.del(orphanNodeComp);
                        Node orphanNode = new Node(this, Set.of(orphanEnd), orphanNodeComp);
                        nodeMap.put(orphanEnd, orphanNode);
                        componentMap.put(orphanEnd, orphanNodeComp);
                        registerEdge(new Edge(oppositeNode, orphanNode,
                                edge.startPos, orphanEnd, remain, remainEdgeComp));
                    }
                }

                nodeMap.remove(pos);
                componentMap.remove(pos);

                for (Node neighbor : formerNeighbors) {
                    if (neighbor.getDegree() == 2
                            && !isAlwaysNode.test(neighbor.blockPositions.iterator().next())) {
                        removeRedundantNode(neighbor);
                    }
                }

            } else {

                Edge edge = edgeBlockMap.get(pos);
                if (edge == null) {
                    componentMap.remove(pos);
                    onBlockRemoved(this);
                    onGraphUpdated(this);
                    return;
                }

                List<Vector3i> blocks = edge.intermediateBlocks;
                int index = blocks.indexOf(pos);

                List<Vector3i> firstPath  = index > 0
                        ? new ArrayList<>(blocks.subList(0, index))
                        : Collections.emptyList();
                List<Vector3i> secondPath = index < blocks.size() - 1
                        ? new ArrayList<>(blocks.subList(index + 1, blocks.size()))
                        : Collections.emptyList();

                Node startNode = edge.start;
                Node endNode   = edge.end;

                C removedBlockComp = (C) edge.component.fromLength(1);
                C remainingComp    = (C) edge.component.del(removedBlockComp);

                int leftSize  = firstPath.size();
                int rightSize = secondPath.size();
                C firstEdgeComp;
                C secondEdgeComp;

                if (leftSize == 0 && rightSize == 0) {
                    firstEdgeComp  = (C) edge.component.fromLength(0);
                    secondEdgeComp = (C) edge.component.fromLength(0);
                } else if (leftSize == 0) {
                    firstEdgeComp  = (C) edge.component.fromLength(0);
                    secondEdgeComp = remainingComp;
                } else if (rightSize == 0) {
                    firstEdgeComp  = remainingComp;
                    secondEdgeComp = (C) edge.component.fromLength(0);
                } else {
                    NetworkUtil.Pair<C, C> split = remainingComp.partition(leftSize, rightSize);
                    firstEdgeComp  = split.left;
                    secondEdgeComp = split.right;
                }

                deregisterEdge(edge);
                componentMap.remove(pos);

                if (!firstPath.isEmpty()) {
                    Vector3i tip  = firstPath.getLast();
                    C tipNodeComp = (C) firstEdgeComp.fromLength(1);
                    C tipEdgeComp = firstPath.size() > 1
                            ? (C) firstEdgeComp.del(tipNodeComp)
                            : (C) firstEdgeComp.fromLength(0);
                    List<Vector3i> inner = firstPath.size() > 1
                            ? new ArrayList<>(firstPath.subList(0, firstPath.size() - 1))
                            : Collections.emptyList();
                    Node tipNode = new Node(this, Set.of(tip), tipNodeComp);
                    nodeMap.put(tip, tipNode);
                    componentMap.put(tip, tipNodeComp);
                    registerEdge(new Edge(startNode, tipNode,
                            edge.startPos, tip, inner, tipEdgeComp));
                }

                if (!secondPath.isEmpty()) {
                    Vector3i tip  = secondPath.getFirst();
                    C tipNodeComp = (C) secondEdgeComp.fromLength(1);
                    C tipEdgeComp = secondPath.size() > 1
                            ? (C) secondEdgeComp.del(tipNodeComp)
                            : (C) secondEdgeComp.fromLength(0);
                    List<Vector3i> inner = secondPath.size() > 1
                            ? new ArrayList<>(secondPath.subList(1, secondPath.size()))
                            : Collections.emptyList();
                    Node tipNode = new Node(this, Set.of(tip), tipNodeComp);
                    nodeMap.put(tip, tipNode);
                    componentMap.put(tip, tipNodeComp);
                    registerEdge(new Edge(tipNode, endNode,
                            tip, edge.endPos, inner, tipEdgeComp));
                }

                for (Node endpoint : List.of(startNode, endNode)) {
                    if (endpoint.getDegree() == 2
                            && !isAlwaysNode.test(endpoint.blockPositions.iterator().next())) {
                        removeRedundantNode(endpoint);
                    }
                }
            }

            onBlockRemoved(this);
            onGraphUpdated(this);
        }

        // ---- edge registration ----------------------------------------------

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
    }

    // =========================================================================
    // BlockNetworkManager — fields
    // =========================================================================

    /** Maps every block position to the {@link NetworkState} that owns it. */
    private final Map<Vector3i, NetworkState> posToNetwork = new HashMap<>();

    /** All active network states. */
    private final Set<NetworkState> networks = new LinkedHashSet<>();

    /**
     * Predicate: must a block at this position always be represented as a node
     * (machines, generators, etc.)?
     */
    private final Predicate<Vector3i> isAlwaysNode;

    /**
     * Predicate: is a block at this position part of an extendable / multi-block
     * node (tanks, etc.)? Adjacent extendable blocks are merged into one Node.
     */
    private final Predicate<Vector3i> isExtendableNode;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a manager.
     *
     * @param isAlwaysNode     {@code true} → position is always a graph node
     * @param isExtendableNode {@code true} → position belongs to a multi-block node;
     *                         adjacent extendable blocks are merged
     */
    public BlockNetworkManager(Predicate<Vector3i> isAlwaysNode,
                               Predicate<Vector3i> isExtendableNode) {
        this.isAlwaysNode     = isAlwaysNode;
        this.isExtendableNode = isExtendableNode;
    }

    // =========================================================================
    // Overridable hooks
    // =========================================================================

    /**
     * Returns {@code true} if two in-network positions should be considered
     * connected. The default requires physical adjacency. Override for
     * directional or type-based filtering.
     *
     * <p>Note: the {@code componentMap} membership check (both positions must
     * be in the network) is performed by {@link NetworkState} before calling
     * this method, so implementations only need to express the connectivity
     * condition itself.
     */
    protected boolean areConnected(Vector3i a, Vector3i b) {
        return areAdjacent(a, b);
    }

    /** Called after a block has been added to {@code network}. */
    protected void onBlockAdded(NetworkState network) {}

    /** Called after a block has been removed from {@code network}. */
    protected void onBlockRemoved(NetworkState network) {}

    /** Called after the graph of {@code network} has been structurally updated. */
    protected void onGraphUpdated(NetworkState network) {}

    /** Called after a new {@link NetworkState} is created. */
    protected void onNetworkCreated(NetworkState network) {}

    /** Called before a {@link NetworkState} is discarded (empty or merged). */
    protected void onNetworkDestroyed(NetworkState network) {}

    // =========================================================================
    // Public API — manager level
    // =========================================================================

    /** Returns the {@link NetworkState} that owns {@code pos}, or {@code null}. */
    public @Nullable NetworkState getNetworkAt(Vector3i pos) {
        return posToNetwork.get(pos);
    }

    /**
     * Returns the component at {@code pos} (resolving through the owning
     * network), or {@code null} if the position is not in any network.
     */
    public @Nullable C getComponentAt(Vector3i pos) {
        NetworkState net = posToNetwork.get(pos);
        return net == null ? null : net.getComponentAt(pos);
    }

    /** Read-only view of every active network. */
    public Set<NetworkState> getAllNetworks() {
        return Collections.unmodifiableSet(networks);
    }

    /**
     * Call when a network-relevant block with {@code component} is placed at
     * {@code pos}.
     *
     * <p>Handles isolated placement, extension of an existing network, and
     * merging of multiple networks bridged by the new block.
     *
     * @return the {@link NetworkState} the block was added to
     */
    public NetworkState onBlockPlaced(Vector3i pos, C component) {
        // Collect distinct neighbouring networks.
        Map<Integer, NetworkState> neighborNets = new LinkedHashMap<>();
        for (Vector3i adj : getAdjacent(pos)) {
            NetworkState net = posToNetwork.get(adj);
            if (net != null) neighborNets.putIfAbsent(net.getNetworkId(), net);
        }

        NetworkState target;

        if (neighborNets.isEmpty()) {
            // Isolated block — brand-new single-block network.
            target = new NetworkState();
            networks.add(target);
            target.addBlock(pos, component);
            posToNetwork.put(pos, target);

        } else if (neighborNets.size() == 1) {
            // Extends exactly one existing network.
            target = neighborNets.values().iterator().next();
            target.addBlock(pos, component);
            posToNetwork.put(pos, target);

        } else {
            // Bridges multiple networks — transfer all into the first, rebuild.
            Iterator<NetworkState> it = neighborNets.values().iterator();
            target = it.next();

            while (it.hasNext()) {
                NetworkState other = it.next();
                for (Vector3i p : other.getPositions()) posToNetwork.put(p, target);
                target.putAllComponents(other.getRawComponentMap());
                networks.remove(other);
                onNetworkDestroyed(other);
            }

            // Register the new block, then do a full rebuild of the merged graph.
            target.putComponent(pos, component);
            posToNetwork.put(pos, target);
            target.rebuildGraph();
        }

        return target;
    }

    /**
     * Call when the block at {@code pos} is removed from the world.
     *
     * <p><b>Note:</b> this method does not detect network splits. Call
     * {@link #recalculateNetworks(Function)} explicitly when a split might
     * have occurred (e.g. after bulk removals or on world load).
     */
    public void onBlockRemoved(Vector3i pos) {
        NetworkState network = posToNetwork.remove(pos);
        if (network == null) return;

        network.removeBlock(pos);

        if (network.size() == 0) {
            networks.remove(network);
            onNetworkDestroyed(network);
        }
    }

    /**
     * Recalculates all networks from scratch using the current
     * {@code posToNetwork} key-set and a caller-supplied component source.
     *
     * <p>Flood-fills to find connected components, splits disconnected
     * networks, destroys empty ones, and rebuilds every graph. This is
     * expensive — call only when necessary (world load, bulk changes).
     *
     * @param componentSupplier maps a block position to its current component
     */
    public void recalculateNetworks(Function<Vector3i, C> componentSupplier) {
        Set<Vector3i> allPositions = new HashSet<>(posToNetwork.keySet());

        networks.clear();
        posToNetwork.clear();

        Set<Vector3i> visited = new HashSet<>();
        for (Vector3i start : allPositions) {
            if (visited.contains(start)) continue;

            // BFS — find the connected component that contains `start`.
            Set<Vector3i> component = new LinkedHashSet<>();
            Queue<Vector3i> queue   = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            component.add(start);

            while (!queue.isEmpty()) {
                Vector3i pos = queue.poll();
                for (Vector3i adj : getAdjacent(pos)) {
                    if (visited.contains(adj) || !allPositions.contains(adj)) continue;
                    if (!areConnected(pos, adj)) continue;
                    visited.add(adj);
                    component.add(adj);
                    queue.add(adj);
                }
            }

            NetworkState network = new NetworkState();
            Map<Vector3i, C> components = new LinkedHashMap<>();
            for (Vector3i p : component) {
                C comp = componentSupplier.apply(p);
                components.put(p, comp);
                posToNetwork.put(p, network);
            }
            network.putAllComponents(components);
            network.rebuildGraph();
            networks.add(network);
            onNetworkCreated(network);
        }
    }
}
