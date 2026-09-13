package com.syntren.sypass.fabric.platform;

import com.syntren.sypass.platform.PlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

public class FabricPlatformHelper implements PlatformHelper {

    @Override
    public String getPlatformName() {
        return "fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public void copyToClipboard(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.keyboardHandler != null && text != null) {
            client.keyboardHandler.setClipboard(text);
        }
    }
}
