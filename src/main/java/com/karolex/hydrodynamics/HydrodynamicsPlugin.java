package com.karolex.hydrodynamics;

import com.karolex.hydrodynamics.command.ClearAllBlockNetworks;
import com.karolex.hydrodynamics.command.ShowBlockNetworks;
import com.karolex.hydrodynamics.gasnetwork.GasNetworkComponent;
import com.karolex.hydrodynamics.gasnetwork.GasNetworkResource;
import com.karolex.hydrodynamics.gasnetwork.GasNetworkSystem;
import com.karolex.hydrodynamics.interaction.ConfigurePipeInteraction;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public class HydrodynamicsPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static HydrodynamicsPlugin instance;
    private ComponentType<ChunkStore, GasNetworkComponent> gasNetworkComponentType;
    private ResourceType<EntityStore, GasNetworkResource> gasNetworkResourceType;

    public HydrodynamicsPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    public static HydrodynamicsPlugin getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new ShowBlockNetworks());
        this.getCommandRegistry().registerCommand(new ClearAllBlockNetworks());

        this.gasNetworkComponentType = this.getChunkStoreRegistry().registerComponent(GasNetworkComponent.class, "GasNetworkComponent", GasNetworkComponent.CODEC);
        this.gasNetworkResourceType = this.getEntityStoreRegistry().registerResource(GasNetworkResource.class, "GasNetworkResource", GasNetworkResource.CODEC);

        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkTickingSystem());
        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkBlockPlaceEventSystem());
        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkBlockBreakEventSystem());
        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkBlockUseEventSystem());

        this.getCodecRegistry(Interaction.CODEC).register("ConfigurePipe", ConfigurePipeInteraction.class, ConfigurePipeInteraction.CODEC);
    }

    public ComponentType<ChunkStore, GasNetworkComponent> getGasNetworkComponentType() { return this.gasNetworkComponentType; }
    public ResourceType<EntityStore, GasNetworkResource> geGasNetworkResourceType() { return this.gasNetworkResourceType; }
}
