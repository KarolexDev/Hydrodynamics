package com.example.exampleplugin.system;

import com.example.exampleplugin.component.FluidStorageComponent;
import com.example.exampleplugin.component.ThermodynamicEnsembleComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class NewPipeFlowUpdateSystem extends EntityTickingSystem<ChunkStore> {

    private final ComponentType<ChunkStore, ThermodynamicEnsembleComponent> thermodynamicEnsembleComponentType;

    public NewPipeFlowUpdateSystem(ComponentType<ChunkStore, ThermodynamicEnsembleComponent> thermodynamicEnsembleComponentType) {
        super();
        this.thermodynamicEnsembleComponentType = thermodynamicEnsembleComponentType;
    }

    @Override
    public void tick(float v, int i, @NonNull ArchetypeChunk<ChunkStore> archetypeChunk, @NonNull Store<ChunkStore> store, @NonNull CommandBuffer<ChunkStore> commandBuffer) {

    }

    @Override
    public @Nullable Query<ChunkStore> getQuery() {
        return thermodynamicEnsembleComponentType;
    }
}