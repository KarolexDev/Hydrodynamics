package com.example.exampleplugin.command;

import com.example.exampleplugin.gasnetwork.GasNetworkResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ShowBlockNetworks extends CommandBase {
    public ShowBlockNetworks() {
        super("showbn", "Prints all block networks in chat.");
        this.setPermissionGroup(GameMode.Creative);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> storeRef = ctx.senderAsPlayerRef();
        GasNetworkResource network = storeRef.getStore().getResource(GasNetworkResource.getResourceType());

        ctx.sendMessage(Message.raw(network.toString()));
    }
}