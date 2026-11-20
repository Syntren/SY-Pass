package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.AccountData;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.ServerAddressUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * Scrollable vanilla Minecraft list widget displaying saved server credentials.
 */
public class PasswordListWidget extends ContainerObjectSelectionList<PasswordListWidget.PasswordEntry> {

    private static final String[] LOADING_DOTS = new String[]{"§e.", "§e..", "§e..."};

    private final SYPassScreen parentScreen;
    private final int fixedRowWidth;

    public PasswordListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight, int fixedRowWidth, SYPassScreen parentScreen) {
        super(minecraft, width, height, y, itemHeight);
        this.parentScreen = parentScreen;
        this.fixedRowWidth = fixedRowWidth;
    }

    public void refresh(String query, SYPassScreen.SortMode sortMode, boolean onlyFavorites) {
        this.clearEntries();
        Map<String, Map<String, AccountData>> allData = PasswordManager.getAllData();
        List<PasswordEntryData> dataList = new ArrayList<>();

        String lowerQuery = (query != null) ? query.toLowerCase(Locale.ROOT).trim() : "";

        for (Map.Entry<String, Map<String, AccountData>> sEntry : allData.entrySet()) {
            String serverIp = sEntry.getKey();
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                String username = aEntry.getKey();
                AccountData acc = aEntry.getValue();

                if (onlyFavorites && !acc.isFavorite()) {
                    continue;
                }

                if (!lowerQuery.isEmpty()
                        && !serverIp.toLowerCase(Locale.ROOT).contains(lowerQuery)
                        && !username.toLowerCase(Locale.ROOT).contains(lowerQuery)
                        && !acc.command().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    continue;
                }

                dataList.add(new PasswordEntryData(serverIp, username, acc));
            }
        }

        switch (sortMode) {
            case FAVORITES_FIRST -> dataList.sort((a, b) -> {
                if (a.data.isFavorite() != b.data.isFavorite()) {
                    return b.data.isFavorite() ? 1 : -1;
                }
                int sComp = a.serverIp.compareToIgnoreCase(b.serverIp);
                return (sComp != 0) ? sComp : a.username.compareToIgnoreCase(b.username);
            });
            case ALPHABETICAL -> dataList.sort((a, b) -> {
                int sComp = a.serverIp.compareToIgnoreCase(b.serverIp);
                return (sComp != 0) ? sComp : a.username.compareToIgnoreCase(b.username);
            });
            case RECENT -> dataList.sort((a, b) -> Long.compare(b.data.lastUsed(), a.data.lastUsed()));
        }

        for (PasswordEntryData item : dataList) {
            this.addEntry(new PasswordEntry(this.parentScreen, item.serverIp, item.username, item.data));
        }
    }

    @Override
    public int getRowWidth() {
        return this.fixedRowWidth;
    }

    @Override
    protected int getScrollbarPosition() {
        return (this.width + getRowWidth()) / 2 + 4;
    }

    private record PasswordEntryData(String serverIp, String username, AccountData data) {}

    public static class PasswordEntry extends ContainerObjectSelectionList.Entry<PasswordEntry> {
        private final SYPassScreen screen;
        private final String serverIp;
        private final String username;
        private final AccountData data;
        private final String key;

        private final List<AbstractWidget> children = new ArrayList<>();
        private Button deleteBwBtn = null;

        public PasswordEntry(SYPassScreen screen, String serverIp, String username, AccountData data) {
            this.screen = screen;
            this.serverIp = serverIp;
            this.username = username;
            this.data = data;
            this.key = serverIp + ":::" + username;

            boolean isStreamer = SYPassConfig.isStreamerModeEnabled();
            boolean isRevealed = !isStreamer && screen.isPasswordRevealed(key);
            boolean canDeleteFromBw = SYPassConfig.isBitwardenEnabled() && data.isSynced() && BitwardenManager.hasActiveSession();

            // 1. Favorite button
            Button favBtn = Button.builder(Component.literal(data.isFavorite() ? "§e★" : "§7☆"), btn -> {
                PasswordManager.toggleFavorite(serverIp, username);
                screen.refreshPasswordList();
            }).bounds(0, 0, 20, 20)
              .tooltip(Tooltip.create(Component.translatable(data.isFavorite() ? "sypass.gui.button.unfavorite.tooltip" : "sypass.gui.button.favorite.tooltip")))
              .build();
            children.add(favBtn);

            // 2. Copy button
            Button copyBtn = Button.builder(Component.literal("📋"), btn -> {
                PasswordManager.updateLastUsed(serverIp, username);
                PasswordManager.copyPasswordToClipboard(serverIp, username);
                screen.setStatusMessage(Component.translatable("sypass.gui.status.copied", username).getString());
            }).bounds(0, 0, 20, 20)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.copy.tooltip")))
              .build();
            children.add(copyBtn);

            // 3. Reveal / Hide toggle
            Button toggleEyeBtn = Button.builder(Component.literal(isRevealed ? "§a●" : "§7○"), btn -> {
                if (SYPassConfig.isStreamerModeEnabled()) return;
                boolean nowRevealed = screen.togglePasswordRevealed(key);
                btn.setMessage(Component.literal(nowRevealed ? "§a●" : "§7○"));
                btn.setTooltip(Tooltip.create(Component.translatable(nowRevealed ? "sypass.gui.button.hide" : "sypass.gui.button.show")));
            }).bounds(0, 0, 20, 20)
              .tooltip(Tooltip.create(Component.translatable(isStreamer
                      ? "sypass.gui.settings.streamer_mode.tooltip"
                      : (isRevealed ? "sypass.gui.button.hide" : "sypass.gui.button.show"))))
              .build();
            if (isStreamer) {
                toggleEyeBtn.active = false;
            }
            children.add(toggleEyeBtn);

            // 4. Edit button
            Button editBtn = Button.builder(Component.literal("✎"), btn -> {
                Minecraft mc = Minecraft.getInstance();
                AccountData realAcc = PasswordManager.getPassword(serverIp, username);
                String pass = (realAcc != null) ? realAcc.getPasswordAsString() : "";
                mc.setScreen(new EditPasswordScreen(screen, serverIp, username, pass, data.command(), data.isSynced()));
            }).bounds(0, 0, 20, 20)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.edit.tooltip")))
              .build();
            children.add(editBtn);

            // 5. Delete from Bitwarden only (if synced)
            if (canDeleteFromBw) {
                boolean isDeleting = key.equals(screen.getActiveBwDeletingKey());
                boolean isSuccess = key.equals(screen.getActiveBwSuccessKey());
                boolean isPendingBw = key.equals(screen.getPendingBwDeleteKey());

                String bwLabel;
                if (isDeleting) {
                    int dotIdx = (int) ((System.currentTimeMillis() / 350L) % 3);
                    bwLabel = LOADING_DOTS[dotIdx];
                } else if (isSuccess) {
                    bwLabel = "§a✔";
                } else if (isPendingBw) {
                    bwLabel = "§4✔?";
                } else {
                    bwLabel = "§c☁-";
                }

                this.deleteBwBtn = Button.builder(Component.literal(bwLabel), btn -> {
                    if (key.equals(screen.getActiveBwDeletingKey()) || key.equals(screen.getActiveBwSuccessKey())) return;
                    screen.setPendingDeleteKey(null);

                    if (key.equals(screen.getPendingBwDeleteKey())) {
                        screen.setPendingBwDeleteKey(null);
                        screen.setActiveBwDeletingKey(key);
                        btn.active = false;
                        btn.setMessage(Component.literal("§e."));
                        screen.setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());

                        BitwardenManager.deleteFromBitwardenOnlyAsync(serverIp, username, data.remoteId()).thenAccept(success -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> {
                                screen.setActiveBwDeletingKey(null);
                                if (success) {
                                    screen.setActiveBwSuccess(key, System.currentTimeMillis() + 2000L);
                                    String dispIp = SYPassConfig.isStreamerModeEnabled() ? ServerAddressUtil.mask(serverIp) : serverIp;
                                    screen.setStatusMessage(Component.translatable("sypass.gui.status.deleted_bw", username, dispIp).getString());
                                    btn.setMessage(Component.literal("§a✔"));
                                } else {
                                    screen.setStatusMessage("§c" + Component.translatable("sypass.gui.status.deleted_bw_failed", username).getString());
                                    screen.refreshPasswordList();
                                }
                            });
                        });
                    } else {
                        screen.setPendingBwDeleteKey(key);
                        btn.setMessage(Component.literal("§4✔?"));
                        btn.setTooltip(Tooltip.create(Component.translatable("sypass.gui.button.delete_bw.confirm")));
                    }
                }).bounds(0, 0, 22, 20)
                  .tooltip(Tooltip.create(Component.translatable(isPendingBw ? "sypass.gui.button.delete_bw.confirm" : "sypass.gui.button.delete_bw.tooltip")))
                  .build();

                if (isDeleting || isSuccess) {
                    this.deleteBwBtn.active = false;
                }
                children.add(deleteBwBtn);
            }

            // 6. Delete local entry
            boolean isPendingDel = key.equals(screen.getPendingDeleteKey());
            Button deleteBtn = Button.builder(Component.literal(isPendingDel ? "§4✔?" : "§c✖"), btn -> {
                if (key.equals(screen.getPendingDeleteKey())) {
                    screen.setPendingDeleteKey(null);
                    screen.setPendingBwDeleteKey(null);
                    PasswordManager.removePassword(serverIp, username);
                    if (data.isSynced() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
                        BitwardenManager.deleteSingleItemAsync(serverIp, username, data.remoteId());
                    }
                    String dispIp = SYPassConfig.isStreamerModeEnabled() ? ServerAddressUtil.mask(serverIp) : serverIp;
                    screen.setStatusMessage(Component.translatable("sypass.gui.status.deleted", username, dispIp).getString());
                    screen.refreshPasswordList();
                } else {
                    screen.setPendingDeleteKey(key);
                    screen.setPendingBwDeleteKey(null);
                    btn.setMessage(Component.literal("§4✔?"));
                    btn.setTooltip(Tooltip.create(Component.translatable("sypass.gui.button.delete.confirm")));
                }
            }).bounds(0, 0, 20, 20)
              .tooltip(Tooltip.create(Component.translatable(isPendingDel ? "sypass.gui.button.delete.confirm" : "sypass.gui.button.delete.tooltip")))
              .build();
            children.add(deleteBtn);
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            String currentUser = (mc.getUser() != null) ? mc.getUser().getName() : null;
            boolean isActiveAccount = currentUser != null && currentUser.equalsIgnoreCase(username);

            int cardY = top + 2;
            int cardH = 42;

            guiGraphics.fill(left, cardY, left + width, cardY + cardH, isActiveAccount ? 0xC0182818 : 0xC01C1C1C);
            guiGraphics.renderOutline(left, cardY, width, cardH, isActiveAccount ? 0x8855FF55 : 0xFF353535);

            boolean isStreamer = SYPassConfig.isStreamerModeEnabled();
            ResourceLocation icon = isStreamer ? ServerIconManager.UNKNOWN_SERVER : ServerIconManager.getServerIcon(serverIp);
            guiGraphics.blit(icon, left + 6, cardY + 9, 24, 24, 0.0F, 0.0F, 64, 64, 64, 64);

            Font font = mc.font;
            int textX = left + 36;

            String displayIp = isStreamer ? ServerAddressUtil.mask(serverIp) : serverIp;
            String cloudBadge = SYPassConfig.isBitwardenEnabled() ? (data.isSynced() ? "§a☁ " : "§7☁ ") : "";
            String serverTitle = cloudBadge + (data.isFavorite() ? "§e★ " : "") + "§6§l" + displayIp;
            guiGraphics.drawString(font, Component.literal(serverTitle), textX, cardY + 5, 0xFFFFFF, true);

            boolean isRevealed = !isStreamer && screen.isPasswordRevealed(key);
            String userTitle = isActiveAccount ? "§a§l" + username : "§e" + username;
            String passTitle;
            if (isRevealed) {
                AccountData realAcc = PasswordManager.getPassword(serverIp, username);
                passTitle = (realAcc != null) ? " §b" + realAcc.getPasswordAsString() : " §b";
            } else {
                passTitle = " §7••••••••";
            }
            guiGraphics.drawString(font, Component.literal(userTitle + passTitle), textX, cardY + 16, 0xFFFFFF, true);
            guiGraphics.drawString(font, Component.literal("§8" + data.command()), textX, cardY + 27, 0x888888, true);

            if (deleteBwBtn != null) {
                if (key.equals(screen.getActiveBwDeletingKey())) {
                    deleteBwBtn.active = false;
                    int dotIdx = (int) ((System.currentTimeMillis() / 350L) % 3);
                    deleteBwBtn.setMessage(Component.literal(LOADING_DOTS[dotIdx]));
                } else if (key.equals(screen.getActiveBwSuccessKey())) {
                    deleteBwBtn.active = false;
                    if (System.currentTimeMillis() < screen.getBwSuccessUntilMs()) {
                        deleteBwBtn.setMessage(Component.literal("§a✔"));
                    } else {
                        screen.clearActiveBwSuccess();
                        // Defer list mutation out of render() to prevent ConcurrentModificationException
                        mc.execute(screen::refreshPasswordList);
                    }
                }
            }

            int btnY = cardY + 11;
            int totalBtnWidth = children.size() * 22;
            int btnX = left + width - totalBtnWidth - 4;

            for (AbstractWidget widget : children) {
                widget.setX(btnX);
                widget.setY(btnY);
                widget.render(guiGraphics, mouseX, mouseY, partialTick);
                btnX += widget.getWidth() + 2;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.children;
        }
    }
}
