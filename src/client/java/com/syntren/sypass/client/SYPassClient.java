package com.syntren.sypass.client;

import com.syntren.sypass.command.SYPassCommands;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class SYPassClient implements ClientModInitializer {
	private static KeyBinding openGuiKeyBinding;

	@Override
	public void onInitializeClient() {
		SYPassConfig.load();
		PasswordManager.init();
		SYPassCommands.register();
		AutoLoginHandler.register();

		openGuiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.sypass.open_gui",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				"category.sypass.title"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openGuiKeyBinding.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new SYPassScreen(null));
				}
			}
		});
	}
}