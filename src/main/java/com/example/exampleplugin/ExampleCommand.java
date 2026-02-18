package com.example.exampleplugin;

import com.example.exampleplugin.resource.ExampleNetwork;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * This is an example command that will simply print the name of the plugin in chat when used.
 */
public class ExampleCommand extends CommandBase {
    private final String pluginName;
    private final String pluginVersion;

    public ExampleCommand(String pluginName, String pluginVersion) {
        super("networks", "Finds all block networks on the server");
        this.setPermissionGroup(GameMode.Creative); // Allows the command to be used by anyone, not just OP
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ExampleNetwork resource = ctx.senderAsPlayerRef().getStore().getResource(ExampleNetwork.getResourceType());
        ctx.sendMessage(Message.raw(
                resource.getAllNetworks().toString()
        ));
    }
}