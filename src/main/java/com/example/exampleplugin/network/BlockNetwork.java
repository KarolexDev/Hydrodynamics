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
        int connectionCount = Integer.bitCount(mask & 0xFF);

        List<Byte>     activeFaces        = new ArrayList<>();
        List<Vector3i> neighbourPositions = new ArrayList<>();
        List<Node>     neighbourNodes     = new ArrayList<>();

        for (int i = 0; i < BlockFaceEnum.FACE_BITS.length; i++) {
            if ((mask & BlockFaceEnum.FACE_BITS[i]) == 0) continue;

            Vector3i neighbourPos  = new Vector3i(pos).add(BlockFaceEnum.FACE_OFFSETS[i]);
            Node     neighbourNode = nodeMap.get(neighbourPos);
            if (neighbourNode == null) continue;

            byte neighbourMask = BlockFaceEnum.readFromWorld(chunk, neighbourPos);
            if ((neighbourMask & BlockFaceEnum.opposite(BlockFaceEnum.FACE_BITS[i])) == 0) continue;

            activeFaces.add(BlockFaceEnum.FACE_BITS[i]);
            neighbourPositions.add(neighbourPos);
            neighbourNodes.add(neighbourNode);
        }

        // Nodes die nach der Umstrukturierung Update-Wellen bekommen sollen.
        Set<Node> waveTriggers = new LinkedHashSet<>();

        if (connectionCount == 2 && activeFaces.size() <= 2) {
            Set<Node> distinctNeighbourNodes = new LinkedHashSet<>(neighbourNodes);

            if (distinctNeighbourNodes.size() == 2) {
                Iterator<Node> it = distinctNeighbourNodes.iterator();
                mergeNodes(it.next(), it.next(), pos, storage);
                // Der zusammengeführte Node ist jetzt unter pos erreichbar.
                waveTriggers.add(nodeMap.get(pos));
            } else if (distinctNeighbourNodes.size() == 1) {
                Node target = distinctNeighbourNodes.iterator().next();
                target.blocks.add(pos);
                target.storage.add(storage);
                nodeMap.put(pos, target);
                waveTriggers.add(target);
            } else {
                waveTriggers.add(registerNewNode(pos, storage));
            }

        } else {
            Node newNode = registerNewNode(pos, storage);
            waveTriggers.add(newNode);

            for (int i = 0; i < neighbourPositions.size(); i++) {
                if (needsSplit(neighbourNodes.get(i), neighbourPositions.get(i))) {
                    splitNode(neighbourNodes.get(i), neighbourPositions.get(i), chunk);
                }

                Node resolvedNeighbour = nodeMap.get(neighbourPositions.get(i));
                Edge edge = new Edge(new Vector3i(pos), new Vector3i(neighbourPositions.get(i)));
                edge.flux = storage.zero(); // edge.flux = storage.zero();
                newNode.connectedEdges.add(edge);
                resolvedNeighbour.connectedEdges.add(edge);
                waveTriggers.add(resolvedNeighbour);
            }
        }

        for (Node trigger : waveTriggers) {
            activeWaves.add(new UpdateWave(trigger));
        }
    }

    public void onBlockRemoved(Vector3i pos, WorldChunk chunk) {
        Node removedNode = nodeMap.remove(pos);
        if (removedNode == null) return;

        removedNode.blocks.remove(pos);
        nodes.remove(removedNode);

        // Alle Edges die diesen Block berühren entfernen und betroffene Nachbar-Nodes sammeln.
        Set<Node> affectedNeighbours = new LinkedHashSet<>();
        for (Edge edge : new ArrayList<>(removedNode.connectedEdges)) {
            Node neighbour = edge.other(removedNode);
            neighbour.connectedEdges.remove(edge);
            affectedNeighbours.add(neighbour);
        }
        removedNode.connectedEdges.clear();

        // Falls der Node noch Blöcke hatte, verbleibende Blöcke in Segmente aufteilen.
        if (!removedNode.blocks.isEmpty()) {
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
                    for (Vector3i offset : BlockFaceEnum.FACE_OFFSETS) {
                        Vector3i adj = new Vector3i(cur).add(offset);
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

                for (Edge e : removedNode.connectedEdges) {
                    boolean startHere = seg.blocks.contains(e.from);
                    boolean endHere   = seg.blocks.contains(e.to);
                    if (startHere || endHere) seg.connectedEdges.add(e);
                }

                nodes.add(seg);
            }
        }

        // Nodes die nach der Umstrukturierung Update-Wellen bekommen sollen.
        Set<Node> waveTriggers = new LinkedHashSet<>();

        for (Node neighbour : affectedNeighbours) {
            Vector3i representativePos = neighbour.blocks.iterator().next();
            byte newMask = BlockFaceEnum.readFromWorld(chunk, representativePos);
            int newConnectionCount = Integer.bitCount(newMask & 0xFF);

            // Nachbar verlor eine Verbindung → immer eine Welle auslösen.
            waveTriggers.add(neighbour);

            if (newConnectionCount != 2) continue;

            // War Junction, ist jetzt einfache Pipe → ggf. mit anderem Node zusammenführen.
            List<Node> pipedNeighbours = new ArrayList<>();
            for (int i = 0; i < BlockFaceEnum.FACE_BITS.length; i++) {
                if ((newMask & BlockFaceEnum.FACE_BITS[i]) == 0) continue;
                Vector3i nPos = new Vector3i(representativePos).add(BlockFaceEnum.FACE_OFFSETS[i]);
                Node n = nodeMap.get(nPos);
                if (n != null && n != neighbour) pipedNeighbours.add(n);
            }

            if (pipedNeighbours.size() == 2) {
                mergeNodes(pipedNeighbours.get(0), pipedNeighbours.get(1),
                        representativePos, neighbour.storage);
                // Zusammengeführter Node unter representativePos triggern.
                waveTriggers.add(nodeMap.get(representativePos));
            }
        }

        for (Node trigger : waveTriggers) {
            activeWaves.add(new UpdateWave(trigger));
        }
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

    /**
     * Gibt true zurück wenn der Node an neighbourPos durch eine neue externe
     * Verbindung von einem einfachen Pipe-Node zum Junction wird.
     */
    private boolean needsSplit(Node node, Vector3i representativePos) {
        // Ein Node war eine einfache Pipe wenn er genau 2 Edges hatte.
        // Hat er durch den neuen Block jetzt eine dritte, muss er gesplittet werden.
        return node.connectedEdges.size() >= 2;
    }

    /**
     * Spaltet einen Node auf: Jeder Block des Nodes der eine Junction-Verbindung hat
     * wird zu einem eigenen Node. Einfache Durchgangs-Blöcke bleiben zusammen.
     */
    private void splitNode(Node node, Vector3i junctionPos, WorldChunk chunk) {
        nodes.remove(node);
        for (Vector3i b : node.blocks) nodeMap.remove(b);

        // BFS: gruppiere Blöcke in zusammenhängende Segmente,
        // wobei Junction-Blöcke (connectionCount != 2) Trennpunkte sind.
        Set<Vector3i> unvisited = new LinkedHashSet<>(node.blocks);
        while (!unvisited.isEmpty()) {
            Vector3i seed = unvisited.iterator().next();
            unvisited.remove(seed);

            Node seg = new Node();
            seg.storage = node.storage.zero(); // Kapazität wird unten anteilig gesetzt
            seg.blocks.add(seed);
            nodeMap.put(seed, seg);

            Queue<Vector3i> bfs = new ArrayDeque<>();
            bfs.add(seed);
            while (!bfs.isEmpty()) {
                Vector3i cur = bfs.poll();
                byte curMask = BlockFaceEnum.readFromWorld(chunk, cur);
                if (Integer.bitCount(curMask & 0xFF) != 2) continue; // Junction → Grenze

                for (int i = 0; i < BlockFaceEnum.FACE_OFFSETS.length; i++) {
                    Vector3i adj = new Vector3i(cur).add(BlockFaceEnum.FACE_OFFSETS[i]);
                    if (unvisited.remove(adj)) {
                        seg.blocks.add(adj);
                        nodeMap.put(adj, seg);
                        bfs.add(adj);
                    }
                }
            }

            // Kapazität anteilig aus dem alten Node übernehmen
            @SuppressWarnings("unchecked")
            C[] parts = node.storage.partition(seg.blocks.size(), node.blocks.size());
            seg.storage = parts[0];

            // Externe Edges die zu diesem Segment gehören übernehmen
            for (Edge e : node.connectedEdges) {
                boolean startHere = seg.blocks.contains(e.from);
                boolean endHere   = seg.blocks.contains(e.to);
                if (startHere || endHere) seg.connectedEdges.add(e);
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
}
