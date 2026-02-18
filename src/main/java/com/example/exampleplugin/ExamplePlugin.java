package com.example.exampleplugin;

import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.interaction.ConfigurePipeInteraction;
import com.example.exampleplugin.resource.ExampleNetwork;
import com.example.exampleplugin.system.ExampleNetworkSystem;
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

    private ResourceType<EntityStore, ExampleNetwork> exampleNetworkResourceType;

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

        this.getCommandRegistry().registerCommand(new ExampleCommand(this.getName(), this.getManifest().getVersion().toString()));

        this.exampleComponentType = this.getChunkStoreRegistry().registerComponent(ExampleComponent.class, "ExampleComponent", ExampleComponent.CODEC);
        // this.exampleComponentType = this.getChunkStoreRegistry().registerComponent(ExampleComponent.class, "ExampleComponent", ExampleComponent.CODEC);

        // this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkAddedSystem());
        // this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkTickingSystem());
        this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkBlockPlaceEventSystem());

        this.getCodecRegistry(Interaction.CODEC)
                .register("ConfigurePipe", ConfigurePipeInteraction.class, ConfigurePipeInteraction.CODEC);

        this.exampleNetworkResourceType = this.getEntityStoreRegistry().registerResource(ExampleNetwork.class, ExampleNetwork::new);
    }

    public ComponentType<ChunkStore, ExampleComponent> getExampleComponentType() { return this.exampleComponentType; }

    public ResourceType<EntityStore, ExampleNetwork> getExampleNetworkResourceType() { return this.exampleNetworkResourceType; }
}
