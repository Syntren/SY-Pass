package com.syntren.sypass.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.PasswordGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.Function;

public class SYPassCommands {

    public static <S> LiteralArgumentBuilder<S> buildCommandTree(
            Function<S, Minecraft> clientExtractor,
            BiConsumer<S, Component> feedbackConsumer,
            BiConsumer<S, Component> errorConsumer
    ) {
        return LiteralArgumentBuilder.<S>literal("sypass")
                .then(LiteralArgumentBuilder.<S>literal("set")
                        .then(RequiredArgumentBuilder.<S, String>argument("password", StringArgumentType.greedyString())
                                .executes(ctx -> handleSavePassword(ctx, clientExtractor, feedbackConsumer, errorConsumer,
                                        StringArgumentType.getString(ctx, "password"), "/login"))
                        )
                )
                .then(LiteralArgumentBuilder.<S>literal("setcustom")
                        .then(RequiredArgumentBuilder.<S, String>argument("command", StringArgumentType.string())
                                .then(RequiredArgumentBuilder.<S, String>argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> handleSavePassword(ctx, clientExtractor, feedbackConsumer, errorConsumer,
                                                StringArgumentType.getString(ctx, "password"),
                                                StringArgumentType.getString(ctx, "command")))
                                )
                        )
                )
                .then(LiteralArgumentBuilder.<S>literal("remove")
                        .executes(ctx -> handleRemovePassword(ctx, clientExtractor, feedbackConsumer, errorConsumer))
                )
                .then(LiteralArgumentBuilder.<S>literal("generate")
                        .executes(ctx -> handleGeneratePassword(ctx, feedbackConsumer, SYPassConfig.getDefaultPasswordLength()))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("length", IntegerArgumentType.integer(6, 64))
                                .executes(ctx -> handleGeneratePassword(ctx, feedbackConsumer, IntegerArgumentType.getInteger(ctx, "length")))
                        )
                )
                .then(LiteralArgumentBuilder.<S>literal("register")
                        .executes(ctx -> handleQuickRegister(ctx, clientExtractor, feedbackConsumer, errorConsumer, SYPassConfig.getDefaultPasswordLength()))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("length", IntegerArgumentType.integer(6, 64))
                                .executes(ctx -> handleQuickRegister(ctx, clientExtractor, feedbackConsumer, errorConsumer, IntegerArgumentType.getInteger(ctx, "length")))
                        )
                )
                .then(LiteralArgumentBuilder.<S>literal("quickreg")
                        .executes(ctx -> handleQuickRegister(ctx, clientExtractor, feedbackConsumer, errorConsumer, SYPassConfig.getDefaultPasswordLength()))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("length", IntegerArgumentType.integer(6, 64))
                                .executes(ctx -> handleQuickRegister(ctx, clientExtractor, feedbackConsumer, errorConsumer, IntegerArgumentType.getInteger(ctx, "length")))
                        )
                );
    }

    private static <S> int handleGeneratePassword(CommandContext<S> context, BiConsumer<S, Component> feedback, int length) {
        String generated = PasswordGenerator.generate(length);
        PlatformHelper.get().copyToClipboard(generated);
        feedback.accept(context.getSource(), Component.translatable("sypass.command.generated", generated));
        SYPassToast.show(
                Component.translatable("sypass.toast.generator.title"),
                Component.translatable("sypass.toast.generator.desc")
        );
        return 1;
    }

    private static <S> int handleQuickRegister(
            CommandContext<S> context,
            Function<S, Minecraft> clientExtractor,
            BiConsumer<S, Component> feedback,
            BiConsumer<S, Component> error,
            int length
    ) {
        Minecraft client = clientExtractor.apply(context.getSource());
        if (client == null || client.getCurrentServer() == null) {
            error.accept(context.getSource(), Component.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = client.getUser().getName();
        String serverIp = client.getCurrentServer().ip;
        boolean success = AutoLoginHandler.executeQuickRegister(client, length);
        if (success) {
            feedback.accept(context.getSource(), Component.translatable("sypass.command.registered", username, serverIp));
            return 1;
        }
        return 0;
    }

    private static <S> int handleSavePassword(
            CommandContext<S> context,
            Function<S, Minecraft> clientExtractor,
            BiConsumer<S, Component> feedback,
            BiConsumer<S, Component> error,
            String password,
            String command
    ) {
        Minecraft client = clientExtractor.apply(context.getSource());
        if (client == null || client.getCurrentServer() == null) {
            error.accept(context.getSource(), Component.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = client.getUser().getName();
        String cleanPassword = password.trim().replaceAll("^\"|\"$", "");
        String cleanCommand = command.trim().replaceAll("\"", "");
        if (cleanCommand.startsWith("/")) {
            cleanCommand = cleanCommand.substring(1);
        }

        String serverIp = client.getCurrentServer().ip;
        PasswordManager.savePassword(serverIp, username, cleanPassword, cleanCommand);

        if (SYPassConfig.isBitwardenEnabled() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.pushSingleItemAsync(serverIp, username, cleanPassword, cleanCommand);
        }

        feedback.accept(context.getSource(), Component.translatable("sypass.command.saved", username, serverIp));
        return 1;
    }

    private static <S> int handleRemovePassword(
            CommandContext<S> context,
            Function<S, Minecraft> clientExtractor,
            BiConsumer<S, Component> feedback,
            BiConsumer<S, Component> error
    ) {
        Minecraft client = clientExtractor.apply(context.getSource());
        if (client == null || client.getCurrentServer() == null) {
            error.accept(context.getSource(), Component.translatable("sypass.command.server_only"));
            return 0;
        }

        String username = client.getUser().getName();
        String serverIp = client.getCurrentServer().ip;

        PasswordManager.AccountData acc = PasswordManager.getPassword(serverIp, username);
        String remoteId = (acc != null) ? acc.remoteId() : "";
        boolean wasSynced = (acc != null && acc.isSynced());

        PasswordManager.removePassword(serverIp, username);

        if (wasSynced && SYPassConfig.isBitwardenEnabled() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.deleteSingleItemAsync(serverIp, username, remoteId);
        }

        feedback.accept(context.getSource(), Component.translatable("sypass.command.removed", username, serverIp));
        return 1;
    }
}
