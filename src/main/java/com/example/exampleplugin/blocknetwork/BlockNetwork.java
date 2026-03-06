package com.example.exampleplugin.blocknetwork;

import com.example.exampleplugin.util.BlockFaceEnum;
import com.example.exampleplugin.blocknetwork.BlockNetworkSerialization.*;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public abstract class BlockNetwork<C extends BlockNetworkComponent<C>> {

    private static final float MIN_UPDATE_INTERVAL = 0.05f;  // max. 20 Updates/s bei hoher Aktivität
    private static final float MAX_UPDATE_INTERVAL = 5.0f;   // min. 1 Update alle 5s bei Stillstand

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
        private static int globalWaveTick = 0;

        public UpdateWave(Node startNode) {
            wavefronts.add(new Wavefront(startNode, null, 0));
        }

        public void tick(float dt) {
            int currentWaveTick = globalWaveTick++;
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
                    wf.current.update(dt, currentWaveTick);
                    changeRate = storageBefore.changeRate(wf.current.storage);
                    wf.current.recordChangeRate(changeRate);
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

        for (Node node : nodes) {   // TODO: ITERATES THROUGH ALL NODES??? THAT IS STUPID
            node.timeSinceLastUpdate += dt;

            if (node.triggersUpdates()) {
                node.timeSinceLastUpdate = 0f;
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

        private float timeSinceLastUpdate = 0f;
        private float lastChangeRate = 0f;
        private int lastAppliedWaveTick = -1;

        Node update(float dt, int waveTick) {
            if (lastAppliedWaveTick == waveTick) return this;
            lastAppliedWaveTick = waveTick;

            for (Edge edge : connectedEdges) {
                if (nodeMap.get(edge.from) == this) {
                    storage.del(edge.flux);
                } else {
                    storage.add(edge.flux);
                }
            }
            return this;
        }

        void recordChangeRate(float changeRate) {
            this.lastChangeRate = changeRate;
        }

        float computeUpdateInterval() {
            if (lastChangeRate <= 0f) return MAX_UPDATE_INTERVAL;
            return Math.clamp(MIN_UPDATE_INTERVAL / lastChangeRate, MIN_UPDATE_INTERVAL, MAX_UPDATE_INTERVAL);
        }

        public boolean triggersUpdates() {
            return timeSinceLastUpdate >= computeUpdateInterval();
        }

        public int getUpdateTickRate() { return 1; }

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
    }

    // ─── onBlockPlaced ───────────────────────────────────────────────────────

    public void onBlockPlaced(Vector3i origin, BlockType blockType, WorldChunk chunk, C storage) {
        // 1. Alle Positionen die dieser Block belegt
        Set<Vector3i> occupiedSet = BlockFaceEnum.getOccupiedPositions(blockType, origin, chunk);

        // 2. Sanity-Check
        for (Vector3i p : occupiedSet) {
            if (nodeMap.containsKey(p))
                throw new IllegalStateException("Block position already occupied: " + p);
        }

        // 3. Neuen Node registrieren
        Node newNode = new Node();
        newNode.storage = storage;
        for (Vector3i p : occupiedSet) {
            newNode.blocks.add(new Vector3i(p));
            nodeMap.put(new Vector3i(p), newNode);
        }
        nodes.add(newNode);

        // 4. Alle externen Nachbar-Nodes sammeln (dedupliziert).
        //    Pro Nachbar-Node den ersten gefundenen Kontaktpunkt speichern:
        //    contacts[neighbour] = { unserKontaktBlock, seinKontaktBlock }
        Map<Node, Vector3i[]> neighbourContacts = new LinkedHashMap<>();
        for (Vector3i blockPos : occupiedSet) {
            for (Vector3i connPos : BlockFaceEnum.getConnections(chunk, blockPos)) {
                if (occupiedSet.contains(connPos)) continue;
                Node neighbour = nodeMap.get(connPos);
                if (neighbour == null || neighbour == newNode) continue;
                neighbourContacts.putIfAbsent(neighbour,
                        new Vector3i[]{new Vector3i(blockPos), new Vector3i(connPos)});
            }
        }

        // 5. Für jeden Nachbarn: merge oder edge entscheiden.
        //    WICHTIG: newNode-Referenz nach jedem Merge über nodeMap.get(origin) neu holen.
        for (Map.Entry<Node, Vector3i[]> entry : neighbourContacts.entrySet()) {
            Node neighbour    = entry.getKey();
            Vector3i ourPos   = entry.getValue()[0];
            Vector3i theirPos = entry.getValue()[1];

            // Nach einem vorigen Merge könnte origin auf einen anderen Node zeigen
            Node currentNew = nodeMap.get(origin);
            if (currentNew == null) break; // sollte nicht passieren

            // Nachbar könnte durch vorigen Merge bereits absorbiert worden sein
            if (!nodes.contains(neighbour)) continue;

            if (currentNew.storage.shouldMerge(neighbour.storage)) {
                if (currentNew.storage.isPipe() && neighbour.storage.isPipe()) {
                    int newExternal       = countExternalConnections(currentNew.blocks, chunk);
                    // NEU: physische Verbindungen des Nachbar-Kontaktblocks aus dem Chunk lesen
                    int neighbourExternal = BlockFaceEnum.getConnections(chunk, theirPos).size();

                    if (newExternal <= 2 && neighbourExternal <= 2) {
                        mergeInto(currentNew, neighbour);
                    } else {
                        if (neighbourExternal > 2) splitNode(theirPos, chunk);
                        addEdge(ourPos, theirPos, currentNew.storage);
                        Node refreshedNeighbour = nodeMap.get(theirPos);
                        if (refreshedNeighbour != null)
                            activeWaves.add(new UpdateWave(refreshedNeighbour));
                    }
                } else {
                    // Nicht-Pipe (z.B. Tank): immer mergen
                    mergeInto(currentNew, neighbour);
                }
            } else {
                // Verschiedene Netzwerktypen → Edge
                addEdge(ourPos, theirPos, currentNew.storage);
                activeWaves.add(new UpdateWave(neighbour));
            }
        }

        // 6. Wave für den (möglicherweise gemergten) finalen Node starten
        Node finalNew = nodeMap.get(origin);
        if (finalNew != null) activeWaves.add(new UpdateWave(finalNew));

        runOnBlockAdded();
    }

    // ─── onBlockRemoved ──────────────────────────────────────────────────────

    public List<BlockNetwork<C>> onBlockRemoved(Vector3i origin, BlockType blockType, WorldChunk chunk) {
        Node removedNode = nodeMap.get(origin);
        if (removedNode == null) return Collections.emptyList();

        // 1. Alle Positionen die dieser Block belegt
        Set<Vector3i> occupiedSet = BlockFaceEnum.getOccupiedPositions(blockType, origin, chunk);

        // 2. Direkte Nachbar-Nodes sichern, bevor wir irgendwas verändern.
        //    WICHTIG: getConnections liest aus dem Chunk – der ist zu diesem Zeitpunkt
        //    bereits aktualisiert (Block ist weg), liefert also keine Verbindungen mehr.
        //    Stattdessen alle 6 Nachbarpositionen direkt in der nodeMap nachschlagen.
        Set<Node> affectedNeighbours = new LinkedHashSet<>();
        for (Vector3i blockPos : occupiedSet) {
            for (Vector3i offset : BlockFaceEnum.FACE_OFFSETS) {
                Vector3i neighbourPos = new Vector3i(blockPos).add(offset);
                if (occupiedSet.contains(neighbourPos)) continue;
                Node nb = nodeMap.get(neighbourPos);
                if (nb != null && nb != removedNode) affectedNeighbours.add(nb);
            }
        }

        // 3. Node(s) entfernen – drei Fälle:
        //
        //    Fall A: Multiblock-Hitbox (occupiedSet.size() > 1)
        //            → removedNode komplett entfernen
        //
        //    Fall B: Einzelblock-Hitbox, Node hat genau einen Block
        //            → Node komplett entfernen
        //
        //    Fall C: Einzelblock-Hitbox, Node hat mehrere Blöcke (durch früheres mergeInto)
        //            → nur diesen Block herauslösen, verbleibende Blöcke per BFS
        //              in räumlich zusammenhängende Segmente aufteilen

        if (occupiedSet.size() > 1 || removedNode.blocks.size() == 1) {
            // Fall A + B
            removeNodeCompletely(removedNode);
        } else {
            // Fall C
            splitRemovedBlockFromNode(origin, removedNode, affectedNeighbours, chunk);
        }

        // 4. Ungültige Edges bei Nachbarn aufräumen
        //    (Edges die auf den entfernten Node zeigten)
        for (Node nb : affectedNeighbours) {
            nb.connectedEdges.removeIf(e -> {
                Node other = e.other(nb);
                return other == null || !nodes.contains(other);
            });
        }

        // 5. Lineare Pipe-Nodes zusammenführen
        //    Kandidaten = affectedNeighbours + deren Nachbarn (eine Ebene tiefer),
        //    damit auch der ehemalige Junction-Node erfasst wird.
        Set<Node> mergeCandidates = new LinkedHashSet<>(affectedNeighbours);
        for (Node nb : affectedNeighbours) {
            for (Edge e : new ArrayList<>(nb.connectedEdges)) {
                Node secondDegree = e.other(nb);
                if (secondDegree != null) mergeCandidates.add(secondDegree);
            }
        }
        mergeLinearNeighbours(mergeCandidates, chunk);

        // 6. Waves für betroffene Nachbarn starten
        for (Node nb : affectedNeighbours) {
            if (nodes.contains(nb) && nb.storage != null)
                activeWaves.add(new UpdateWave(nb));
        }

        // 7. Netz auf Zusammenhang prüfen → ggf. aufteilen
        List<BlockNetwork<C>> splits = detectSplit();
        runOnBlockRemoved();
        return splits;
    }

    // ─── Hilfsmethoden für onBlockRemoved ────────────────────────────────────

    /** Entfernt einen Node vollständig aus nodeMap, nodes und räumt alle Edges auf. */
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
     * Löst {@code removedPos} aus {@code oldNode} heraus und teilt die verbleibenden
     * Blöcke per BFS in räumlich zusammenhängende Segmente auf.
     * Jedes Segment erbt Storage proportional zur Blockanzahl und die relevanten Edges.
     */
    private void splitRemovedBlockFromNode(Vector3i removedPos, Node oldNode,
                                           Set<Node> affectedNeighbours, WorldChunk chunk) {
        // Entfernten Block aus nodeMap und oldNode.blocks herauslösen
        nodeMap.remove(removedPos);
        oldNode.blocks.remove(removedPos);
        int totalOriginalSize = oldNode.blocks.size() + 1; // +1 weil removedPos bereits entfernt

        // oldNode aus nodes entfernen – Segmente werden neu registriert
        nodes.remove(oldNode);

        // nodeMap-Einträge der verbleibenden Blöcke freigeben,
        // damit buildSegment sie korrekt neu setzen kann
        for (Vector3i b : oldNode.blocks) nodeMap.remove(b);

        Set<Vector3i> unvisited = new LinkedHashSet<>(oldNode.blocks);

        while (!unvisited.isEmpty()) {
            Node seg = buildSegment(unvisited); // setzt nodeMap-Einträge für seg.blocks

            // Storage proportional aufteilen
            @SuppressWarnings("unchecked")
            C[] parts = oldNode.storage.partition(seg.blocks.size(), totalOriginalSize - seg.blocks.size());
            seg.storage = parts[0];

            // Edges des oldNode die zu diesem Segment gehören übernehmen.
            // Eine Edge gehört zum Segment wenn from oder to im Segment liegen.
            for (Edge e : oldNode.connectedEdges) {
                boolean fromInSeg = seg.blocks.contains(e.from);
                boolean toInSeg   = seg.blocks.contains(e.to);
                if (!fromInSeg && !toInSeg) continue;

                seg.connectedEdges.add(e);

                // Gegenseite direkt über die Position auflösen die NICHT im Segment liegt
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

    /**
     * Mergt lineare Pipe-Nodes zusammen.
     * Ein Node gilt als linear wenn er nach dem Edge-Cleanup <= 2 Netzwerk-Verbindungen hat.
     * Wiederholt bis kein weiterer Merge möglich ist (Fixpunkt).
     */
    private void mergeLinearNeighbours(Set<Node> candidates, WorldChunk chunk) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (Node node : new ArrayList<>(candidates)) {
                if (!nodes.contains(node)) continue;
                if (!node.storage.isPipe()) continue;

                // Physische Verbindungen aller Blöcke dieses Nodes prüfen
                int physicalConns = countPhysicalConnections(node.blocks, chunk);
                if (physicalConns > 2) continue; // immer noch Junction

                if (node.connectedEdges.size() > 2) continue;

                for (Edge e : new ArrayList<>(node.connectedEdges)) {
                    Node neighbour = e.other(node);
                    if (neighbour == null) continue;
                    if (!nodes.contains(neighbour)) continue;
                    if (!neighbour.storage.isPipe()) continue;

                    int neighbourPhysicalConns = countPhysicalConnections(neighbour.blocks, chunk);
                    if (neighbourPhysicalConns > 2) continue; // Nachbar ist noch Junction

                    if (neighbour.connectedEdges.size() > 2) continue;

                    mergeInto(node, neighbour);
                    candidates.add(node);
                    changed = true;
                    break outer;
                }
            }
        }
    }

    /** Liest die tatsächlichen physischen Verbindungen aller Blöcke eines Nodes aus dem Chunk. */
    private int countPhysicalConnections(Set<Vector3i> blockSet, WorldChunk chunk) {
        return (int) blockSet.stream()
                .flatMap(p -> BlockFaceEnum.getConnections(chunk, p).stream())
                .filter(c -> !blockSet.contains(c))
                .distinct()
                .count();
    }

    // ─── Hilfsmethoden (unverändert) ─────────────────────────────────────────

    private int countExternalConnections(Set<Vector3i> blockSet, WorldChunk chunk) {
        return (int) blockSet.stream()
                .flatMap(p -> BlockFaceEnum.getConnections(chunk, p).stream())
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
                continue; // interne Edge zwischen target und absorbed → wegwerfen
            }
            target.connectedEdges.add(e);

            // Gegenseite aktualisieren
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
            if (existing.other(a) == b) return; // keine doppelten Edges
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

        // Junction bekommt eigenen Node
        Node junctionNode = new Node();
        junctionNode.blocks.add(new Vector3i(junctionPos));
        junctionNode.storage = oldNode.storage.partition(1, oldNode.blocks.size() - 1)[0];
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
            for (Vector3i offset : BlockFaceEnum.FACE_OFFSETS) {
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

    public boolean isEmpty() { return nodes.isEmpty() && nodeMap.isEmpty(); }

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