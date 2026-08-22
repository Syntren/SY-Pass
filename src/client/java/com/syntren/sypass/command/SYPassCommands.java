package com.syntren.sypass.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
            );
        });
    }

    private static int savePassword(CommandContext<FabricClientCommandSource> context, String password, String command) {
        ServerInfo server = context.getSource().getClient().getCurrentServerEntry();
        if (server == null) {
            context.getSource().sendError(Text.literal("§c[SY-Pass] Цю команду можна виконувати лише під час гри на сервері!"));
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
        context.getSource().sendFeedback(Text.literal("§a[SY-Pass] Збережено пароль для акаунта §e" + username + " §aна сервері " + serverIp + "!"));
        return 1;
    }

    private static int removePassword(CommandContext<FabricClientCommandSource> context) {
        ServerInfo server = context.getSource().getClient().getCurrentServerEntry();
        if (server == null) {
            context.getSource().sendError(Text.literal("§c[SY-Pass] Цю команду можна виконувати лише під час гри на сервері!"));
            return 0;
        }

        String username = context.getSource().getClient().getSession().getUsername();
        String serverIp = server.address;

        PasswordManager.removePassword(serverIp, username);
        context.getSource().sendFeedback(Text.literal("§e[SY-Pass] Пароль для акаунта §e" + username + " §eна сервері " + serverIp + " видалено."));
        return 1;
    }
}