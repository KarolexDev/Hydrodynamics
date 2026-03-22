package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.karolex.hydrodynamics.util.BlockUtil;
import com.karolex.hydrodynamics.blocknetwork.BlockNetworkSerialization.*;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.karolex.hydrodynamics.util.UniqueSchedule;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Class carrying all of the network modification logic.
 *
 * @param <C>
 */
public abstract class BlockNetwork<C extends BlockNetworkComponent<C>> {

    public static final String DEFAULT_CONNECTION_TYPE = "Default";

    private final Map<Vector3i, Node> nodeMap = new HashMap<>();
    private final Set<Node> nodes = new HashSet<>();
    private final Supplier<BlockNetwork<C>> factory;

    protected BlockNetwork(World world, Supplier<BlockNetwork<C>> factory) {
        this.world = world;
        this.factory = factory;
    }

    public long tick_counter = 0;
    private final UniqueSchedule<Node, Long> schedule = new UniqueSchedule<>();
    private final HashSet<Node> visitedNodes = new HashSet<>();

    private final World world;

    void tick() {
        Set<Node> newVisitedNodes = new HashSet<>();
        Set<Node> nextWave = new HashSet<>();
        Set<Edge> updatedEdges = new HashSet<>();
        Map<Node, Long> pendingInserts = new LinkedHashMap<>(); // Instant → Long

        while (!schedule.isEmpty() && schedule.peekEarliestTimestamp() != null && schedule.peekEarliestTimestamp().compareTo(tick_counter) <= 0) { // isAfter → <=
            Node node = schedule.pollEarliest();
            newVisitedNodes.add(node);
            node.update(world);

            List<Map.Entry<String, C>> incomingFlux = new ArrayList<>();
            List<Map.Entry<String, C>> outgoingFlux = new ArrayList<>();
            List<Edge> outgoingEdges = new ArrayList<>();

            for (Edge edge : node.connectedEdges) {
                Node otherNode = edge.other(node);
                if (otherNode == null) continue;

                String connType = getConnectionType(node, edge);

                // Flux aus Sicht dieses Nodes: positiv = verlässt diesen Node
                boolean thisIsFrom = (nodeMap.get(edge.from) == node);
                C perspectiveFlux = thisIsFrom ? edge.flux : edge.flux.negate();

                if (visitedNodes.contains(otherNode)) {
                    incomingFlux.add(Map.entry(connType, perspectiveFlux));
                } else {
                    nextWave.add(otherNode);
                    outgoingFlux.add(Map.entry(connType, perspectiveFlux));
                    outgoingEdges.add(edge);
                }
            }
            List<C> result = node.storage.divideFluxFlow(incomingFlux, outgoingFlux);

            // TODO: THIS DOESN'T WORK. TANKS DON'T GET FILLED UP AND FOR SOME REASON THE FLUXES ARE ALL FUCKED UP AGHFGHAGHAGHAG...
            if (incomingFlux.size() == 1) outgoingFlux = incomingFlux; // Temp. fix

            for (int i = 0; i < outgoingEdges.size(); i++) {
                C flux_cap = result.get(i);
                Edge e = outgoingEdges.get(i);
                e.update(flux_cap);
            }

            if (!node.storage.fluxAmountsToZero(incomingFlux, outgoingFlux)) {
                Integer delay = node.storage.computeDelay(incomingFlux, outgoingFlux);
                if (delay != null) pendingInserts.put(node, tick_counter + (long) delay); // now.plusMillis → tick_counter +
            }
        }

        for (Map.Entry<Node, Long> entry : pendingInserts.entrySet()) // Instant → Long
            schedule.insert(entry.getKey(), entry.getValue());
        for (Node node : nextWave) schedule.insert(node, tick_counter + 1L); // now → tick_counter + 1

        visitedNodes.clear();
        visitedNodes.addAll(newVisitedNodes);

        ++tick_counter;
    }

    void triggerUpdateWave(Node node) {
        if (node == null) return;
        visitedNodes.remove(node);
        schedule.insert(node, 0L); // Instant.EPOCH → 0L (immer frühestmöglich)
    }

    String getConnectionType(Node node, Edge edge) {
        if (nodeMap.get(edge.to) == node) return edge.toType;
        if (nodeMap.get(edge.from) == node) return edge.fromType;
        return null;
    }

    public void triggerUpdateWave(Vector3i pos) {
        triggerUpdateWave(nodeMap.get(pos));
    }

    final class Node {
        C storage;
        final Set<Vector3i> blocks = new LinkedHashSet<>();
        final Set<Edge> connectedEdges = new HashSet<>();
        Instant lastUpdated;

        Node(Instant timeOfCreation) { lastUpdated = timeOfCreation; }

