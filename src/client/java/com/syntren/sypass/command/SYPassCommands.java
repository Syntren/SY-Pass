package com.syntren.sypass.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

public class SYPassCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("sypass")
                    .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                    .executes(context -> savePassword(context, StringArgumentType.getString(context, "password"), "/login"))
                            )
                    )
                    .then(ClientCommandManager.literal("setcustom")
                            .then(ClientCommandManager.argument("command", StringArgumentType.string())
                                    .then(ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                            .executes(context -> savePassword(context,
                                                    StringArgumentType.getString(context, "password"),
                                                    StringArgumentType.getString(context, "command")))
                                    )
                            )
                    )
                    .then(ClientCommandManager.literal("remove")
                            .executes(SYPassCommands::removePassword)
                    )
                    .then(ClientCommandManager.literal("generate")
                            .executes(context -> generatePassword(context, 16))
                            .then(ClientCommandManager.argument("length", com.mojang.brigadier.arguments.IntegerArgumentType.integer(6, 64))
                                    .executes(context -> generatePassword(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "length")))
                            )
                    )
                    .then(ClientCommandManager.literal("register")
                            .executes(context -> quickRegister(context, 16))
                            .then(ClientCommandManager.argument("length", com.mojang.brigadier.arguments.IntegerArgumentType.integer(6, 64))
                                    .executes(context -> quickRegister(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "length")))
                            )
                    )
                    .then(ClientCommandManager.literal("quickreg")
                            .executes(context -> quickRegister(context, 16))
                            .then(ClientCommandManager.argument("length", com.mojang.brigadier.arguments.IntegerArgumentType.integer(6, 64))
                                    .executes(context -> quickRegister(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "length")))
                            )
                    )
            );
        });
    }

    private static int generatePassword(CommandContext<FabricClientCommandSource> context, int length) {
        String generated = com.syntren.sypass.util.PasswordGenerator.generate(length);
        if (context.getSource().getClient() != null && context.getSource().getClient().keyboard != null) {
            context.getSource().getClient().keyboard.setClipboard(generated);
        }
        context.getSource().sendFeedback(Text.translatable("sypass.command.generated", generated));
        com.syntren.sypass.gui.SYPassToast.show(
                Text.translatable("sypass.toast.generator.title"),
                Text.translatable("sypass.toast.generator.desc")
        );
        return 1;
    }

    private static int quickRegister(CommandContext<FabricClientCommandSource> context, int length) {
        ServerInfo server = context.getSource().getClient().getCurrentServerEntry();
        if (server == null) {
            context.getSource().sendError(Text.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = context.getSource().getClient().getSession().getUsername();
        boolean success = AutoLoginHandler.executeQuickRegister(context.getSource().getClient(), length);
        if (success) {
            context.getSource().sendFeedback(Text.translatable("sypass.command.registered", username, server.address));
            return 1;
        }
        return 0;
    }

    private static int savePassword(CommandContext<FabricClientCommandSource> context, String password, String command) {
        ServerInfo server = context.getSource().getClient().getCurrentServerEntry();
        if (server == null) {
            context.getSource().sendError(Text.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = context.getSource().getClient().getSession().getUsername();

        String cleanPassword = password.trim().replaceAll("^\"|\"$", "");
        String cleanCommand = command.trim().replaceAll("\"", "");
        if (cleanCommand.startsWith("/")) {
            cleanCommand = cleanCommand.substring(1);
        }

        String serverIp = server.address;
        PasswordManager.savePassword(serverIp, username, cleanPassword, cleanCommand);

        if (SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.pushSingleItemAsync(serverIp, username, cleanPassword, cleanCommand);
        }

        context.getSource().sendFeedback(Text.translatable("sypass.command.saved", username, serverIp));
        return 1;
    }

    private static int removePassword(CommandContext<FabricClientCommandSource> context) {
        ServerInfo server = context.getSource().getClient().getCurrentServerEntry();
        if (server == null) {
            context.getSource().sendError(Text.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = context.getSource().getClient().getSession().getUsername();
        String serverIp = server.address;

        PasswordManager.AccountData acc = PasswordManager.getPassword(serverIp, username);
        String remoteId = (acc != null) ? acc.remoteId() : "";
        boolean wasSynced = (acc != null && acc.isSynced());

        PasswordManager.removePassword(serverIp, username);

        if (wasSynced && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.deleteSingleItemAsync(serverIp, username, remoteId);
        }

        context.getSource().sendFeedback(Text.translatable("sypass.command.removed", username, serverIp));
        return 1;
    }
}