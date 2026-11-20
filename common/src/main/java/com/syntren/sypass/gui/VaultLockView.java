package com.syntren.sypass.gui;

import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Handles the Master Password / PIN vault lock screen with masked input and two-step reset confirmation.
 */
final class VaultLockView {

    private final SYPassScreen screen;
    private boolean pendingResetConfirm = false;

    VaultLockView(SYPassScreen screen) {
        this.screen = screen;
    }

    void init(int screenWidth, int screenHeight, int contentWidth, Font font) {
        int cardWidth = Math.min(280, contentWidth);
        int cardX = (screenWidth - cardWidth) / 2;
        int y = (screenHeight - 140) / 2;

        EditBox unlockPassBox = new EditBox(font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.lock.desc"));
        unlockPassBox.setMaxLength(128);
        unlockPassBox.setHint(Component.translatable("sypass.gui.lock.desc"));
        GuiHelpers.applyPasswordMask(unlockPassBox, false);
        unlockPassBox.setFocused(true);
        screen.registerWidget(unlockPassBox);

        Button unlockBtn = Button.builder(Component.translatable("sypass.gui.lock.unlock_btn"), b -> {
            String val = unlockPassBox.getValue();
            if (val.isBlank()) return;
            this.pendingResetConfirm = false;
            if (PasswordManager.unlockVault(val.toCharArray())) {
                screen.setStatusMessage("");
                screen.rebuildView();
            } else {
                screen.setStatusMessage("§c" + Component.translatable("sypass.gui.lock.wrong_pass").getString());
            }
        }).bounds(cardX, y + 52, cardWidth, 20).build();
        screen.registerWidget(unlockBtn);

        Component resetLabel = pendingResetConfirm
                ? Component.literal("§4✔? ").append(Component.translatable("sypass.gui.lock.reset_btn"))
                : Component.translatable("sypass.gui.lock.reset_btn");

        Button resetBtn = Button.builder(resetLabel, b -> {
            if (!pendingResetConfirm) {
                pendingResetConfirm = true;
                screen.setStatusMessage("§6" + Component.translatable("sypass.gui.lock.reset_confirm").getString());
                screen.rebuildView();
                return;
            }
            pendingResetConfirm = false;
            if (PasswordManager.resetVaultWithBitwarden()) {
                screen.setStatusMessage("§aVault reset. Unlocked.");
                screen.rebuildView();
            }
        }).bounds(cardX, y + 78, cardWidth, 20)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.lock.reset_confirm")))
          .build();
        screen.registerWidget(resetBtn);

        Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
            pendingResetConfirm = false;
            screen.onClose();
        }).bounds(cardX, y + 104, cardWidth, 20).build();
        screen.registerWidget(backBtn);
    }

    void render(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, String statusMessage) {
        int y = (screenHeight - 140) / 2;
        guiGraphics.drawCenteredString(
                font,
                Component.translatable("sypass.gui.lock.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                screenWidth / 2,
                y + 5,
                0xFFFFFF
        );
        if (statusMessage != null && !statusMessage.isBlank()) {
            guiGraphics.drawCenteredString(font, Component.literal(statusMessage), screenWidth / 2, y + 132, 0xFFFFFF);
        }
    }

    void resetState() {
        this.pendingResetConfirm = false;
    }
}
