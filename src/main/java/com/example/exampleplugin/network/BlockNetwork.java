package com.example.exampleplugin.network;

import com.example.exampleplugin.util.BlockFaceEnum;
import com.example.exampleplugin.util.BlockNetworkSerialization.*;
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

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BlockNetwork[nodes=").append(nodes.size()).append("] {\n");
        for (Node node : nodes) {
            sb.append("  Node{blocks=").append(node.blocks.size())
                    .append(", edges=").append(node.connectedEdges.size())
                    .append(", storage=").append(node.storage)
                    .append(", positions=").append(node.blocks)
                    .append("}\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private final Map<Vector3i, Node> nodeMap = new HashMap<>();
    private final Set<Node> nodes = new HashSet<>();

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

                // Node updaten und Änderung messen
                // TODO: CORRECT LOGIC
                float changeRate = 0f;
                C storageBefore = wf.current.storage.copy();
                if (storageBefore != null) {
                    wf.current.update(dt);
                    changeRate = storageBefore.changeRate(wf.current.storage);
                }

                // Zu Nachbarn propagieren
                for (Edge edge : wf.current.connectedEdges) {
                    Node neighbor = edge.other(wf.current);
                    if (neighbor == null) { continue; } // TODO: INELEGANT!
                    if (neighbor.equals(wf.cameFrom)) continue;

                    edge.update(dt);

                    // Nachbar bestimmt selbst wie lange er warten will
                    float delay = neighbor.computeDelay(changeRate);
                    next.add(new Wavefront(neighbor, wf.current, delay));
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

    private final class Node {
        private C storage;
        private final Set<Vector3i> blocks = new LinkedHashSet<>();
        private final Set<Edge> connectedEdges = new HashSet<>();

        private long lastUpdateTick = -1;
        private long currentTick = 0;

        Node update(double dt) {
            for (Edge edge : connectedEdges) {
                if (nodeMap.get(edge.from) == this) {
                    storage.del(edge.flux);
                } else if (nodeMap.get(edge.to) == this) {
                    storage.add(edge.flux);
                }
            }
            return this;
        }

        public double getAndUpdateTimestamp() {
            long prev = lastUpdateTick;
            lastUpdateTick = currentTick++;
            return prev < 0 ? 0.0 : (double)(lastUpdateTick - prev);
        }

        public int tickDelay(Node cameFrom) { return 0; }

        /** Soll dieser Node Update-Wellen auslösen? */
        public boolean triggersUpdates() { return false; }

        /** Alle wieviele Ticks soll eine neue Welle ausgelöst werden? */
        public int getUpdateTickRate() { return 1; }

        public float computeDelay(float changeRate) {
            return storage.computeDelay(changeRate);
        }
    }

    private final class Edge {
        private final Vector3i from;
        private final Vector3i to;
        private long lastUpdateTick = -1;
        private long currentTick = 0;

        private C flux; // storage packets per tick

        private Edge(Vector3i from, Vector3i vector3i) {
            this.from = from;
            to = vector3i;
        }

        Edge update(double dt) {
            flux.calculateFlux(nodeMap.get(from).storage, nodeMap.get(to).storage);
            return this;
        }

        public Node other(Node node) {
            Node fromNode = nodeMap.get(from);
            Node toNode = nodeMap.get(to);
            return node.equals(fromNode) ? toNode : fromNode;
        }

        public double getAndUpdateTimestamp() {
            long prev = lastUpdateTick;
            lastUpdateTick = currentTick++;
            return prev < 0 ? 0.0 : (double)(lastUpdateTick - prev);
        }

        public long timeDelay(Node cameFrom) { return 0; }
    }

    public void onBlockPlaced(Vector3i pos, WorldChunk chunk, C storage) {
        byte mask = BlockFaceEnum.readFromWorld(chunk, pos);

        List<Vector3i> neighbourPositions = new ArrayList<>();
        List<Node>     neighbourNodes     = new ArrayList<>();
        List<Byte>     neighbourMasks     = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            if ((mask & BlockFaceEnum.FACE_BITS[i]) == 0) continue;

            Vector3i neighbourPos  = new Vector3i(pos).add(BlockFaceEnum.FACE_OFFSETS[i]);
            Node     neighbourNode = nodeMap.get(neighbourPos);
            if (neighbourNode == null) continue;

            byte neighbourMask = BlockFaceEnum.readFromWorld(chunk, neighbourPos);
            if ((neighbourMask & BlockFaceEnum.opposite(BlockFaceEnum.FACE_BITS[i])) == 0) continue;

            neighbourPositions.add(neighbourPos);
            neighbourNodes.add(neighbourNode);
            neighbourMasks.add(neighbourMask);
        }

        Set<Node> waveTriggers = new LinkedHashSet<>();
        Set<Node> distinctNeighbourNodes = new LinkedHashSet<>(neighbourNodes);

        if (distinctNeighbourNodes.isEmpty()) {
            // Isolierter Block → neuer Node
            waveTriggers.add(registerNewNode(pos, storage));

        } else if (distinctNeighbourNodes.size() == 1) {
            Node target             = distinctNeighbourNodes.iterator().next();
            Vector3i actualNeighbourPos  = neighbourPositions.getFirst();
            byte     actualNeighbourMask = neighbourMasks.getFirst();

            if (Integer.bitCount(actualNeighbourMask & 0xFF) <= 2) {
                // Einfache Pipe-Verlängerung → Block dem bestehenden Node hinzufügen
                target.blocks.add(pos);
                target.storage.add(storage);
                nodeMap.put(pos, target);
                waveTriggers.add(target);
            } else {
                // Nachbar wird Junction → bestehenden Node splitten
                splitNode(target, actualNeighbourPos, chunk);

                Node newNode = registerNewNode(pos, storage);
                Node resolvedNeighbour = nodeMap.get(actualNeighbourPos);
                Edge edge = new Edge(new Vector3i(pos), new Vector3i(actualNeighbourPos));
                edge.flux = storage.zero();
                newNode.connectedEdges.add(edge);
                resolvedNeighbour.connectedEdges.add(edge);
                waveTriggers.add(newNode);
                waveTriggers.add(resolvedNeighbour);
            }

        } else if (distinctNeighbourNodes.size() == 2) {
            // Zwei verschiedene Nachbar-Nodes → zusammenführen
            Iterator<Node> it = distinctNeighbourNodes.iterator();
            mergeNodes(it.next(), it.next(), pos, storage);
            waveTriggers.add(nodeMap.get(pos));

        } else {
            // Junction: neuer Node mit Edges zu allen Nachbarn
            Node newNode = registerNewNode(pos, storage);
            waveTriggers.add(newNode);

            for (int i = 0; i < neighbourPositions.size(); i++) {
                Vector3i neighbourPos  = neighbourPositions.get(i);
                Node     neighbour     = neighbourNodes.get(i);
                byte     neighbourMask = neighbourMasks.get(i);

                if (Integer.bitCount(neighbourMask & 0xFF) > 2) {
                    splitNode(neighbour, neighbourPos, chunk);
                }

                Node resolvedNeighbour = nodeMap.get(neighbourPos);
                Edge edge = new Edge(new Vector3i(pos), new Vector3i(neighbourPos));
                edge.flux = storage.zero();
                newNode.connectedEdges.add(edge);
                resolvedNeighbour.connectedEdges.add(edge);
                waveTriggers.add(resolvedNeighbour);
            }
        }

        for (Node trigger : waveTriggers) {
            activeWaves.add(new UpdateWave(trigger));
        }
        runOnBlockAdded();
    }

    public void onBlockRemoved(Vector3i pos, WorldChunk chunk) {
        Node removedNode = nodeMap.remove(pos);
        if (removedNode == null) return;

        removedNode.blocks.remove(pos);

        // Alle Edges die diesen Block berühren sammeln und von Nachbarn entfernen
        Set<Edge> removedEdges = new HashSet<>();
        Set<Node> affectedNeighbours = new LinkedHashSet<>();
        for (Edge e : removedNode.connectedEdges) {
            if (e.from.equals(pos) || e.to.equals(pos)) {
                removedEdges.add(e);
                Node neighbour = e.other(removedNode);
                if (neighbour != null) {
                    neighbour.connectedEdges.remove(e);
                    affectedNeighbours.add(neighbour);
                }
            }
        }
        removedNode.connectedEdges.removeAll(removedEdges);

        nodes.remove(removedNode);

        Set<Node> waveTriggers = new LinkedHashSet<>();

        // Verbleibende Blöcke des alten Nodes per BFS in Segmente aufteilen
        if (!removedNode.blocks.isEmpty()) {
            // Temporär aus nodeMap entfernen für saubere BFS
            for (Vector3i b : removedNode.blocks) nodeMap.remove(b);

            Set<Vector3i> unvisited = new LinkedHashSet<>(removedNode.blocks);
            while (!unvisited.isEmpty()) {
                Vector3i seed = unvisited.iterator().next();
                unvisited.remove(seed);

                Node seg = new Node();
                seg.blocks.add(seed);
                nodeMap.put(seed, seg);

                Queue<Vector3i> bfs = new ArrayDeque<>();
                bfs.add(seed);
                while (!bfs.isEmpty()) {
                    Vector3i cur = bfs.poll();
                    for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
                        Vector3i adj = new Vector3i(cur).add(BlockFaceEnum.FACE_OFFSETS[i]);
                        if (unvisited.remove(adj)) {
                            seg.blocks.add(adj);
                            nodeMap.put(adj, seg);
                            bfs.add(adj);
                        }
                    }
                }

                @SuppressWarnings("unchecked")
                C[] parts = removedNode.storage.partition(seg.blocks.size(), removedNode.blocks.size());
                seg.storage = parts[0];

                // Edges des alten Nodes die zu diesem Segment gehören übernehmen
                for (Edge e : removedNode.connectedEdges) {
                    if (seg.blocks.contains(e.from) || seg.blocks.contains(e.to))
                        seg.connectedEdges.add(e);
                }

                nodes.add(seg);
                waveTriggers.add(seg);
            }
        }

        // Betroffene Nachbarn prüfen: ggf. zusammenführen wenn Junction weggefallen
        for (Node neighbour : affectedNeighbours) {
            // Nachbar könnte inzwischen durch BFS oben ersetzt worden sein
            Vector3i representativePos = neighbour.blocks.iterator().next();
            Node currentNeighbour = nodeMap.get(representativePos);
            if (currentNeighbour == null) continue;

            waveTriggers.add(currentNeighbour);

            byte neighbourMask = BlockFaceEnum.readFromWorld(chunk, representativePos);
            int connectionCount = Integer.bitCount(neighbourMask & 0xFF);

            // Hatte der Nachbar genau 2 Verbindungen übrig → war Junction, ist jetzt Pipe
            // → prüfen ob die zwei verbleibenden Nachbarn zusammengeführt werden können
            if (connectionCount != 2) continue;

            Node pipeNeighbourA = null;
            Node pipeNeighbourB = null;
            for (int i = 0; i < BlockFaceEnum.FACE_BITS.length; i++) {
                if ((neighbourMask & BlockFaceEnum.FACE_BITS[i]) == 0) continue;
                Vector3i nPos = new Vector3i(representativePos).add(BlockFaceEnum.FACE_OFFSETS[i]);
                Node n = nodeMap.get(nPos);
                if (n == null || n == currentNeighbour) continue;
                if (pipeNeighbourA == null) pipeNeighbourA = n;
                else pipeNeighbourB = n;
            }

            if (pipeNeighbourA != null && pipeNeighbourB != null && pipeNeighbourA != pipeNeighbourB) {
                mergeNodes(pipeNeighbourA, pipeNeighbourB, representativePos, currentNeighbour.storage);
                Node mergedNode = nodeMap.get(representativePos);
                waveTriggers.remove(currentNeighbour);
                waveTriggers.add(mergedNode);
            }
        }

        for (Node trigger : waveTriggers) {
            activeWaves.add(new UpdateWave(trigger));
        }
        runOnBlockRemoved();
    }

    /** Legt einen neuen Node an und registriert alle seine Blöcke in nodeMap. */
    private Node registerNewNode(Vector3i pos, C storage) {
        Node node = new Node();
        node.storage = storage;
        node.blocks.add(pos);
        nodeMap.put(pos, node);
        nodes.add(node);
        return node;
    }

    /** Führt nodeA und nodeB zusammen. Der Bridge-Block (pos) wird in den Merged-Node aufgenommen. */
    private void mergeNodes(Node nodeA, Node nodeB, Vector3i bridgePos, C bridgeStorage) {
        Node merged = new Node();
        merged.storage = nodeA.storage.add(nodeB.storage).add(bridgeStorage);

        merged.blocks.addAll(nodeA.blocks);
        merged.blocks.addAll(nodeB.blocks);
        merged.blocks.add(bridgePos);

        for (Vector3i b : merged.blocks) nodeMap.put(b, merged);

        // Externe Edges übernehmen (nicht die zwischen A und B).
        for (Edge e : nodeA.connectedEdges) {
            if (nodeB.connectedEdges.contains(e)) continue;
            if (e.other(nodeA) == nodeB) continue;
            merged.connectedEdges.add(e);
            replaceEdgeEndpoint(e, nodeA, merged);
        }
        for (Edge e : nodeB.connectedEdges) {
            if (nodeA.connectedEdges.contains(e)) continue;
            if (e.other(nodeB) == nodeA) continue;
            merged.connectedEdges.add(e);
            replaceEdgeEndpoint(e, nodeB, merged);
        }

        nodes.remove(nodeA);
        nodes.remove(nodeB);
        nodes.add(merged);
    }

    private void splitNode(Node node, Vector3i junctionPos, WorldChunk chunk) {
        nodes.remove(node);
        node.blocks.remove(junctionPos);
        nodeMap.remove(junctionPos);

        // Junction-Block wird ein eigener Node
        Node junctionNode = new Node();
        junctionNode.blocks.add(junctionPos);
        nodeMap.put(junctionPos, junctionNode);

        @SuppressWarnings("unchecked")
        C[] junctionParts = node.storage.partition(1, node.blocks.size() + 1);
        junctionNode.storage = junctionParts[0];

        // Externe Edges die den Junction-Block berühren übernehmen
        for (Edge e : node.connectedEdges) {
            if (e.from.equals(junctionPos) || e.to.equals(junctionPos)) {
                junctionNode.connectedEdges.add(e);
            }
        }
        node.connectedEdges.removeAll(junctionNode.connectedEdges);

        nodes.add(junctionNode);

        // Verbleibende Blöcke per BFS in zusammenhängende Segmente aufteilen
        Set<Vector3i> unvisited = new LinkedHashSet<>(node.blocks);
        while (!unvisited.isEmpty()) {
            Vector3i seed = unvisited.iterator().next();
            unvisited.remove(seed);

            Node seg = new Node();
            seg.blocks.add(seed);
            nodeMap.put(seed, seg);

            Queue<Vector3i> bfs = new ArrayDeque<>();
            bfs.add(seed);
            while (!bfs.isEmpty()) {
                Vector3i cur = bfs.poll();
                for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
                    Vector3i adj = new Vector3i(cur).add(BlockFaceEnum.FACE_OFFSETS[i]);
                    if (unvisited.remove(adj)) {
                        seg.blocks.add(adj);
                        nodeMap.put(adj, seg);
                        bfs.add(adj);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            C[] parts = node.storage.partition(seg.blocks.size(), node.blocks.size());
            seg.storage = parts[0];

            // Externe Edges übernehmen
            for (Edge e : node.connectedEdges) {
                if (seg.blocks.contains(e.from) || seg.blocks.contains(e.to))
                    seg.connectedEdges.add(e);
            }

            // Neue Edge zwischen diesem Segment und dem junctionNode erstellen,
            // falls ein Block des Segments direkt an junctionPos angrenzt
            boolean adjacentToJunction = false;
            for (Vector3i b : seg.blocks) {
                for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
                    if (new Vector3i(b).add(BlockFaceEnum.FACE_OFFSETS[i]).equals(junctionPos)) {
                        adjacentToJunction = true;
                        break;
                    }
                }
                if (adjacentToJunction) break;
            }

            if (adjacentToJunction) {
                // Grenzblock des Segments finden der an junctionPos angrenzt
                Vector3i segBoundary = null;
                outer:
                for (Vector3i b : seg.blocks) {
                    for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
                        if (new Vector3i(b).add(BlockFaceEnum.FACE_OFFSETS[i]).equals(junctionPos)) {
                            segBoundary = b;
                            break outer;
                        }
                    }
                }

                Edge newEdge = new Edge(new Vector3i(segBoundary), new Vector3i(junctionPos));
                newEdge.flux = node.storage.zero();
                seg.connectedEdges.add(newEdge);
                junctionNode.connectedEdges.add(newEdge);
            }

            nodes.add(seg);
        }
    }

    /** Ersetzt einen Node-Endpunkt in einer Edge (da from/to final sind, via nodeMap). */
    private void replaceEdgeEndpoint(Edge edge, Node oldNode, Node newNode) {
        // Edges referenzieren Koordinaten, nicht Nodes direkt –
        // nodeMap.put reicht da other() über nodeMap auflöst.
        for (Vector3i b : newNode.blocks) {
            if (oldNode.blocks.contains(b)) nodeMap.put(b, newNode);
        }
    }

    public void clear() {
        nodeMap.clear();
        nodes.forEach(node -> node.connectedEdges.clear());
        nodes.clear();
        activeWaves.clear();
    }

    public abstract void runOnBlockAdded();

    public abstract void runOnBlockRemoved();

    // Jede Subklasse liefert ihren eigenen Component-Codec
    protected abstract BuilderCodec<C> getComponentCodec();

    public boolean containsBlock(Vector3i pos) {
        return nodeMap.containsKey(pos);
    }

    public boolean isAdjacentTo(Vector3i pos) {
        for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
            if (nodeMap.containsKey(new Vector3i(pos).add(BlockFaceEnum.FACE_OFFSETS[i]))) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
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
                .append(
                        new KeyedCodec<>("Nodes", nodeArrayCodec),
                        (r, v) -> r.deserializeNodes(v),
                        r -> r.serializeNodes()
                ).add()
                .append(
                        new KeyedCodec<>("Edges", edgeArrayCodec),
                        (r, v) -> r.deserializeEdges(v),
                        r -> r.serializeEdges()
                ).add()
                .build();
    }

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
            for (Vector3i pos : dto.blocks) {
                node.blocks.add(pos);
                nodeMap.put(pos, node);
            }
            nodes.add(node);
        }
    }

    public void deserializeEdges(EdgeDTO<C>[] edgeDTOs) {
        for (EdgeDTO<C> dto : edgeDTOs) {
            Edge edge = new Edge(dto.from, dto.to);
            edge.flux  = dto.flux;
            Node fromNode = nodeMap.get(dto.from);
            Node toNode   = nodeMap.get(dto.to);
            if (fromNode != null) fromNode.connectedEdges.add(edge);
            if (toNode   != null) toNode.connectedEdges.add(edge);
        }
    }

    public void mergeFrom(BlockNetwork<C> other) {
        NodeDTO<C>[] otherNodes = other.serializeNodes();
        EdgeDTO<C>[] otherEdges = other.serializeEdges();

        // Bestehende Nodes/Edges serialisieren
        NodeDTO<C>[] thisNodes = serializeNodes();
        EdgeDTO<C>[] thisEdges = serializeEdges();

        // Kombinieren
        @SuppressWarnings("unchecked")
        NodeDTO<C>[] mergedNodes = new NodeDTO[thisNodes.length + otherNodes.length];
        System.arraycopy(thisNodes, 0, mergedNodes, 0, thisNodes.length);
        System.arraycopy(otherNodes, 0, mergedNodes, thisNodes.length, otherNodes.length);

        @SuppressWarnings("unchecked")
        EdgeDTO<C>[] mergedEdges = new EdgeDTO[thisEdges.length + otherEdges.length];
        System.arraycopy(thisEdges, 0, mergedEdges, 0, thisEdges.length);
        System.arraycopy(otherEdges, 0, mergedEdges, thisEdges.length, otherEdges.length);

        deserializeNodes(mergedNodes);
        deserializeEdges(mergedEdges);
    }

    public List<BlockNetwork<C>> getSplitNetworks() {
        if (nodes.size() <= 1) return Collections.emptyList();

        // BFS um zusammenhängende Node-Gruppen zu finden
        Set<Node> unvisited = new HashSet<>(nodes);
        List<Set<Node>> groups = new ArrayList<>();

        while (!unvisited.isEmpty()) {
            Set<Node> group = new HashSet<>();
            Queue<Node> queue = new ArrayDeque<>();
            Node start = unvisited.iterator().next();
            queue.add(start);
            unvisited.remove(start);

            while (!queue.isEmpty()) {
                Node current = queue.poll();
                group.add(current);
                for (Edge edge : current.connectedEdges) {
                    Node neighbour = edge.other(current);
                    if (unvisited.remove(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            groups.add(group);
        }

        // Nur eine Gruppe → kein Split
        if (groups.size() <= 1) return Collections.emptyList();

        // Für jede Gruppe ein neues Netzwerk erstellen
        List<BlockNetwork<C>> result = new ArrayList<>();
        for (Set<Node> group : groups) {
            BlockNetwork<C> newNetwork = /* factory needed */ null; // TODO: FACTORY NEEDED!!
            // Nodes serialisieren die zu dieser Gruppe gehören
            @SuppressWarnings("unchecked")
            NodeDTO<C>[] nodeDTOs = new NodeDTO[group.size()];
            int i = 0;
            for (Node node : group) {
                NodeDTO<C> dto = new NodeDTO<>();
                dto.blocks = node.blocks.toArray(new Vector3i[0]);
                dto.storage = node.storage;
                nodeDTOs[i++] = dto;
            }
            // Edges serialisieren die zu dieser Gruppe gehören
            Set<Edge> seen = new HashSet<>();
            List<EdgeDTO<C>> edgeList = new ArrayList<>();
            for (Node node : group) {
                for (Edge edge : node.connectedEdges) {
                    if (seen.add(edge)) {
                        EdgeDTO<C> dto = new EdgeDTO<>();
                        dto.from = edge.from;
                        dto.to = edge.to;
                        dto.flux = edge.flux;
                        edgeList.add(dto);
                    }
                }
            }
            @SuppressWarnings("unchecked")
            EdgeDTO<C>[] edgeDTOs = edgeList.toArray(new EdgeDTO[0]);

            newNetwork.deserializeNodes(nodeDTOs);
            newNetwork.deserializeEdges(edgeDTOs);
            result.add(newNetwork);
        }

        return result;
    }
}
