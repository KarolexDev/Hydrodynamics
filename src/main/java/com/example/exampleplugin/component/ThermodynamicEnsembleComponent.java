package com.example.exampleplugin.component;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.Nullable;

public class ThermodynamicEnsembleComponent implements Component<ChunkStore> {
    public float entropy;
    public float volume;
    public float molarity;

    public float entropy_flow = 0;
    public float volume_flow = 0; // In almost all systems zero
    public float particle_flow = 0;

    public ThermodynamicEnsembleComponent(
            float entropy,
            float volume,
            float molarity
    ) {
        this.entropy = entropy;
        this.volume = volume;
        this.molarity = molarity;
    }

    @Override
    public @Nullable Component<ChunkStore> clone() {
        return new ThermodynamicEnsembleComponent(
                this.entropy,
                this.volume,
                this.molarity
        );
    }
}
