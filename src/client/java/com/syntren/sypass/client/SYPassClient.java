package com.syntren.sypass.client;

import com.syntren.sypass.command.SYPassCommands;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.ServerIconManager;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class SYPassClient implements ClientModInitializer {
	private static KeyBinding openGuiKeyBinding;
	private static KeyBinding quickLoginKeyBinding;
	private static KeyBinding quickRegisterKeyBinding;

	@Override
	public void onInitializeClient() {
		SYPassConfig.load();
		PasswordManager.init();
		SYPassCommands.register();
		AutoLoginHandler.register();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ServerIconManager.clearCache());

		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (!SYPassConfig.isChatLeakProtectionEnabled() || message == null || message.isBlank()) {
				return true;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null || client.player == null) return true;

			ServerInfo server = client.getCurrentServerEntry();
			String serverAddress = (server != null) ? server.address : "";

			boolean leaks = com.syntren.sypass.util.ChatProtectionMatcher.checkMessageLeaks(
					message,
					serverAddress,
					SYPassConfig.getChatProtectionScope()
			);

			if (leaks) {
				String msgKey = (SYPassConfig.getChatProtectionScope() == SYPassConfig.ChatProtectionScope.ALL_SERVERS)
						? "sypass.chat.blocked_leak_all"
						: "sypass.chat.blocked_leak";
				client.player.sendMessage(
						Text.translatable(msgKey).formatted(Formatting.RED),
						false
				);
				return false;
			}

			return true;
		});

		openGuiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.sypass.open_gui",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				"category.sypass.title"
		));

		quickLoginKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.sypass.quick_login",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"category.sypass.title"
		));

		quickRegisterKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.sypass.quick_register",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"category.sypass.title"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openGuiKeyBinding.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new SYPassScreen(null));
				}
			}
			while (quickLoginKeyBinding.wasPressed()) {
				AutoLoginHandler.executeManualLogin(client);
			}
			while (quickRegisterKeyBinding.wasPressed()) {
				AutoLoginHandler.executeQuickRegister(client);
			}
		});
	}
}