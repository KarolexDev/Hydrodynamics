package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.server.core.universe.world.World;
import com.karolex.hydrodynamics.util.BlockUtil;
import com.karolex.hydrodynamics.blocknetwork.BlockNetworkSerialization.*;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.*;
import java.util.function.Supplier;

/**
 * Class carrying all of the network modification logic.
 *
 * @param <C>
 */
public abstract class BlockNetwork<C extends BlockNetworkComponent<C>> {

    private static final float MIN_UPDATE_INTERVAL = 0.05f;  // max. 20 Updates/s at high activity
    private static final float MAX_UPDATE_INTERVAL = 5.0f;   // min. 1 Update every 5s at rest

    private final Map<Vector3i, Node> nodeMap = new HashMap<>();
    private final Set<Node> nodes = new HashSet<>();
    private final Supplier<BlockNetwork<C>> factory;

    protected BlockNetwork(Supplier<BlockNetwork<C>> factory) {
        this.factory = factory;
    }

    private final Set<Node> visitedNodes = new HashSet<>();
    private final Set<Node> nodesToUpdate = new HashSet<>();

    void tick(float dt, World world) {
        // TODO: Implement wave triggering.
        // TODO: Perhaps implement propagation delay (ver cumbersome and likely unnecessary...)

        Set<Node> newNodesToUpdate = new HashSet<>();
        Set<Edge> edgesToUpdate = new HashSet<>();

        for (Node node : nodesToUpdate) {
            node.update(dt, world);
            for (Edge edge : node.connectedEdges) {
                Node otherNode = edge.other(node);
                if (visitedNodes.contains(otherNode)) continue;
                edgesToUpdate.add(edge);
                newNodesToUpdate.add(otherNode);
            }
        }

        for (Edge edge : edgesToUpdate) edge.update(dt);

        visitedNodes.clear();
        visitedNodes.addAll(nodesToUpdate);

        nodesToUpdate.clear();
        nodesToUpdate.addAll(newNodesToUpdate);
    }

    public void triggerUpdateWave(Vector3i pos) {
        nodesToUpdate.add(nodeMap.get(pos));
    }

    final class Node {
        C storage;
        final Set<Vector3i> blocks = new LinkedHashSet<>();
        final Set<Edge> connectedEdges = new HashSet<>();

        Node update(float dt, World world) {
            for (Edge edge : connectedEdges) {
                if (nodeMap.get(edge.from) == this) {
                    storage.del(edge.flux);
                } else {
                    storage.add(edge.flux);
                }
            }

            // Do whatever it gotta do...
            storage.tick(dt);

            // World update hook
            if (storage.requiresWorldUpdate()) {
                for (Vector3i pos : blocks) {
                    final Vector3i capturedPos = new Vector3i(pos);
                    final C capturedStorage = storage;
                    capturedStorage.onWorldUpdate(capturedPos, world);
                }
            }

            return this;
        }
    }

    final class Edge {
        final Vector3i from;
        final Vector3i to;
        C flux;

        Edge(Vector3i from, Vector3i to) {
            this.from = new Vector3i(from);
            this.to   = new Vector3i(to);
        }

        Edge update(float dt) {
            Node fromNode = nodeMap.get(from);
            Node toNode   = nodeMap.get(to);
            if (fromNode != null && toNode != null)
                flux = flux.calculateFlux(dt, fromNode.storage, toNode.storage);
            return this;
        }

        public Node other(Node node) {
            Node fromNode = nodeMap.get(from);
            Node toNode   = nodeMap.get(to);
            if (node == fromNode) return toNode;
            if (node == toNode)   return fromNode;
            return null;
        }

        public Vector3i other(Vector3i node) {
            if (node == from) return to;
            if (node == to)   return from;
            return null;
        }

        public boolean connectedTo(Node node) {
            return (nodeMap.get(this.to) == node) || (nodeMap.get(this.from) == node);
        }
    }

