package com.syntren.sypass.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.command.SYPassCommands;
import com.syntren.sypass.fabric.platform.FabricPlatformHelper;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.gui.ServerIconManager;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.util.ChatLeakGuard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;

public class SYPassFabric implements ClientModInitializer {
    private static KeyMapping openGuiKeyMapping;
    private static KeyMapping quickLoginKeyMapping;
    private static KeyMapping quickRegisterKeyMapping;

    @Override
    public void onInitializeClient() {
        SYPassCommon.init(new FabricPlatformHelper());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(SYPassCommands.buildCommandTree(
                        FabricClientCommandSource::getClient,
                        FabricClientCommandSource::sendFeedback,
                        FabricClientCommandSource::sendError
                ))
        );

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

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            if (server != null && client.getUser() != null) {
                AutoLoginHandler.onJoinServer(server.ip, client.getUser().getName());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AutoLoginHandler.onDisconnect());

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

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> ChatLeakGuard.allowOutgoing(message, false));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> ChatLeakGuard.allowOutgoing(command, true));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ServerIconManager.clearCache());
    }
}
