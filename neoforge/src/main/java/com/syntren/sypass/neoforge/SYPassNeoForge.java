package com.syntren.sypass.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.command.SYPassCommands;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.util.ChatProtectionMatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

@Mod(value = "sypass", dist = Dist.CLIENT)
public class SYPassNeoForge {

    private static KeyMapping openGuiKeyMapping;
    private static KeyMapping quickLoginKeyMapping;
    private static KeyMapping quickRegisterKeyMapping;

    public SYPassNeoForge(IEventBus modEventBus, ModContainer container) {
        SYPassCommon.init();

        // 1. Інтеграція меню конфігурації у список модів NeoForge
        container.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) -> new SYPassScreen(parent));

        // 2. Реєстрація клавіш у шині життєвого циклу моду
        modEventBus.addListener(this::onRegisterKeyMappings);

        // 3. Підписка на події NeoForge гри
        NeoForge.EVENT_BUS.register(this);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openGuiKeyMapping = new KeyMapping(
                "key.sypass.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category.sypass.title"
        );
        event.register(openGuiKeyMapping);

        quickLoginKeyMapping = new KeyMapping(
                "key.sypass.quick_login",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.sypass.title"
        );
        event.register(quickLoginKeyMapping);

        quickRegisterKeyMapping = new KeyMapping(
                "key.sypass.quick_register",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "category.sypass.title"
        );
        event.register(quickRegisterKeyMapping);
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(SYPassCommands.buildCommandTree(
                source -> Minecraft.getInstance(),
                (source, msg) -> {
                    if (source != null && msg != null) {
                        source.sendSuccess(() -> msg, false);
                    }
                },
                (source, msg) -> {
                    if (source != null && msg != null) {
                        source.sendFailure(msg);
                    }
                }
        ));
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        AutoLoginHandler.onClientTick(client);

        if (openGuiKeyMapping != null) {
            while (openGuiKeyMapping.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new SYPassScreen(null));
                }
            }
        }

        if (quickLoginKeyMapping != null) {
            while (quickLoginKeyMapping.consumeClick()) {
                AutoLoginHandler.executeManualLogin(client);
            }
        }

        if (quickRegisterKeyMapping != null) {
            while (quickRegisterKeyMapping.consumeClick()) {
                AutoLoginHandler.executeQuickRegister(client);
            }
        }
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client.getCurrentServer();
        if (server != null && client.getUser() != null) {
            AutoLoginHandler.onJoinServer(server.ip, client.getUser().getName());
        }
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AutoLoginHandler.onDisconnect();
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent.System event) {
        AutoLoginHandler.onIncomingMessage(event.getMessage().getString(), Minecraft.getInstance());
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent.Player event) {
        AutoLoginHandler.onIncomingMessage(event.getMessage().getString(), Minecraft.getInstance());
    }

    @SuppressWarnings("null")
    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        if (!SYPassConfig.isChatLeakProtectionEnabled()) return;

        String message = event.getMessage();
        if (message == null || message.isBlank()) return;

        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null) return;

        ServerData server = client.getCurrentServer();
        String serverAddress = (server != null && server.ip != null) ? server.ip : "";

        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                message,
                serverAddress,
                SYPassConfig.getChatProtectionScope()
        );

        if (leaks) {
            event.setCanceled(true);
            String msgKey = (SYPassConfig.getChatProtectionScope() == SYPassConfig.ChatProtectionScope.ALL_SERVERS)
                    ? "sypass.chat.blocked_leak_all"
                    : "sypass.chat.blocked_leak";
            Component chatMessage = Component.translatable(msgKey).withStyle(ChatFormatting.RED);
            player.displayClientMessage(Objects.requireNonNull(chatMessage), false);
        }
    }
}
