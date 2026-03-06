package com.example.exampleplugin.blocknetwork;

import com.example.exampleplugin.blocknetwork.BlockNetworkSerialization.*;
import com.example.exampleplugin.util.BlockFaceEnum;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    public void onBlockPlaced(Vector3i origin, WorldChunk chunk, C storage, BlockType blockType) {
        Set<Vector3i> occupied = BlockFaceEnum.getOccupiedPositions(blockType, origin, chunk);
        Set<Vector3i> externalConns = occupied.stream()
                .flatMap(p -> BlockFaceEnum.getConnections(chunk, p).stream())
                .filter(c -> !occupied.contains(c))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<N> neighbours = networks.stream()
                .filter(n -> externalConns.stream().anyMatch(n::containsBlock))
                .toList();

        if (neighbours.isEmpty()) {
            N network = factory.get();
            network.onBlockPlaced(origin, blockType, chunk, storage);
            networks.add(network);
        } else {
            N primary = neighbours.getFirst();
            for (int i = 1; i < neighbours.size(); i++) {
                primary.mergeFrom(neighbours.get(i));
                networks.remove(neighbours.get(i));
            }
            primary.onBlockPlaced(origin, blockType, chunk, storage);
        }

        networks.removeIf(BlockNetwork::isEmpty);
    }

    public void onBlockRemoved(Vector3i origin, WorldChunk chunk, BlockType blockType) {
        N network = networks.stream()
                .filter(n -> n.containsBlock(origin))
                .findFirst()
                .orElse(null);
        if (network == null) return;

        List<N> split = (List<N>) (List<?>) network.onBlockRemoved(origin, blockType, chunk);

        networks.addAll(split);
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