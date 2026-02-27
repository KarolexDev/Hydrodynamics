package com.example.exampleplugin;

import com.example.exampleplugin.component.ExampleComponent;
import com.example.exampleplugin.interaction.ConfigurePipeInteraction;
import com.example.exampleplugin.system.ExampleNetworkSystem;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

public class ExamplePlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static ExamplePlugin instance;

    private ComponentType<ChunkStore, ExampleComponent> exampleComponentType;

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


        this.getEntityStoreRegistry().registerSystem(new ExampleNetworkSystem.NetworkTickingSystem());

        this.getCodecRegistry(Interaction.CODEC)
                .register("ConfigurePipe", ConfigurePipeInteraction.class, ConfigurePipeInteraction.CODEC);
    }

    public ComponentType<ChunkStore, ExampleComponent> getExampleComponentType() { return this.exampleComponentType; }
}
