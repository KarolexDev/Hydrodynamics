package com.example.exampleplugin.command;

import com.example.exampleplugin.gasnetwork.GasNetworkResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ClearAllBlockNetworks extends CommandBase {

    public ClearAllBlockNetworks() {
        super("clearBlockNetwork", "Empties all block network metadata (intended only for debugging purposes!)");
        this.setPermissionGroup(GameMode.Creative);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> storeRef = ctx.senderAsPlayerRef();
        GasNetworkResource network = storeRef.getStore().getResource(GasNetworkResource.getResourceType());
        network.clear();
        ctx.sendMessage(Message.raw("All block network metadata cleared!"));
    }
}