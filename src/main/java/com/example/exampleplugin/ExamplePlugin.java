package com.example.exampleplugin;

import com.example.exampleplugin.command.ClearAllBlockNetworks;
import com.example.exampleplugin.command.ShowBlockNetworks;
import com.example.exampleplugin.examplenetwork.ExampleNetworkResource;
import com.example.exampleplugin.examplenetwork.ExampleComponent;
import com.example.exampleplugin.gasnetwork.GasNetworkComponent;
import com.example.exampleplugin.gasnetwork.GasNetworkResource;
import com.example.exampleplugin.gasnetwork.GasNetworkSystem;
import com.example.exampleplugin.interaction.ConfigurePipeInteraction;
import com.example.exampleplugin.examplenetwork.ExampleNetworkSystem;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public class ExamplePlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ExamplePlugin instance;
    private ComponentType<ChunkStore, ExampleComponent> exampleComponentType;
    private ComponentType<ChunkStore, GasNetworkComponent> gasNetworkComponentType;
    private ResourceType<EntityStore, ExampleNetworkResource> exampleNetworkResourceType;
    private ResourceType<EntityStore, GasNetworkResource> gasNetworkResourceType;

    public ExamplePlugin(JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    public static ExamplePlugin getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new ShowBlockNetworks());
        this.getCommandRegistry().registerCommand(new ClearAllBlockNetworks());

        this.exampleComponentType = this.getChunkStoreRegistry().registerComponent(ExampleComponent.class, "ExampleComponent", ExampleComponent.CODEC);
        this.gasNetworkComponentType = this.getChunkStoreRegistry().registerComponent(GasNetworkComponent.class, "GasNetworkComponent", GasNetworkComponent.CODEC);

        this.exampleNetworkResourceType = this.getEntityStoreRegistry().registerResource(ExampleNetworkResource.class, "ExampleNetworkResource", ExampleNetworkResource.CODEC);
        this.gasNetworkResourceType = this.getEntityStoreRegistry().registerResource(GasNetworkResource.class, "GasNetworkResource", GasNetworkResource.CODEC);

        this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkTickingSystem());
        this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkBlockPlaceEventSystem());
        this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkBlockBreakEventSystem());

        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkTickingSystem());
        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkBlockPlaceEventSystem());
        this.getEntityStoreRegistry().registerSystem(new GasNetworkSystem.NetworkBlockBreakEventSystem());

        this.getCodecRegistry(Interaction.CODEC).register("ConfigurePipe", ConfigurePipeInteraction.class, ConfigurePipeInteraction.CODEC);
    }

    public ComponentType<ChunkStore, ExampleComponent> getExampleComponentType() { return this.exampleComponentType; }
    public ComponentType<ChunkStore, GasNetworkComponent> getGasNetworkComponentType() { return this.gasNetworkComponentType; }

    public ResourceType<EntityStore, ExampleNetworkResource> getExampleNetworkResourceType() { return this.exampleNetworkResourceType; }
    public ResourceType<EntityStore, GasNetworkResource> geGasNetworkResourceType() { return this.gasNetworkResourceType; }
}
