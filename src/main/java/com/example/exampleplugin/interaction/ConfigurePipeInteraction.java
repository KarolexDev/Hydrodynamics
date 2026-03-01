package com.example.exampleplugin.interaction;

import com.example.exampleplugin.ExamplePlugin;
import com.example.exampleplugin.component.ExampleComponent;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jetbrains.annotations.NotNull;

// TODO: Convert to component system?
public class ConfigurePipeInteraction extends SimpleInteraction {
    public static final BuilderCodec<ConfigurePipeInteraction> CODEC =
            BuilderCodec.builder(ConfigurePipeInteraction.class, ConfigurePipeInteraction::new,
                    SimpleInteraction.CODEC).build();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // any time an interaction happens
    @Override
    protected void tick0(boolean firstRun, float time, @NotNull InteractionType type, @NotNull InteractionContext context, @NotNull CooldownHandler cooldownHandler) {
        // The player who interacted with the block
        Ref<EntityStore> owningEntity = context.getOwningEntity();
        Store<EntityStore> store = owningEntity.getStore();

        // Player store
        Player player = store.getComponent(owningEntity, Player.getComponentType());
        if(player == null) return;

        // World of the player
        World world = player.getWorld();
        if(world == null) return;

        // Get the block data we interacted with
        BlockPosition targetBlock = context.getTargetBlock();
        Vector3i targetVec = new Vector3i(targetBlock.x, targetBlock.y, targetBlock.z);
        if(targetBlock == null) return;


        if(type == InteractionType.Use) {
            WorldChunk worldChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(targetVec.x, targetVec.z));
            assert worldChunk != null;

            Ref<ChunkStore> componentRef = worldChunk.getBlockComponentEntity(targetVec.x, targetVec.y, targetVec.z);
            if (componentRef == null) { return; }

            if (world.getChunkStore().getStore().getComponent(componentRef, ExamplePlugin.getInstance().getExampleComponentType()) == null) { return; }
            ExampleComponent exampleComponent = (ExampleComponent) world.getChunkStore().getStore().getComponent(componentRef, ExamplePlugin.getInstance().getExampleComponentType());

            player.sendMessage(Message.raw("Interacted with this custom interaction code..."));

            if(exampleComponent != null) {
                // Do smth with ExampleComponent...
            } else {
                player.sendMessage(Message.raw("This block does NOT have a Thermal Component!"));
            }
        }
    }
}