    public void onBlockPlaced(Vector3i origin, BlockType blockType, WorldChunk chunk, C storage) {
        // 1.   Check all positions occupied by the block.
        Set<Vector3i> occupiedSet = BlockUtil.getOccupiedPositions(blockType, origin, chunk);

        // 2.   Sanity-Check
        for (Vector3i p : occupiedSet) {
            if (nodeMap.containsKey(p))
                throw new IllegalStateException("Block position already occupied: " + p);
        }

        // 3.   Register new Node
        Node newNode = new Node();
        newNode.storage = storage;
        for (Vector3i p : occupiedSet) {
            newNode.blocks.add(new Vector3i(p));
            nodeMap.put(new Vector3i(p), newNode);
        }
        nodes.add(newNode);

        // 4.   Collect all external neighbouring nodes.
        //      Safe the first found contact point per neighbour Node:
        //      contacts[neighbour] = { ourContactBlock, theirContactBlock }
        Map<Node, Vector3i[]> neighbourContacts = new LinkedHashMap<>();
        for (Vector3i blockPos : occupiedSet) {
            for (Vector3i connPos : BlockUtil.getConnections(chunk, blockPos)) {
                if (occupiedSet.contains(connPos)) continue;
                Node neighbour = nodeMap.get(connPos);
                if (neighbour == null || neighbour == newNode) continue;
                neighbourContacts.putIfAbsent(neighbour,
                        new Vector3i[]{new Vector3i(blockPos), new Vector3i(connPos)});
            }
        }

        // 5.   For each neighbour: decide whether merge or edge.
        //      IMPORTANT: fetch newNode-Reference after each merge via nodeMap.get(origin).
        for (Map.Entry<Node, Vector3i[]> entry : neighbourContacts.entrySet()) {
            Node neighbour    = entry.getKey();
            Vector3i ourPos   = entry.getValue()[0];
            Vector3i theirPos = entry.getValue()[1];

            // After a previous merge, origin could point towards another Node. TODO: No, idk...??
            Node currentNew = nodeMap.get(origin);
            if (currentNew == null) break; // things like these snouldn't happen...

            // Neighbour could have been absorbed in previous merge.
            if (!nodes.contains(neighbour)) continue;

            if (currentNew.storage.shouldMerge(neighbour.storage)) {
                if (currentNew.storage.isPipe() && neighbour.storage.isPipe()) {
                    int newExternal       = countExternalConnections(currentNew.blocks, chunk);
                    int neighbourExternal = BlockUtil.getConnections(chunk, theirPos).size();

                    if (newExternal <= 2 && neighbourExternal <= 2) {
                        mergeInto(currentNew, neighbour);
                    } else {
                        if (neighbourExternal > 2) splitNode(theirPos, chunk);
                        addEdge(ourPos, theirPos, currentNew.storage);
                        Node refreshedNeighbour = nodeMap.get(theirPos);
                        if (refreshedNeighbour != null)
                            nodesToUpdate.add(refreshedNeighbour);
                    }
                } else {
                    // Tanks, for example.
                    mergeInto(currentNew, neighbour);
                }
            } else {
                // Different network component types.
                addEdge(ourPos, theirPos, currentNew.storage);
                nodesToUpdate.add(neighbour);
            }
        }

        // 6.   Trigger update wave.
        Node finalNew = nodeMap.get(origin);
        if (finalNew != null) nodesToUpdate.add(finalNew);

        runOnBlockAdded();
    }

