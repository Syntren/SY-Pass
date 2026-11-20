package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.File;
import java.nio.file.Path;

/**
 * Shared UI formatting, password masking, and system utility helpers for SY-Pass screens.
 */
public final class GuiHelpers {

    public static final int PANEL_BG = 0xD0101010;
    public static final int PANEL_BORDER = 0xFF3C3C3C;

    private GuiHelpers() {}

    public static void applyPasswordMask(EditBox box, boolean revealed) {
        if (box == null) return;
        if (revealed) {
            box.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward(text, Style.EMPTY));
        } else {
            box.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        }
    }

    public static String onOffBadge(boolean enabled) {
        return enabled
                ? "§a" + Component.translatable("sypass.gui.settings.on").getString()
                : "§c" + Component.translatable("sypass.gui.settings.off").getString();
    }

    public static String onOffGrayBadge(boolean enabled) {
        return enabled
                ? "§a" + Component.translatable("sypass.gui.settings.on").getString()
                : "§7" + Component.translatable("sypass.gui.settings.off").getString();
    }

    public static void openExternalLink(Minecraft mc, Screen returnTo, String url) {
        if (mc == null) return;
        mc.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                Util.getPlatform().openUri(url);
            }
            mc.setScreen(returnTo);
        }, url, true));
    }

    public static void openConfigFolder() {
        openFolder(SYPassConfig.getConfigDir());
    }

    public static void openBackupsFolder() {
        openFolder(SYPassConfig.getConfigDir().resolve("backups"));
    }

    private static void openFolder(Path path) {
        File dir = path.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        Util.getPlatform().openFile(dir);
    }
}
