package com.example.exampleplugin.network;

import java.util.*;

public abstract class Network {

    protected final int networkId;

    protected final Map<BlockPos, Node> nodes = new HashMap<>();
    protected final Set<Edge> edges = new HashSet<>();

    protected Network(int networkId) {
        this.networkId = networkId;
    }

    /* =========================
       === ABSTRACT CONTRACT ===
       ========================= */

    /** Is this block part of this network type? */
    protected abstract boolean isNetworkBlock(BlockPos pos);

    /** Is this block a node (junction, machine, endpoint)? */
    protected abstract boolean isNodeBlock(BlockPos pos);

    /** Which directions can this block connect in? */
    protected abstract EnumSet<Direction> getConnections(BlockPos pos);

    /** Create a node instance for this block */
    protected abstract Node createNode(BlockPos pos);

    /** Create an edge instance */
    protected abstract Edge createEdge(Node a, Node b, Set<BlockPos> blocks);

    /* =========================
       === NETWORK MUTATION ===
       ========================= */

    public void onBlockPlaced(BlockPos pos) {
        if (!isNetworkBlock(pos)) return;

        List<ConnectionEnd> neighbors = findConnectedEnds(pos);

        if (isNodeBlock(pos)) {
            Node newNode = createNode(pos);
            nodes.put(pos, newNode);

            for (ConnectionEnd end : neighbors) {
                connectNodeToEnd(newNode, end);
            }
        } else {
            handleEdgeBlockPlacement(pos, neighbors);
        }
    }

    public void onBlockRemoved(BlockPos pos) {
        Node node = nodes.remove(pos);
        if (node != null) {
            removeNode(node);
            return;
        }

        Edge edge = findEdgeContaining(pos);
        if (edge != null) {
            splitEdge(edge, pos);
        }
    }

    /* =========================
       === EDGE / NODE LOGIC ===
       ========================= */

    protected void handleEdgeBlockPlacement(BlockPos pos, List<ConnectionEnd> ends) {
        if (ends.size() == 2) {
            // Extend or merge edges
            ConnectionEnd a = ends.get(0);
            ConnectionEnd b = ends.get(1);

            if (a.edge != null && a.edge == b.edge) {
                a.edge.blocks.add(pos);
            } else {
                mergeEndsWithBlock(a, b, pos);
            }
        }
        else if (ends.size() == 1) {
            ends.get(0).edge.blocks.add(pos);
        }
        else {
            // Isolated edge → create dangling edge with temp nodes
            Node n1 = createNode(pos);
            Node n2 = createNode(pos);
            Edge e = createEdge(n1, n2, new HashSet<>(Set.of(pos)));
            edges.add(e);
        }
    }

    protected void connectNodeToEnd(Node node, ConnectionEnd end) {
        if (end.edge != null) {
            end.edge.replaceNode(end.node, node);
            node.edges.add(end.edge);
        }
    }

    protected void mergeEndsWithBlock(ConnectionEnd a, ConnectionEnd b, BlockPos pos) {
        Edge merged = createEdge(
                a.node,
                b.node,
                new HashSet<>()
        );

        merged.blocks.addAll(a.edge.blocks);
        merged.blocks.addAll(b.edge.blocks);
        merged.blocks.add(pos);

        edges.remove(a.edge);
        edges.remove(b.edge);
        edges.add(merged);
    }

    protected void splitEdge(Edge edge, BlockPos removed) {
        edge.blocks.remove(removed);

        // Simple version: fully rebuild edge
        edges.remove(edge);
        rebuildFrom(edge.blocks);
    }

    protected void removeNode(Node node) {
        for (Edge e : new HashSet<>(node.edges)) {
            edges.remove(e);
            rebuildFrom(e.blocks);
        }
    }

    protected void rebuildFrom(Set<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            onBlockPlaced(pos);
        }
    }

    /* =========================
       === SEARCH HELPERS ===
       ========================= */

    protected List<ConnectionEnd> findConnectedEnds(BlockPos pos) {
        List<ConnectionEnd> ends = new ArrayList<>();

        for (Direction d : getConnections(pos)) {
            BlockPos n = pos.offset(d);

            Node node = nodes.get(n);
            if (node != null) {
                ends.add(new ConnectionEnd(node, null));
                continue;
            }

            Edge edge = findEdgeContaining(n);
            if (edge != null) {
                ends.add(new ConnectionEnd(edge.getOtherNode(null), edge));
            }
        }

        return ends;
    }

    protected Edge findEdgeContaining(BlockPos pos) {
        for (Edge e : edges) {
            if (e.blocks.contains(pos)) return e;
        }
        return null;
    }

    /* =========================
       === INNER TYPES ===
       ========================= */

    protected class ConnectionEnd {
        final Node node;
        final Edge edge;

        ConnectionEnd(Node node, Edge edge) {
            this.node = node;
            this.edge = edge;
        }
    }

    public abstract class Node {
        public final BlockPos pos;
        protected final Set<Edge> edges = new HashSet<>();

        protected Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    public abstract class Edge {
        protected Node a, b;
        protected final Set<BlockPos> blocks;

        protected Edge(Node a, Node b, Set<BlockPos> blocks) {
            this.a = a;
            this.b = b;
            this.blocks = blocks;
            a.edges.add(this);
            b.edges.add(this);
        }

        protected void replaceNode(Node oldNode, Node newNode) {
            if (a == oldNode) a = newNode;
            if (b == oldNode) b = newNode;
            oldNode.edges.remove(this);
            newNode.edges.add(this);
        }

        protected Node getOtherNode(Node n) {
            return n == a ? b : a;
        }
    }
}