    public List<BlockNetwork<C>> onBlockRemoved(Vector3i origin, BlockType blockType, WorldChunk chunk) {
        Node removedNode = nodeMap.get(origin);
        if (removedNode == null) return Collections.emptyList();

        // 1.   All positions occupied by the block.
        Set<Vector3i> occupiedSet = BlockUtil.getOccupiedPositions(blockType, origin, chunk);

        // 2.   Save affected neighbours before making any changes.
        Set<Node> affectedNeighbours = new LinkedHashSet<>();
        for (Vector3i blockPos : occupiedSet) {
            for (Vector3i offset : BlockUtil.FACE_OFFSETS) {
                Vector3i neighbourPos = new Vector3i(blockPos).add(offset);
                if (occupiedSet.contains(neighbourPos)) continue;
                Node nb = nodeMap.get(neighbourPos);
                if (nb != null && nb != removedNode) affectedNeighbours.add(nb);
            }
        }

        // 3.   Remove Nodes, three distinct cases:
        //
        //      Case A: Multiblock-Hitbox (occupiedSet.size() > 1)
        //              -> Remove all affected blocks from Node completely.
        //
        //      Case B: Single-Block Hitbox
        //              -> Remove Node completely.
        //
        //      Case C: Single-Block Hitbox, but Node contains multiple blocks
        //              -> Only delete this block and perform BFS for remaining blocks.

        if (occupiedSet.size() > 1 || removedNode.blocks.size() == 1) {
            // Case A + B
            removeNodeCompletely(removedNode);
        } else {
            // Case C
            splitRemovedBlockFromNode(origin, removedNode, affectedNeighbours, chunk);
        }

        // 4.   Remove invalid edges at neighbour
        //      (Edges that are pointing towards the removed Node).
        for (Node nb : affectedNeighbours) {
            nb.connectedEdges.removeIf(e -> {
                Node other = e.other(nb);
                return other == null || !nodes.contains(other);
            });
        }

        // 5.   Merge linear pipe Nodes (i.e. pipes that are not junctions).
        //      Candidates = affectedNeighbours + their neighbours
        Set<Node> mergeCandidates = new LinkedHashSet<>(affectedNeighbours);
        for (Node nb : affectedNeighbours) {
            for (Edge e : new ArrayList<>(nb.connectedEdges)) {
                Node secondDegree = e.other(nb);
                if (secondDegree != null) mergeCandidates.add(secondDegree);
            }
        }
        mergeLinearNeighbours(mergeCandidates, chunk);

        // 6.   Trigger update waves at affected neighbours.
        for (Node nb : affectedNeighbours) {
            if (nodes.contains(nb) && nb.storage != null)
                nodesToUpdate.add(nb);
        }

        // 7.   Test for coherency of the network.
        List<BlockNetwork<C>> splits = detectSplit();
        runOnBlockRemoved();
        return splits;
    }

    private void removeNodeCompletely(Node node) {
        for (Vector3i b : node.blocks) nodeMap.remove(b);
        for (Edge e : node.connectedEdges) {
            Node other = e.other(node);
            if (other != null) other.connectedEdges.remove(e);
        }
        node.connectedEdges.clear();
        nodes.remove(node);
    }

    /**
     * Removes {@code removedPos} from {@code oldNode} and separates the remainiing
     * blocks into separate spatial segments per BFS.
     */
    private void splitRemovedBlockFromNode(Vector3i removedPos, Node oldNode,
                                           Set<Node> affectedNeighbours, WorldChunk chunk) {
        nodeMap.remove(removedPos);
        oldNode.blocks.remove(removedPos);
        int totalOriginalSize = oldNode.blocks.size() + 1; // +1 weil removedPos bereits entfernt

        nodes.remove(oldNode);

        for (Vector3i b : oldNode.blocks) nodeMap.remove(b);

        Set<Vector3i> unvisited = new LinkedHashSet<>(oldNode.blocks);

        while (!unvisited.isEmpty()) {
            Node seg = buildSegment(unvisited);

            @SuppressWarnings("unchecked")
            C[] parts = oldNode.storage.partition(seg.blocks.size(), totalOriginalSize - seg.blocks.size());
            seg.storage = parts[0];

            for (Edge e : oldNode.connectedEdges) {
                boolean fromInSeg = seg.blocks.contains(e.from);
                boolean toInSeg   = seg.blocks.contains(e.to);
                if (!fromInSeg && !toInSeg) continue;

                seg.connectedEdges.add(e);

                Vector3i externalPos = fromInSeg ? e.to : e.from;
                Node otherNode = nodeMap.get(externalPos);
                if (otherNode != null) {
                    otherNode.connectedEdges.remove(e);
                    otherNode.connectedEdges.add(e);
                }
            }

            nodes.add(seg);
            affectedNeighbours.add(seg);
        }
    }

