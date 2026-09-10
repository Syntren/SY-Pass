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

import java.util.Map;

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
			if (!SYPassConfig.isChatLeakProtectionEnabled() || message == null || message.isEmpty()) {
				return true;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null || client.player == null) return true;

			ServerInfo server = client.getCurrentServerEntry();
			if (server == null || server.address == null || server.address.isBlank()) return true;

			Map<String, PasswordManager.AccountData> accounts = PasswordManager.getServerAccounts(server.address);
			if (accounts.isEmpty()) return true;

			int msgLen = message.length();
			for (PasswordManager.AccountData acc : accounts.values()) {
				String pass = acc.password();
				if (pass != null && !pass.isBlank()) {
					int passLen = pass.length();
					if (msgLen < passLen) continue; // Швидка евристика: якщо повідомлення коротше за пароль, перевірка пропускається

					boolean leaks = passLen >= 3 ? message.contains(pass) : message.trim().equals(pass);
					if (leaks) {
						client.player.sendMessage(
								Text.translatable("sypass.chat.blocked_leak").formatted(Formatting.RED),
								false
						);
						return false;
					}
				}
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
				AutoLoginHandler.executeQuickRegister(client, 16);
			}
		});
	}
}