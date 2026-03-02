package com.example.exampleplugin.network;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class BlockNetworkManager<C extends BlockNetworkComponent<C>, N extends BlockNetwork<C>> implements Resource<EntityStore> {

    final List<N> networks = new ArrayList<>();
    private final Supplier<N> factory;

    public BlockNetworkManager(Supplier<N> factory) {
        this.factory = factory;
    }

    public void onBlockPlaced(Vector3i pos, WorldChunk chunk, C storage) {
        // Finde alle Netzwerke die an pos angrenzen
        List<N> neighbours = networks.stream()
                .filter(n -> n.isAdjacentTo(pos))
                .toList();

        if (neighbours.isEmpty()) {
            // Neues Netzwerk erstellen
            N network = factory.get();
            network.onBlockPlaced(pos, chunk, storage);
            networks.add(network);
        } else if (neighbours.size() == 1) {
            // Zu bestehendem Netzwerk hinzufügen
            neighbours.getFirst().onBlockPlaced(pos, chunk, storage);
        } else {
            // Mehrere Netzwerke verbinden → zusammenführen
            N primary = neighbours.getFirst();
            primary.onBlockPlaced(pos, chunk, storage);
            for (int i = 1; i < neighbours.size(); i++) {
                primary.mergeFrom(neighbours.get(i));
                networks.remove(neighbours.get(i));
            }
        }
    }

    public void onBlockRemoved(Vector3i pos, WorldChunk chunk) {
        N network = networks.stream()
                .filter(n -> n.containsBlock(pos))
                .findFirst()
                .orElse(null);

        if (network == null) return;

        network.onBlockRemoved(pos, chunk);

        // Wenn das Netzwerk durch das Entfernen gesplittet wurde,
        // die neuen Teilnetzwerke als separate Instanzen registrieren
        @SuppressWarnings("unchecked")
        List<N> split = (List<N>) (List<?>) network.getSplitNetworks();
        if (!split.isEmpty()) {
            networks.remove(network);
            networks.addAll(split);
        }

        // Leere Netzwerke entfernen
        networks.removeIf(n -> n.isEmpty());
    }

    @Override
    public @Nullable Resource<EntityStore> clone() {
        return new BlockNetworkManager(this.factory);
    }

    public static <C extends BlockNetworkComponent<C>, N extends BlockNetwork<C>, M extends BlockNetworkManager<C, N>>
    BuilderCodec<M> createCodec(Class<M> clazz, Supplier<M> managerFactory, BuilderCodec<N> networkCodec) {

        @SuppressWarnings("unchecked")
        ArrayCodec<N> networkArrayCodec = (ArrayCodec<N>) (Object)
                ArrayCodec.ofBuilderCodec(networkCodec, size -> (N[]) new BlockNetwork[size]);

        return BuilderCodec
                .builder(clazz, managerFactory)
                .append(
                        new KeyedCodec<>("Networks", networkArrayCodec),
                        (m, v) -> { m.networks.clear(); m.networks.addAll(Arrays.asList(v)); },
                        m -> {
                            @SuppressWarnings("unchecked")
                            N[] array = (N[]) (Object) m.networks.toArray(new BlockNetwork[0]);
                            return array;
                        }
                ).add()
                .build();
    }
}
