package com.karolex.hydrodynamics.blocknetwork;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.math.vector.Vector3i;

public class BlockNetworkSerialization {
    private BlockNetworkSerialization() { /* Utility Class */ }
    public static class NodeDTO<C> {
        public Vector3i[] blocks;
        public C storage;

        public static <C> BuilderCodec<NodeDTO<C>> codec(BuilderCodec<C> componentCodec) {
            return BuilderCodec
                    .builder((Class<NodeDTO<C>>) (Class<?>) NodeDTO.class, NodeDTO::new)
                    .append(new KeyedCodec<>("Blocks", ArrayCodec.ofBuilderCodec(Vector3i.CODEC, Vector3i[]::new)),
                            (d, v) -> d.blocks = v,
                            d -> d.blocks)
                    .add()
                    .append(new KeyedCodec<>("Storage", componentCodec),
                            (d, v) -> d.storage = v,
                            d -> d.storage)
                    .add()
                    .build();
        }
    }
    public static class EdgeDTO<C> {
        public Vector3i from;
        public Vector3i to;
        public C flux;

        public static <C> BuilderCodec<EdgeDTO<C>> codec(BuilderCodec<C> componentCodec) {
            return BuilderCodec
                    .builder((Class<EdgeDTO<C>>)(Class<?>) EdgeDTO.class, EdgeDTO::new)
                    .append(new KeyedCodec<>("From", Vector3i.CODEC),
                            (d, v) -> d.from = v,
                            d -> d.from)
                    .add()
                    .append(new KeyedCodec<>("To", Vector3i.CODEC),
                            (d, v) -> d.to = v,
                            d -> d.to)
                    .add()
                    .append(new KeyedCodec<>("Flux", componentCodec),
                            (d, v) -> d.flux = v,
                            d -> d.flux)
                    .add()
                    .build();
        }
    }
}
