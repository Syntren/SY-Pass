package com.syntren.sypass.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.command.SYPassCommands;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.gui.ServerIconManager;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.util.ChatProtectionMatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class SYPassFabric implements ClientModInitializer {
    private static KeyMapping openGuiKeyMapping;
    private static KeyMapping quickLoginKeyMapping;
    private static KeyMapping quickRegisterKeyMapping;

    @Override
    public void onInitializeClient() {
        SYPassCommon.init();

        // 1. Реєстрація команд
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(SYPassCommands.buildCommandTree(
                    FabricClientCommandSource::getClient,
                    FabricClientCommandSource::sendFeedback,
                    FabricClientCommandSource::sendError
            ));
        });

        // 2. Реєстрація хоткеїв
        openGuiKeyMapping = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sypass.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category.sypass.title"
        ));

        quickLoginKeyMapping = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sypass.quick_login",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.sypass.title"
        ));

        quickRegisterKeyMapping = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sypass.quick_register",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "category.sypass.title"
        ));

        // 3. Відстеження входу на сервер
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            if (server != null && client.getUser() != null) {
                AutoLoginHandler.onJoinServer(server.ip, client.getUser().getName());
            }
        });

        // 4. Відстеження виходу з сервера
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AutoLoginHandler.onDisconnect();
        });

        // 5. Відстеження тіків та натискання клавіш
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AutoLoginHandler.onClientTick(client);

            while (openGuiKeyMapping.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new SYPassScreen(null));
                }
            }
            while (quickLoginKeyMapping.consumeClick()) {
                AutoLoginHandler.executeManualLogin(client);
            }
            while (quickRegisterKeyMapping.consumeClick()) {
                AutoLoginHandler.executeQuickRegister(client);
            }
        });

        // 6. Отримання повідомлень від сервера (Smart Auto-Login)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null) {
                AutoLoginHandler.onIncomingMessage(message.getString(), Minecraft.getInstance());
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (sender == null && message != null) {
                AutoLoginHandler.onIncomingMessage(message.getString(), Minecraft.getInstance());
            }
        });

        // 7. Захист від витоку паролів у чат та команди
        ClientSendMessageEvents.ALLOW_CHAT.register(SYPassFabric::checkLeakAndNotify);
        ClientSendMessageEvents.ALLOW_COMMAND.register(SYPassFabric::checkLeakAndNotify);

        // 8. Очищення кешу іконок при зупинці гри
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ServerIconManager.clearCache());
    }

    private static boolean checkLeakAndNotify(String message) {
        if (!SYPassConfig.isChatLeakProtectionEnabled() || message == null || message.isBlank()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return true;

        ServerData server = client.getCurrentServer();
        String serverAddress = (server != null) ? server.ip : "";

        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                message,
                serverAddress,
                SYPassConfig.getChatProtectionScope()
        );

        if (leaks) {
            String msgKey = (SYPassConfig.getChatProtectionScope() == SYPassConfig.ChatProtectionScope.ALL_SERVERS)
                    ? "sypass.chat.blocked_leak_all"
                    : "sypass.chat.blocked_leak";
            client.player.displayClientMessage(
                    Component.translatable(msgKey).withStyle(ChatFormatting.RED),
                    false
            );
            return false;
        }

        return true;
    }
}
