package com.example.exampleplugin.network;

import com.example.exampleplugin.util.BlockFaceEnum;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

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
            int remainingTicks;

            private Wavefront(Node current, Node cameFrom, int remainingTicks) {
                this.current = current;
                this.cameFrom = cameFrom;
                this.remainingTicks = remainingTicks;
            }

        }

        private final List<Wavefront> wavefronts = new CopyOnWriteArrayList<>();

        public UpdateWave(Node startNode) {
            wavefronts.add(new Wavefront(startNode, null, 0));
        }

        public void tick() {
            List<Wavefront> next = new ArrayList<>();

            for (Wavefront wf : wavefronts) {
                if (wf.remainingTicks > 0) {
                    // Noch nicht bereit – weiter warten
                    next.add(new Wavefront(wf.current, wf.cameFrom, wf.remainingTicks - 1));
                    continue;
                }

                double dt = wf.current.getAndUpdateTimestamp();
                wf.current.update(dt);

                for (Edge edge : wf.current.connectedEdges) {
                    Node neighbor = edge.other(wf.current);
                    if (neighbor.equals(wf.cameFrom)) continue;

                    double edgeDt = edge.getAndUpdateTimestamp();
                    edge.update(edgeDt);

                    int nodeDelay = neighbor.tickDelay(wf.current);
                    next.add(new Wavefront(neighbor, wf.current, nodeDelay));
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

    public void tick(int tickCounter) {

        // Bestehende Wellen weiterführen
        for (UpdateWave wave : activeWaves) {
            wave.tick();
        }
        // Abgeschlossene Wellen entfernen
        activeWaves.removeIf(wave -> wave.isFinished());

        // Neue Wellen starten für passende Nodes
        for (Node node : nodes) {
            if (node.triggersUpdates() && tickCounter % node.getUpdateTickRate() == 0) {
                activeWaves.add(new UpdateWave(node));
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
}