        void update(World world) {
            for (Edge edge : connectedEdges) {
                if (nodeMap.get(edge.from) == this) {
                    storage.del(edge.flux);
                } else {
                    storage.add(edge.flux);
                }
                // edge.flux = edge.flux.zero();
            }

            storage.tick();

            // World update hook
            if (storage.requiresWorldUpdate()) {
                for (Vector3i pos : blocks) {
                    final Vector3i capturedPos = new Vector3i(pos);
                    final C capturedStorage = storage;
                    capturedStorage.onWorldUpdate(capturedPos, world);
                }
            }
        }
    }

    final class Edge {
        final Vector3i from;
        final String fromType;

        final Vector3i to;
        final String toType;
        C flux;

        Edge(Vector3i from, Vector3i to, String fromType, String toType) {
            this.from = new Vector3i(from);
            this.fromType = fromType;
            this.to   = new Vector3i(to);
            this.toType = toType;
        }

        Edge update(C fluxCap) {
            Node fromNode = nodeMap.get(from);
            Node toNode   = nodeMap.get(to);
            if (fromNode != null && toNode != null)
                flux = flux.calculateFlux(fluxCap, fromNode.storage, toNode.storage, fromType, toType);
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
        Node newNode = new Node(world.getEntityStore()
                .getStore()
                .getResource(TimeResource.getResourceType()).getNow());
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
                        addEdge(chunk, ourPos, theirPos, currentNew.storage);
                        Node refreshedNeighbour = nodeMap.get(theirPos);
                        if (refreshedNeighbour != null)
                            triggerUpdateWave(refreshedNeighbour);
                    }
                } else {
                    // Tanks, for example.
                    mergeInto(currentNew, neighbour);
                }
            } else {
                // Different network component types.
                addEdge(chunk, ourPos, theirPos, currentNew.storage);
                triggerUpdateWave(neighbour);
            }
        }

        // 6.   Trigger update wave.
        Node finalNew = nodeMap.get(origin);
        if (finalNew != null) triggerUpdateWave(finalNew);

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
                triggerUpdateWave(nb);
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

    private void addEdge(WorldChunk chunk, Vector3i fromPos, Vector3i toPos, C storage) {
        Node a = nodeMap.get(fromPos);
        Node b = nodeMap.get(toPos);
        if (a == null || b == null) return;
        for (Edge existing : a.connectedEdges) {
            if (existing.other(a) == b) return;
        }

        String fromType;
        Map<Vector3i, String> connectionPointsA = a.storage.getConnectionPoints();
        if (connectionPointsA == null) {
            fromType = DEFAULT_CONNECTION_TYPE;
        } else {
            @SuppressWarnings("removal")
            Map<Vector3i, String> rotatedA = BlockUtil.rotateConnectionPoints(
                    connectionPointsA, chunk.getRotation(fromPos.x, fromPos.y, fromPos.z));
            Vector3i dirAtoB = new Vector3i(toPos.x - fromPos.x, toPos.y - fromPos.y, toPos.z - fromPos.z);
            fromType = rotatedA.get(dirAtoB);
        }

        String toType;
        Map<Vector3i, String> connectionPointsB = b.storage.getConnectionPoints();
        if (connectionPointsB == null) {
            toType = DEFAULT_CONNECTION_TYPE;
        } else {
            @SuppressWarnings("removal")
            Map<Vector3i, String> rotatedB = BlockUtil.rotateConnectionPoints(
                    connectionPointsB, chunk.getRotation(toPos.x, toPos.y, toPos.z));
            Vector3i dirBtoA = new Vector3i(fromPos.x - toPos.x, fromPos.y - toPos.y, fromPos.z - toPos.z);
            toType = rotatedB.get(dirBtoA);
        }

        Edge edge = new Edge(new Vector3i(fromPos), new Vector3i(toPos), fromType, toType);
        edge.flux = storage.copy().zero();
        a.connectedEdges.add(edge);
        b.connectedEdges.add(edge);
    }

    private void splitNode(Vector3i junctionPos, WorldChunk chunk) {
        Node oldNode = nodeMap.get(junctionPos);
        if (oldNode == null) return;

        nodes.remove(oldNode);

        Node junctionNode = new Node(world.getEntityStore()
                .getStore()
                .getResource(TimeResource.getResourceType()).getNow());
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

            Node seg = new Node(world.getEntityStore()
                    .getStore()
                    .getResource(TimeResource.getResourceType()).getNow());
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
                Edge newEdge = new Edge(new Vector3i(segBoundary), new Vector3i(junctionPos), DEFAULT_CONNECTION_TYPE, DEFAULT_CONNECTION_TYPE);
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

        Node seg = new Node(world.getEntityStore()
                .getStore()
                .getResource(TimeResource.getResourceType()).getNow());
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
        schedule.clear();
        visitedNodes.clear();
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
                    dto.from     = edge.from;
                    dto.fromType = edge.fromType;
                    dto.to       = edge.to;
                    dto.toType   = edge.toType;
                    dto.flux     = edge.flux;
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
            Node node = new Node(world.getEntityStore()
                    .getStore()
                    .getResource(TimeResource.getResourceType()).getNow());
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
            Edge edge = new Edge(dto.from, dto.to, dto.fromType, dto.toType);
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