    private void mergeLinearNeighbours(Set<Node> candidates, WorldChunk chunk) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (Node node : new ArrayList<>(candidates)) {
                if (!nodes.contains(node)) continue;
                if (!node.storage.isPipe()) continue;

                int physicalConns = countPhysicalConnections(node.blocks, chunk);
                if (physicalConns > 2) continue;

                if (node.connectedEdges.size() > 2) continue;

                for (Edge e : new ArrayList<>(node.connectedEdges)) {
                    Node neighbour = e.other(node);
                    if (neighbour == null) continue;
                    if (!nodes.contains(neighbour)) continue;
                    if (!neighbour.storage.isPipe()) continue;

                    int neighbourPhysicalConns = countPhysicalConnections(neighbour.blocks, chunk);
                    if (neighbourPhysicalConns > 2) continue;

                    if (neighbour.connectedEdges.size() > 2) continue;

                    mergeInto(node, neighbour);
                    candidates.add(node);
                    changed = true;
                    break outer;
                }
            }
        }
    }

    private int countPhysicalConnections(Set<Vector3i> blockSet, WorldChunk chunk) {
        return (int) blockSet.stream()
                .flatMap(p -> BlockUtil.getConnections(chunk, p).stream())
                .filter(c -> !blockSet.contains(c))
                .distinct()
                .count();
    }

    private int countExternalConnections(Set<Vector3i> blockSet, WorldChunk chunk) {
        return (int) blockSet.stream()
                .flatMap(p -> BlockUtil.getConnections(chunk, p).stream())
                .filter(c -> !blockSet.contains(c))
                .distinct()
                .count();
    }

    private void mergeInto(Node target, Node absorbed) {
        target.storage = target.storage.mergeComponents(absorbed.storage);

        List<Edge> absorbedEdges = new ArrayList<>(absorbed.connectedEdges); // Snapshot
        for (Edge e : absorbedEdges) {
            if (e.other(absorbed) == target) {
                target.connectedEdges.remove(e);
                continue;
            }
            target.connectedEdges.add(e);

            Node other = e.other(absorbed);
            if (other != null) {
                other.connectedEdges.remove(e);
                other.connectedEdges.add(e);
            }
        }
        absorbed.connectedEdges.clear();

        for (Vector3i b : absorbed.blocks) {
            target.blocks.add(b);
            nodeMap.put(b, target);
        }
        nodes.remove(absorbed);
    }

    private void addEdge(Vector3i fromPos, Vector3i toPos, C storage) {
        Node a = nodeMap.get(fromPos);
        Node b = nodeMap.get(toPos);
        if (a == null || b == null) return;
        for (Edge existing : a.connectedEdges) {
            if (existing.other(a) == b) return;
        }
        Edge edge = new Edge(new Vector3i(fromPos), new Vector3i(toPos));
        edge.flux = storage.copy().zero(); // TODO: UGLY AF BUT STATIC METHODS AREN'T POSSIBLE WITH GENERICS ARHGRHGHG
        a.connectedEdges.add(edge);
        b.connectedEdges.add(edge);
    }

    private void splitNode(Vector3i junctionPos, WorldChunk chunk) {
        Node oldNode = nodeMap.get(junctionPos);
        if (oldNode == null) return;

        nodes.remove(oldNode);

        Node junctionNode = new Node();
        junctionNode.blocks.add(new Vector3i(junctionPos));
        junctionNode.storage = oldNode.storage.partition(1, oldNode.blocks.size() - 1)[0];
        nodeMap.put(new Vector3i(junctionPos), junctionNode);
        nodes.add(junctionNode);

        for (Edge e : oldNode.connectedEdges) {
            if (e.from.equals(junctionPos) || e.to.equals(junctionPos)) {
                junctionNode.connectedEdges.add(e);
            }
        }

        Set<Vector3i> unvisited = new LinkedHashSet<>(oldNode.blocks);
        unvisited.remove(junctionPos);

        for (Vector3i b : unvisited) nodeMap.remove(b);

        while (!unvisited.isEmpty()) {
            Vector3i seed = unvisited.iterator().next();
            unvisited.remove(seed);

            Node seg = new Node();
            seg.blocks.add(new Vector3i(seed));
            nodeMap.put(new Vector3i(seed), seg);

            Queue<Vector3i> bfs = new ArrayDeque<>();
            bfs.add(seed);
            while (!bfs.isEmpty()) {
                Vector3i cur = bfs.poll();
                for (Vector3i offset : BlockUtil.FACE_OFFSETS) {
                    Vector3i adj = new Vector3i(cur).add(offset);
                    if (unvisited.remove(adj)) {
                        seg.blocks.add(new Vector3i(adj));
                        nodeMap.put(new Vector3i(adj), seg);
                        bfs.add(adj);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            C[] parts = oldNode.storage.partition(seg.blocks.size(), oldNode.blocks.size() - seg.blocks.size());
            seg.storage = parts[0];

            for (Edge e : oldNode.connectedEdges) {
                if (junctionNode.connectedEdges.contains(e)) continue;
                if (seg.blocks.contains(e.from) || seg.blocks.contains(e.to)) {
                    seg.connectedEdges.add(e);
                }
            }

            boolean adjacentToJunction = false;
            Vector3i segBoundary = null;
            outer:
            for (Vector3i b : seg.blocks) {
                for (Vector3i offset : BlockUtil.FACE_OFFSETS) {
                    if (new Vector3i(b).add(offset).equals(junctionPos)) {
                        adjacentToJunction = true;
                        segBoundary = new Vector3i(b);
                        break outer;
                    }
                }
            }

            if (adjacentToJunction) {
                Edge newEdge = new Edge(new Vector3i(segBoundary), new Vector3i(junctionPos));
                newEdge.flux = oldNode.storage.zero();
                seg.connectedEdges.add(newEdge);
                junctionNode.connectedEdges.add(newEdge);
            }

            nodes.add(seg);
        }
    }

    private Node buildSegment(Set<Vector3i> unvisited) {
        Vector3i seed = unvisited.iterator().next();
        unvisited.remove(seed);

        Node seg = new Node();
        seg.blocks.add(new Vector3i(seed));
        nodeMap.put(new Vector3i(seed), seg);

        Queue<Vector3i> bfs = new ArrayDeque<>();
        bfs.add(seed);
        while (!bfs.isEmpty()) {
            Vector3i cur = bfs.poll();
            for (Vector3i offset : BlockUtil.FACE_OFFSETS) {
                Vector3i adj = new Vector3i(cur).add(offset);
                if (unvisited.remove(adj)) {
                    seg.blocks.add(new Vector3i(adj));
                    nodeMap.put(new Vector3i(adj), seg);
                    bfs.add(adj);
                }
            }
        }
        return seg;
    }

    private List<BlockNetwork<C>> detectSplit() {
        if (nodes.isEmpty()) return Collections.emptyList();

        Set<Node> visited = new HashSet<>();
        Queue<Node> bfs = new ArrayDeque<>();
        Node start = nodes.iterator().next();
        bfs.add(start);
        visited.add(start);
        while (!bfs.isEmpty()) {
            Node cur = bfs.poll();
            for (Edge e : cur.connectedEdges) {
                Node nb = e.other(cur);
                if (nb != null && visited.add(nb)) bfs.add(nb);
            }
        }

        if (visited.size() == nodes.size()) return Collections.emptyList();

        List<BlockNetwork<C>> splits = new ArrayList<>();
        Set<Node> remaining = new HashSet<>(nodes);
        remaining.removeAll(visited);
        nodes.retainAll(visited);

        while (!remaining.isEmpty()) {
            Set<Node> component = new HashSet<>();
            Queue<Node> q = new ArrayDeque<>();
            Node seed = remaining.iterator().next();
            q.add(seed);
            component.add(seed);
            while (!q.isEmpty()) {
                Node cur = q.poll();
                for (Edge e : cur.connectedEdges) {
                    Node nb = e.other(cur);
                    if (nb != null && component.add(nb)) q.add(nb);
                }
            }
            remaining.removeAll(component);

            BlockNetwork<C> newNetwork = factory.get();
            for (Node n : component) {
                newNetwork.nodes.add(n);
                for (Vector3i b : n.blocks) {
                    newNetwork.nodeMap.put(b, n);
                    nodeMap.remove(b);
                }
            }
            splits.add(newNetwork);
        }

        return splits;
    }

    public boolean containsBlock(Vector3i pos) {
        return nodeMap.containsKey(pos);
    }

    public boolean isAdjacentTo(Vector3i pos) {
        for (int i = 0; i < BlockUtil.FACE_OFFSETS.length; i++) {
            if (nodeMap.containsKey(new Vector3i(pos).add(BlockUtil.FACE_OFFSETS[i]))) return true;
        }
        return false;
    }

    public boolean isEmpty() { return nodes.isEmpty() && nodeMap.isEmpty(); }

    public C getComponent(Vector3i vec) {
        Node n = nodeMap.get(vec);
        return n != null ? n.storage : null;
    }

    public void clear() {
        nodeMap.clear();
        nodes.forEach(node -> node.connectedEdges.clear());
        nodes.clear();
        nodesToUpdate.clear();
    }

    public abstract void runOnBlockAdded();
    public abstract void runOnBlockRemoved();
    protected abstract BuilderCodec<C> getComponentCodec();

    public NodeDTO<C>[] serializeNodes() {
        @SuppressWarnings("unchecked")
        NodeDTO<C>[] result = new NodeDTO[nodes.size()];
        int i = 0;
        for (Node node : nodes) {
            NodeDTO<C> dto = new NodeDTO<>();
            dto.blocks  = node.blocks.toArray(new Vector3i[0]);
            dto.storage = node.storage;
            result[i++] = dto;
        }
        return result;
    }

    public EdgeDTO<C>[] serializeEdges() {
        Set<Edge> seen = new HashSet<>();
        List<EdgeDTO<C>> result = new ArrayList<>();
        for (Node node : nodes) {
            for (Edge edge : node.connectedEdges) {
                if (seen.add(edge)) {
                    EdgeDTO<C> dto = new EdgeDTO<>();
                    dto.from = edge.from;
                    dto.to   = edge.to;
                    dto.flux = edge.flux;
                    result.add(dto);
                }
            }
        }
        @SuppressWarnings("unchecked")
        EdgeDTO<C>[] array = new EdgeDTO[result.size()];
        return result.toArray(array);
    }

    public void deserializeNodes(NodeDTO<C>[] nodeDTOs) {
        clear();
        for (NodeDTO<C> dto : nodeDTOs) {
            Node node = new Node();
            node.storage = dto.storage;
            for (Vector3i p : dto.blocks) {
                node.blocks.add(p);
                nodeMap.put(p, node);
            }
            nodes.add(node);
        }
    }

    public void deserializeEdges(EdgeDTO<C>[] edgeDTOs) {
        for (EdgeDTO<C> dto : edgeDTOs) {
            Edge edge = new Edge(dto.from, dto.to);
            edge.flux = dto.flux;
            Node fromNode = nodeMap.get(dto.from);
            Node toNode   = nodeMap.get(dto.to);
            if (fromNode != null) fromNode.connectedEdges.add(edge);
            if (toNode   != null) toNode.connectedEdges.add(edge);
        }
    }

    public void mergeFrom(BlockNetwork<C> other) {
        NodeDTO<C>[] otherNodes = other.serializeNodes();
        EdgeDTO<C>[] otherEdges = other.serializeEdges();
        NodeDTO<C>[] thisNodes  = serializeNodes();
        EdgeDTO<C>[] thisEdges  = serializeEdges();

        @SuppressWarnings("unchecked")
        NodeDTO<C>[] mergedNodes = new NodeDTO[thisNodes.length + otherNodes.length];
        System.arraycopy(thisNodes,  0, mergedNodes, 0,                thisNodes.length);
        System.arraycopy(otherNodes, 0, mergedNodes, thisNodes.length, otherNodes.length);

        @SuppressWarnings("unchecked")
        EdgeDTO<C>[] mergedEdges = new EdgeDTO[thisEdges.length + otherEdges.length];
        System.arraycopy(thisEdges,  0, mergedEdges, 0,                thisEdges.length);
        System.arraycopy(otherEdges, 0, mergedEdges, thisEdges.length, otherEdges.length);

        deserializeNodes(mergedNodes);
        deserializeEdges(mergedEdges);
    }

    public static <C extends BlockNetworkComponent<C>, R extends BlockNetwork<C>>
    BuilderCodec<R> createCodec(Class<R> clazz, Supplier<R> factory, BuilderCodec<C> componentCodec) {

        BuilderCodec<NodeDTO<C>> nodeCodec = NodeDTO.codec(componentCodec);
        BuilderCodec<EdgeDTO<C>> edgeCodec = EdgeDTO.codec(componentCodec);

        @SuppressWarnings("unchecked")
        ArrayCodec<NodeDTO<C>> nodeArrayCodec = (ArrayCodec<NodeDTO<C>>) (Object)
                ArrayCodec.ofBuilderCodec(nodeCodec, NodeDTO[]::new);

        @SuppressWarnings("unchecked")
        ArrayCodec<EdgeDTO<C>> edgeArrayCodec = (ArrayCodec<EdgeDTO<C>>) (Object)
                ArrayCodec.ofBuilderCodec(edgeCodec, EdgeDTO[]::new);

        return BuilderCodec
                .builder(clazz, factory)
                .append(new KeyedCodec<>("Nodes", nodeArrayCodec),
                        (r, v) -> r.deserializeNodes(v), r -> r.serializeNodes()).add()
                .append(new KeyedCodec<>("Edges", edgeArrayCodec),
                        (r, v) -> r.deserializeEdges(v), r -> r.serializeEdges()).add()
                .build();
    }
}