package com.syntren.sypass.neoforge.platform;

import com.syntren.sypass.platform.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class NeoForgePlatformHelper implements PlatformHelper {

    @Override
    public String getPlatformName() {
        return "neoforge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void copyToClipboard(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.keyboardHandler != null && text != null) {
            client.keyboardHandler.setClipboard(text);
        }
    }
}
