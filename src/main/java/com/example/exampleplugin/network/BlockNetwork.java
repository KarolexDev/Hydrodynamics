package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class BlockNetwork<C extends BlockNetworkComponent> {
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
            return C.calculateFlux(nodeMap.get(from).storage, nodeMap.get(to).storage);
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

    // Public Hooks

    protected abstract String fromConnectionMaskToBlockID(byte bits);

    protected abstract void updateConnectionOnCoords(World world, byte bits); // <-- hook to world!
}
