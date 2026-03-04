package com.example.exampleplugin.blocknetwork;

import com.example.exampleplugin.blocknetwork.BlockNetworkSerialization.*;
import com.example.exampleplugin.util.BlockFaceEnum;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

public class BlockNetworkManager<C extends BlockNetworkComponent<C>, N extends BlockNetwork<C>>
        implements Resource<EntityStore> {

    protected final List<N> networks = new ArrayList<>();
    private final Supplier<N> factory;

    public BlockNetworkManager(Supplier<N> factory) {
        this.factory = factory;
    }

    public void tick(float dt, World world) {
        for (N network : networks) network.tick(dt, world);
    }

    public void onBlockPlaced(Vector3i pos, WorldChunk chunk, C storage) {

        List<Vector3i> connections = BlockFaceEnum.getConnections(chunk, pos);

        // Netzwerke finden die einen der Verbindungsblöcke bereits kennen
        List<N> neighbours = networks.stream()
                .filter(n -> connections.stream().anyMatch(n::containsBlock))
                .toList();

        if (neighbours.isEmpty()) {
            N network = factory.get();
            network.onBlockPlaced(pos, connections, chunk, storage);
            networks.add(network);
        } else {
            // Alle betroffenen Netzwerke zuerst zusammenführen, dann Block einfügen
            N primary = neighbours.getFirst();
            for (int i = 1; i < neighbours.size(); i++) {
                primary.mergeFrom(neighbours.get(i));
                networks.remove(neighbours.get(i));
            }
            primary.onBlockPlaced(pos, connections, chunk, storage);
        }

        networks.removeIf(BlockNetwork::isEmpty);
    }

    public void onBlockRemoved(Vector3i pos, WorldChunk chunk) {
        N network = networks.stream()
                .filter(n -> n.containsBlock(pos))
                .findFirst()
                .orElse(null);
        if (network == null) return;

        @SuppressWarnings("unchecked")
        List<N> split = (List<N>) (List<?>) network.onBlockRemoved(pos, chunk);

        if (!split.isEmpty()) {
            networks.remove(network);
            networks.addAll(split);
        }

        networks.removeIf(BlockNetwork::isEmpty);
    }

    public void clear() {
        networks.forEach(N::clear);
        networks.clear();
    }

    public C getComponent(Vector3i vec) {
        for (N network : networks) {
            C result = network.getComponent(vec);
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return new BlockNetworkManager<>(this.factory);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BlockNetworkManager[").append(networks.size()).append(" networks]");
        for (int i = 0; i < networks.size(); i++) {
            sb.append("\n  [").append(i).append("] ").append(networks.get(i));
        }
        return sb.toString();
    }

    public static <C extends BlockNetworkComponent<C>, N extends BlockNetwork<C>,
            M extends BlockNetworkManager<C, N>>
    BuilderCodec<M> createCodec(Class<M> clazz, Supplier<M> managerFactory, BuilderCodec<N> networkCodec) {

        @SuppressWarnings("unchecked")
        ArrayCodec<N> networkArrayCodec = (ArrayCodec<N>) (Object)
                ArrayCodec.ofBuilderCodec(networkCodec, size -> (N[]) new BlockNetwork[size]);

        return BuilderCodec
                .builder(clazz, managerFactory)
                .append(new KeyedCodec<>("Networks", networkArrayCodec),
                        (m, v) -> { m.networks.clear(); m.networks.addAll(Arrays.asList(v)); },
                        m -> { @SuppressWarnings("unchecked")
                        N[] array = (N[]) m.networks.toArray(new BlockNetwork[0]);
                            return array; }
                ).add()
                .build();
    }
}