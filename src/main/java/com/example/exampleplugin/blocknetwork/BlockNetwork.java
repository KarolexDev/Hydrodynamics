package com.example.exampleplugin.blocknetwork;

import com.example.exampleplugin.util.BlockFaceEnum;
import com.example.exampleplugin.blocknetwork.BlockNetworkSerialization.*;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public abstract class BlockNetwork<C extends BlockNetworkComponent<C>> {

    private final Map<Vector3i, Node> nodeMap = new HashMap<>();
    private final Set<Node> nodes = new HashSet<>();
    private final Supplier<BlockNetwork<C>> factory;

    protected BlockNetwork(Supplier<BlockNetwork<C>> factory) {
        this.factory = factory;
    }

    // ─── UpdateWave ──────────────────────────────────────────────────────────

    public class UpdateWave {

        private class Wavefront {
            private Node current;
            private Node cameFrom;
            float remainingTime;

            private Wavefront(Node current, Node cameFrom, float remainingTime) {
                this.current = current;
                this.cameFrom = cameFrom;
                this.remainingTime = remainingTime;
            }
        }

        private final List<Wavefront> wavefronts = new CopyOnWriteArrayList<>();

        public UpdateWave(Node startNode) {
            wavefronts.add(new Wavefront(startNode, null, 0));
        }

        public void tick(float dt) {
            List<Wavefront> next = new ArrayList<>();

            for (Wavefront wf : wavefronts) {
                wf.remainingTime -= dt;
                if (wf.remainingTime > 0) {
                    next.add(wf);
                    continue;
                }

                float changeRate = 0f;
                if (wf.current.storage != null) {
                    C storageBefore = wf.current.storage.copy();
                    wf.current.update(dt);
                    changeRate = storageBefore.changeRate(wf.current.storage);
                }

                for (Edge edge : wf.current.connectedEdges) {
                    Node neighbor = edge.other(wf.current);
                    if (neighbor == null || neighbor.equals(wf.cameFrom)) continue;

                    edge.update(dt);
                    next.add(new Wavefront(neighbor, wf.current, neighbor.computeDelay(changeRate)));
                }
            }

            wavefronts.clear();
            wavefronts.addAll(next);
        }

        public boolean isFinished() {
            return wavefronts.isEmpty();
        }
    }

    private final List<UpdateWave> activeWaves = new ArrayList<>();

    // ─── tick ────────────────────────────────────────────────────────────────

    public void tick(float dt, World world) {
        if (world == null) return;

        for (UpdateWave wave : activeWaves) {
            wave.tick(dt);
        }
        activeWaves.removeIf(UpdateWave::isFinished);

        for (Node node : nodes) {
            if (node.triggersUpdates()) {
                activeWaves.add(new UpdateWave(node));
            }

            if (node.storage.requiresWorldUpdate()) {
                for (Vector3i pos : node.blocks) {
                    final Vector3i capturedPos = new Vector3i(pos);
                    final C capturedStorage = node.storage;
                    world.execute(() -> capturedStorage.onWorldUpdate(capturedPos, world));
                }
            }
        }
    }

    // ─── Node ────────────────────────────────────────────────────────────────

    final class Node {
        C storage;
        final Set<Vector3i> blocks = new LinkedHashSet<>();
        final Set<Edge> connectedEdges = new HashSet<>();

        Node update(float dt) {
            for (Edge edge : connectedEdges) {
                if (nodeMap.get(edge.from) == this) {
                    storage.del(edge.flux);
                } else {
                    storage.add(edge.flux);
                }
            }
            return this;
        }

        public boolean triggersUpdates() { return false; }
        public int getUpdateTickRate()   { return 1; }

        public float computeDelay(float changeRate) {
            return storage.computeDelay(changeRate);
        }
    }

    // ─── Edge ────────────────────────────────────────────────────────────────

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
                flux.calculateFlux(fromNode.storage, toNode.storage);
            return this;
        }

        public Node other(Node node) {
            Node fromNode = nodeMap.get(from);
            Node toNode   = nodeMap.get(to);
            if (node == fromNode) return toNode;
            if (node == toNode)   return fromNode;
            return null;
        }
    }

    // ─── onBlockPlaced ───────────────────────────────────────────────────────
    // SEEMS RIGHT??
    public void onBlockPlaced(Vector3i pos, List<Vector3i> connections, WorldChunk chunk, C storage) {

        if (nodeMap.containsKey(pos)) {
            throw new IllegalStateException("Block position already occupied: " + pos);
        }

        Node newNode = new Node();
        newNode.blocks.add(new Vector3i(pos));
        newNode.storage = storage;
        nodeMap.put(new Vector3i(pos), newNode);
        nodes.add(newNode);

        Set<Node> waveTriggers = new LinkedHashSet<>();
        waveTriggers.add(newNode);

        for (Vector3i connPos : connections) {
            Node neighbour = nodeMap.get(connPos);
            if (neighbour == null || neighbour == newNode) continue;

            // Hat der Nachbar seinerseits eine Verbindung zurück?
            List<Vector3i> neighbourConnections = BlockFaceEnum.getConnections(chunk, connPos);
            boolean connected = neighbourConnections.stream().anyMatch(nc -> nc.equals(pos));
            if (!connected) continue;

            // Aktuellen newNode neu auflösen, da er durch einen Merge ersetzt worden sein könnte
            newNode = nodeMap.get(pos);

            if (storage.shouldMerge(neighbour.storage)) {
                if (storage.isPipe() && neighbour.storage.isPipe()) {
                    if (connections.size() <= 2 && neighbourConnections.size() <= 2) {
                        mergeInto(neighbour, newNode);
                        newNode = nodeMap.get(pos);
                        waveTriggers.clear();
                        waveTriggers.add(newNode);
                    } else {
                        // Junction → Nachbar splitten falls er selbst ein Pipe-Node ist
                        if (neighbourConnections.size() > 2) {
                            splitNode(connPos, chunk);
                        }
                        addEdge(pos, connPos, storage);
                        waveTriggers.add(nodeMap.get(connPos));
                    }
                } else {
                    mergeInto(neighbour, newNode);
                    newNode = nodeMap.get(pos);
                    waveTriggers.clear();
                    waveTriggers.add(newNode);
                }
            } else {
                addEdge(pos, connPos, storage);
                waveTriggers.add(neighbour);
            }
        }

        for (Node trigger : waveTriggers) activeWaves.add(new UpdateWave(trigger));
        runOnBlockAdded();
    }

    // ─── onBlockRemoved ──────────────────────────────────────────────────────

    public List<BlockNetwork<C>> onBlockRemoved(Vector3i pos, WorldChunk chunk) {

        Node removedNode = nodeMap.remove(pos);
        if (removedNode == null) return Collections.emptyList();
        removedNode.blocks.remove(pos);
        nodes.remove(removedNode);

        Set<Node> affectedNeighbours = new LinkedHashSet<>();
        for (Edge e : removedNode.connectedEdges) {
            Node neighbour = e.other(removedNode);
            if (neighbour != null) {
                neighbour.connectedEdges.remove(e);
                affectedNeighbours.add(neighbour);
            }
        }

        List<BlockNetwork<C>> splitNetworks = new ArrayList<>();
        Set<Node> waveTriggers = new LinkedHashSet<>();

        // Pipe-Nachbarn prüfen ob sie jetzt zusammengeführt werden können
        for (Node neighbour : affectedNeighbours) {
            if (neighbour.storage == null) continue;
            waveTriggers.add(neighbour);

            if (!neighbour.storage.isPipe()) continue;

            List<Vector3i> neighbourConns = BlockFaceEnum.getConnections(chunk, neighbour.blocks.iterator().next());
            if (neighbourConns.size() > 2) continue;

            List<Node> pipeNeighbours = neighbour.connectedEdges.stream()
                    .map(e -> e.other(neighbour))
                    .filter(n -> n != null && n.storage.isPipe())
                    .toList();

            if (pipeNeighbours.size() == 2) {
                Node a = pipeNeighbours.get(0);
                Node b = pipeNeighbours.get(1);
                List<Vector3i> aConns = BlockFaceEnum.getConnections(chunk, a.blocks.iterator().next());
                List<Vector3i> bConns = BlockFaceEnum.getConnections(chunk, b.blocks.iterator().next());

                if (a != b
                        && a.storage.shouldMerge(neighbour.storage)
                        && b.storage.shouldMerge(neighbour.storage)
                        && aConns.size() <= 2 && bConns.size() <= 2) {
                    nodes.remove(neighbour);
                    mergeInto(a, neighbour);
                    mergeInto(nodeMap.get(a.blocks.iterator().next()), b);
                    waveTriggers.remove(neighbour);
                    waveTriggers.add(nodeMap.get(a.blocks.iterator().next()));
                }
            }
        }

        // Split-Erkennung per BFS über Edge-Graph
        if (!nodes.isEmpty()) {
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

            if (visited.size() < nodes.size()) {
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
                        for (Vector3i b : n.blocks) newNetwork.nodeMap.put(b, n);
                    }
                    splitNetworks.add(newNetwork);
                }
            }
        }

        for (Node trigger : waveTriggers) activeWaves.add(new UpdateWave(trigger));
        runOnBlockRemoved();

        return splitNetworks;
    }

    // ─── Hilfsmethoden ───────────────────────────────────────────────────────
    // seems ok
    private void mergeInto(Node target, Node absorbed) {
        target.storage = target.storage.mergeComponents(absorbed.storage);
        for (Vector3i b : absorbed.blocks) {
            target.blocks.add(b);
            nodeMap.put(b, target); // Overwrites old map -> automatically deletes
        }
        for (Edge e : absorbed.connectedEdges) {
            if (e.other(absorbed) == target) continue; // interne Edge entfernen
            target.connectedEdges.add(e);
        }
        nodes.remove(absorbed);
    }

    private void addEdge(Vector3i fromPos, Vector3i toPos, C storageForFlux) {
        Node a = nodeMap.get(fromPos);
        Node b = nodeMap.get(toPos);
        if (a == null || b == null) return;
        for (Edge existing : a.connectedEdges) {
            if (existing.other(a) == b) return; // keine doppelten Edges
        }
        Edge edge = new Edge(new Vector3i(fromPos), new Vector3i(toPos));
        edge.flux = storageForFlux.zero();
        a.connectedEdges.add(edge);
        b.connectedEdges.add(edge);
    }

    private void splitNode(Vector3i junctionPos, WorldChunk chunk) {
        Node oldNode = nodeMap.get(junctionPos);
        if (oldNode == null) return;

        nodes.remove(oldNode);

        // Junction bekommt eigenen Node
        Node junctionNode = new Node();
        junctionNode.blocks.add(new Vector3i(junctionPos));
        junctionNode.storage = oldNode.storage.partition(1, oldNode.blocks.size())[0];
        nodeMap.put(new Vector3i(junctionPos), junctionNode);
        nodes.add(junctionNode);

        // Externe Edges die direkt an junctionPos hängen übernehmen
        for (Edge e : oldNode.connectedEdges) {
            if (e.from.equals(junctionPos) || e.to.equals(junctionPos)) {
                junctionNode.connectedEdges.add(e);
            }
        }

        // Verbleibende Blöcke des alten Nodes (ohne junctionPos) per BFS aufteilen
        Set<Vector3i> unvisited = new LinkedHashSet<>(oldNode.blocks);
        unvisited.remove(junctionPos);

        // nodeMap-Einträge der verbleibenden Blöcke erst freigeben
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
                for (Vector3i offset : BlockFaceEnum.FACE_OFFSETS) {
                    Vector3i adj = new Vector3i(cur).add(offset);
                    if (unvisited.remove(adj)) {
                        seg.blocks.add(new Vector3i(adj));
                        nodeMap.put(new Vector3i(adj), seg);
                        bfs.add(adj);
                    }
                }
            }

            // Storage proportional aufteilen
            @SuppressWarnings("unchecked")
            C[] parts = oldNode.storage.partition(seg.blocks.size(), oldNode.blocks.size());
            seg.storage = parts[0];

            // Externe Edges des alten Nodes die zu diesem Segment gehören übernehmen
            for (Edge e : oldNode.connectedEdges) {
                if (junctionNode.connectedEdges.contains(e)) continue;
                if (seg.blocks.contains(e.from) || seg.blocks.contains(e.to)) {
                    seg.connectedEdges.add(e);
                }
            }

            // Neue Edge zwischen Segment und Junction, falls räumlich angrenzend
            boolean adjacentToJunction = false;
            Vector3i segBoundary = null;
            outer:
            for (Vector3i b : seg.blocks) {
                for (Vector3i offset : BlockFaceEnum.FACE_OFFSETS) {
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

    // ─── Hilfsmethoden für Manager ───────────────────────────────────────────

    public boolean containsBlock(Vector3i pos) {
        return nodeMap.containsKey(pos);
    }

    public boolean isAdjacentTo(Vector3i pos) {
        for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
            if (nodeMap.containsKey(new Vector3i(pos).add(BlockFaceEnum.FACE_OFFSETS[i]))) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public C getComponent(Vector3i vec) {
        Node n = nodeMap.get(vec);
        return n != null ? n.storage : null;
    }

    // ─── clear ───────────────────────────────────────────────────────────────

    public void clear() {
        nodeMap.clear();
        nodes.forEach(node -> node.connectedEdges.clear());
        nodes.clear();
        activeWaves.clear();
    }

    // ─── Abstrakte Methoden ──────────────────────────────────────────────────

    public abstract void runOnBlockAdded();
    public abstract void runOnBlockRemoved();
    protected abstract BuilderCodec<C> getComponentCodec();

    // ─── Serialisierung ──────────────────────────────────────────────────────

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