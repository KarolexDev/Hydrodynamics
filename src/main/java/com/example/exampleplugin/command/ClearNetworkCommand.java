package com.example.exampleplugin.command;

import com.example.exampleplugin.resource.ExampleNetworkResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * This is an example command that will simply print the name of the plugin in chat when used.
 */
public class ClearNetworkCommand extends CommandBase {
    private final String pluginName;
    private final String pluginVersion;

    public ClearNetworkCommand(String pluginName, String pluginVersion) {
        super("clearBlockNetwork", "Prints a test message from the " + pluginName + " plugin.");
        this.setPermissionGroup(GameMode.Adventure); // Allows the command to be used by anyone, not just OP
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> storeRef = ctx.senderAsPlayerRef();
        ExampleNetworkResource network = storeRef.getStore().getResource(ExampleNetworkResource.getResourceType());

        network.clear();

        ctx.sendMessage(Message.raw("Network Cleared!"));
    }
}