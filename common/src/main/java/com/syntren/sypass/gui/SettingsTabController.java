package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Manages all Settings sub-menus: Main, Chat Protection, Backups, Bitwarden, Master Password, and Login Patterns.
 */
final class SettingsTabController {

    enum Stage {
        MAIN,
        CHAT_PROTECTION,
        BACKUP,
        BITWARDEN,
        MASTER_PASSWORD,
        LOGIN_PATTERNS
    }

    private static final int PATTERNS_PER_PAGE = 3;

    private final SYPassScreen screen;
    private Stage stage = Stage.MAIN;
    private boolean exportAsCsv = false;
    private int patternPage = 0;

    private EditBox backupPassBox;
    private EditBox mpNewPassBox;
    private EditBox mpConfirmPassBox;
    private EditBox regTemplateBox;
    private EditBox newPatternBox;

    SettingsTabController(SYPassScreen screen) {
        this.screen = screen;
    }

    void resetToMain() {
        this.stage = Stage.MAIN;
        this.patternPage = 0;
    }

    void init(int screenWidth, int screenHeight, int contentWidth, Font font) {
        int cardWidth = Math.min(420, contentWidth);
        int gap = 8;
        int colWidth = (cardWidth - gap) / 2;
        int cardX = (screenWidth - cardWidth) / 2;
        int startY = Math.max(38, (screenHeight - 188) / 2);

        switch (stage) {
            case MAIN -> initMain(cardX, startY, colWidth, gap);
            case CHAT_PROTECTION -> initChatProtection(cardX, startY, cardWidth);
            case BACKUP -> initBackup(cardX, startY, cardWidth, font);
            case BITWARDEN -> initBitwarden(cardX, startY, cardWidth, font);
            case MASTER_PASSWORD -> initMasterPassword(cardX, startY, cardWidth, font);
            case LOGIN_PATTERNS -> initLoginPatterns(cardX, startY, cardWidth, font);
        }

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> screen.onClose())
                .bounds((screenWidth - 140) / 2, screenHeight - 26, 140, 20).build());
    }

    private void initMain(int cardX, int startY, int colWidth, int gap) {
        int y = startY;
        int rightX = cardX + colWidth + gap;

        // Row 1: Auto-login & Toasts
        addToggle(cardX, y, colWidth, "sypass.gui.settings.autologin", "sypass.gui.settings.autologin.tooltip",
                SYPassConfig::isAutoLoginEnabled, SYPassConfig::setAutoLoginEnabled);
        addToggle(rightX, y, colWidth, "sypass.gui.settings.toasts", "sypass.gui.settings.toasts.tooltip",
                SYPassConfig::isToastsEnabled, SYPassConfig::setToastsEnabled);

        y += 24;
        // Row 2: Smart Auto-login & Smart Auto-register
        addToggle(cardX, y, colWidth, "sypass.gui.settings.smart_login", "sypass.gui.settings.smart_login.tooltip",
                SYPassConfig::isSmartAutoLoginEnabled, SYPassConfig::setSmartAutoLoginEnabled);
        addToggle(rightX, y, colWidth, "sypass.gui.settings.smart_register", "sypass.gui.settings.smart_register.tooltip",
                SYPassConfig::isSmartAutoRegisterEnabled, SYPassConfig::setSmartAutoRegisterEnabled);

        y += 24;
        // Row 3: Overwrite Protection & Chat Protection submenu
        addToggle(cardX, y, colWidth, "sypass.gui.settings.prevent_overwrite", "sypass.gui.settings.prevent_overwrite.tooltip",
                SYPassConfig::isPreventRegisterOverwriteEnabled, SYPassConfig::setPreventRegisterOverwriteEnabled);
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.chat_protect.menu_btn", GuiHelpers.onOffGrayBadge(SYPassConfig.isChatLeakProtectionEnabled())),
                b -> goTo(Stage.CHAT_PROTECTION)
        ).bounds(rightX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.chat_protect.tooltip"))).build());

        y += 26;
        // Row 4: Delay & Password Length steppers
        int dBtnW = (colWidth - 6) / 4;
        addStepperRow(cardX, y + 12, dBtnW,
                new String[]{"-10t", "-5t", "+5t", "+10t"},
                new int[]{-10, -5, 5, 10},
                delta -> SYPassConfig.setAutoLoginDelayTicks(SYPassConfig.getAutoLoginDelayTicks() + delta));
        addStepperRow(rightX, y + 12, dBtnW,
                new String[]{"-4", "-1", "+1", "+4"},
                new int[]{-4, -1, 1, 4},
                delta -> SYPassConfig.setDefaultPasswordLength(SYPassConfig.getDefaultPasswordLength() + delta));

        y += 34;
        // Row 5: Backups & Bitwarden submenus
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.backup.menu_btn"),
                b -> goTo(Stage.BACKUP)
        ).bounds(cardX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.backup.menu_btn.tooltip"))).build());

        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.bw.menu_btn", GuiHelpers.onOffGrayBadge(SYPassConfig.isBitwardenEnabled())),
                b -> goTo(Stage.BITWARDEN)
        ).bounds(rightX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.bw.menu_btn.tooltip"))).build());

        y += 24;
        // Row 6: Master Password & Login Patterns submenus
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.master_pass.menu_btn", GuiHelpers.onOffGrayBadge(SYPassConfig.isMasterPasswordEnabled())),
                b -> goTo(Stage.MASTER_PASSWORD)
        ).bounds(cardX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.master_pass.tooltip"))).build());

        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.patterns.menu_btn"),
                b -> goTo(Stage.LOGIN_PATTERNS)
        ).bounds(rightX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.patterns.tooltip"))).build());

        y += 24;
        // Row 7: Streamer Mode & Open Config Folder
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.streamer_mode", GuiHelpers.onOffGrayBadge(SYPassConfig.isStreamerModeEnabled())),
                b -> {
                    SYPassConfig.setStreamerModeEnabled(!SYPassConfig.isStreamerModeEnabled());
                    screen.rebuildView();
                }
        ).bounds(cardX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.streamer_mode.tooltip"))).build());

        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.bw.button.open_folder"),
                b -> GuiHelpers.openConfigFolder()
        ).bounds(rightX, y, colWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.open_folder.tooltip"))).build());
    }

    private void initChatProtection(int cardX, int y, int cardWidth) {
        boolean chatProtect = SYPassConfig.isChatLeakProtectionEnabled();
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.chat_protect", GuiHelpers.onOffBadge(chatProtect)),
                b -> {
                    SYPassConfig.setChatLeakProtectionEnabled(!SYPassConfig.isChatLeakProtectionEnabled());
                    screen.rebuildView();
                }
        ).bounds(cardX, y + 25, cardWidth, 20).build());

        SYPassConfig.ChatProtectionScope scope = SYPassConfig.getChatProtectionScope();
        Button scopeBtn = Button.builder(
                Component.translatable("sypass.gui.settings.chat_scope.mode", Component.translatable(scope.getTranslationKey())),
                b -> {
                    if (!chatProtect) return;
                    SYPassConfig.ChatProtectionScope newScope = (scope == SYPassConfig.ChatProtectionScope.CURRENT_SERVER)
                            ? SYPassConfig.ChatProtectionScope.ALL_SERVERS
                            : SYPassConfig.ChatProtectionScope.CURRENT_SERVER;
                    SYPassConfig.setChatProtectionScope(newScope);
                    screen.rebuildView();
                }
        ).bounds(cardX, y + 65, cardWidth, 20).build();
        scopeBtn.active = chatProtect;
        screen.registerWidget(scopeBtn);

        addBackButton(cardX, y + 120, cardWidth);
    }

    private void initBackup(int cardX, int y, int cardWidth, Font font) {
        this.backupPassBox = new EditBox(font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.settings.backup.pass_placeholder"));
        this.backupPassBox.setMaxLength(128);
        this.backupPassBox.setHint(Component.translatable("sypass.gui.settings.backup.pass_placeholder"));
        GuiHelpers.applyPasswordMask(this.backupPassBox, false);
        if (!this.exportAsCsv) {
            screen.registerWidget(this.backupPassBox);
        }

        Component formatLabel = exportAsCsv
                ? Component.translatable("sypass.gui.settings.backup.csv_checkbox")
                : Component.translatable("sypass.gui.settings.backup.format_json");

        screen.registerWidget(Button.builder(formatLabel, b -> {
            this.exportAsCsv = !this.exportAsCsv;
            screen.rebuildView();
        }).bounds(cardX, y + 50, cardWidth, 20).build());

        int btnW = (cardWidth - 8) / 3;
        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.export"), b -> {
            if (this.exportAsCsv) {
                String file = PasswordManager.exportBitwardenCsv();
                if (file != null) {
                    screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.exported_csv", file).getString());
                }
            } else {
                String pass = (this.backupPassBox != null) ? this.backupPassBox.getValue().trim() : "";
                String file = PasswordManager.exportBackup(pass);
                if (file != null) {
                    screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.exported", file).getString());
                }
            }
        }).bounds(cardX, y + 75, btnW, 20).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.import"), b -> {
            int imported = this.exportAsCsv
                    ? PasswordManager.importLatestCsvBackup()
                    : PasswordManager.importLatestBackup(this.backupPassBox != null ? this.backupPassBox.getValue().trim() : null);
            if (imported >= 0) {
                screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.imported", imported).getString());
            } else {
                screen.setStatusMessage("§c" + Component.translatable("sypass.gui.settings.backup.import_failed").getString());
            }
        }).bounds(cardX + btnW + 4, y + 75, btnW, 20).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.open_folder"), b ->
                GuiHelpers.openBackupsFolder()
        ).bounds(cardX + (btnW + 4) * 2, y + 75, btnW, 20).build());

        addBackButton(cardX, y + 105, cardWidth);
    }

    private void initBitwarden(int cardX, int y, int cardWidth, Font font) {
        boolean bwEn = SYPassConfig.isBitwardenEnabled();
        screen.registerWidget(Button.builder(
                Component.translatable("sypass.gui.settings.enable_bw", GuiHelpers.onOffBadge(bwEn)),
                b -> {
                    SYPassConfig.setBitwardenEnabled(!SYPassConfig.isBitwardenEnabled());
                    screen.rebuildView();
                }
        ).bounds(cardX, y + 25, cardWidth, 20).build());

        Button autoSyncBtn = Button.builder(
                Component.translatable("sypass.gui.settings.autosync", GuiHelpers.onOffBadge(SYPassConfig.isAutoSyncEnabled())),
                b -> {
                    if (!bwEn) return;
                    boolean newVal = !SYPassConfig.isAutoSyncEnabled();
                    SYPassConfig.setAutoSyncEnabled(newVal);
                    b.setMessage(Component.translatable("sypass.gui.settings.autosync", GuiHelpers.onOffBadge(newVal)));
                }
        ).bounds(cardX, y + 50, cardWidth, 20).build();
        autoSyncBtn.active = bwEn;
        screen.registerWidget(autoSyncBtn);

        EditBox serverUrlBox = new EditBox(font, cardX, y + 85, cardWidth - 28, 20, Component.translatable("sypass.gui.settings.server_url"));
        serverUrlBox.setMaxLength(256);
        serverUrlBox.setValue(SYPassConfig.getCustomServerUrl());
        serverUrlBox.setHint(Component.translatable("sypass.gui.settings.server_url.placeholder"));
        serverUrlBox.setEditable(bwEn);
        serverUrlBox.setTextColor(bwEn ? 0xFFFFFF : 0x777777);
        screen.registerWidget(serverUrlBox);

        Button saveUrlBtn = Button.builder(Component.literal("✔"), b -> {
            if (!bwEn) return;
            String url = serverUrlBox.getValue().trim();
            SYPassConfig.setCustomServerUrl(url);
            BitwardenManager.configureServer(url);
            screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.server_url.saved").getString());
        }).bounds(cardX + cardWidth - 24, y + 85, 24, 20).build();
        saveUrlBtn.active = bwEn;
        screen.registerWidget(saveUrlBtn);

        Button openFolderBwBtn = Button.builder(Component.translatable("sypass.gui.bw.button.open_folder"), b ->
                GuiHelpers.openConfigFolder()
        ).bounds(cardX, y + 110, cardWidth, 20).build();
        openFolderBwBtn.active = bwEn;
        screen.registerWidget(openFolderBwBtn);

        addBackButton(cardX, y + 135, cardWidth);
    }

    private void initMasterPassword(int cardX, int y, int cardWidth, Font font) {
        if (!SYPassConfig.isMasterPasswordEnabled()) {
            this.mpNewPassBox = new EditBox(font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
            this.mpNewPassBox.setMaxLength(128);
            this.mpNewPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
            GuiHelpers.applyPasswordMask(this.mpNewPassBox, false);
            screen.registerWidget(this.mpNewPassBox);

            this.mpConfirmPassBox = new EditBox(font, cardX, y + 50, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.confirm_pass"));
            this.mpConfirmPassBox.setMaxLength(128);
            this.mpConfirmPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.confirm_pass"));
            GuiHelpers.applyPasswordMask(this.mpConfirmPassBox, false);
            screen.registerWidget(this.mpConfirmPassBox);

            screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.master_pass.setup"), b -> {
                String p1 = (this.mpNewPassBox != null) ? this.mpNewPassBox.getValue() : "";
                String p2 = (this.mpConfirmPassBox != null) ? this.mpConfirmPassBox.getValue() : "";
                if (p1.length() < 4) {
                    screen.setStatusMessage("§cPassword too short (min 4 chars)!");
                    return;
                }
                if (!p1.equals(p2)) {
                    screen.setStatusMessage("§c" + Component.translatable("sypass.gui.settings.master_pass.mismatch").getString());
                    return;
                }
                if (PasswordManager.enableMasterPassword(p1.toCharArray())) {
                    screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.master_pass.success_on").getString());
                    goTo(Stage.MAIN);
                } else {
                    screen.setStatusMessage("§cFailed to enable Master Password!");
                }
            }).bounds(cardX, y + 75, cardWidth, 20).build());

            addBackButton(cardX, y + 100, cardWidth);
        } else {
            this.mpNewPassBox = new EditBox(font, cardX, y + 20, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
            this.mpNewPassBox.setMaxLength(128);
            this.mpNewPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
            GuiHelpers.applyPasswordMask(this.mpNewPassBox, false);
            screen.registerWidget(this.mpNewPassBox);

            screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.master_pass.remove"), b -> {
                String p1 = (this.mpNewPassBox != null) ? this.mpNewPassBox.getValue() : "";
                if (PasswordManager.disableMasterPassword(p1.toCharArray())) {
                    screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.master_pass.success_off").getString());
                    goTo(Stage.MAIN);
                } else {
                    screen.setStatusMessage("§c" + Component.translatable("sypass.gui.lock.wrong_pass").getString());
                }
            }).bounds(cardX, y + 44, cardWidth, 20).build());

            int currentTimeout = SYPassConfig.getAutoLockTimeoutMinutes();
            Button autoLockBtn = Button.builder(formatAutoLockText(currentTimeout), b -> {
                int next = switch (SYPassConfig.getAutoLockTimeoutMinutes()) {
                    case 0 -> 5;
                    case 5 -> 10;
                    case 10 -> 15;
                    case 15 -> 30;
                    case 30 -> 60;
                    default -> 0;
                };
                SYPassConfig.setAutoLockTimeoutMinutes(next);
                b.setMessage(formatAutoLockText(next));
            }).bounds(cardX, y + 68, cardWidth, 20)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.autolock.tooltip"))).build();
            screen.registerWidget(autoLockBtn);

            screen.registerWidget(Button.builder(Component.translatable("sypass.gui.settings.master_pass.lock_now"), b -> {
                PasswordManager.lockVault();
                screen.rebuildView();
            }).bounds(cardX, y + 92, cardWidth, 20)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.master_pass.lock_now.tooltip"))).build());

            addBackButton(cardX, y + 118, cardWidth);
        }
    }

    private void initLoginPatterns(int cardX, int y, int cardWidth, Font font) {
        this.regTemplateBox = new EditBox(font, cardX, y + 16, cardWidth - 65, 20, Component.translatable("sypass.gui.settings.reg_template.title"));
        this.regTemplateBox.setMaxLength(128);
        this.regTemplateBox.setValue(SYPassConfig.getRegisterCommandTemplate());
        screen.registerWidget(this.regTemplateBox);

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.button.save"), b -> {
            if (this.regTemplateBox != null) {
                SYPassConfig.setRegisterCommandTemplate(this.regTemplateBox.getValue().trim());
                screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.reg_template.saved").getString());
            }
        }).bounds(cardX + cardWidth - 60, y + 16, 60, 20).build());

        int pw = (cardWidth - 6) / 4;
        String[] presets = new String[]{"/register %p% %p%", "/reg %p% %p%", "/register %p%", "/reg %p%"};
        for (int i = 0; i < presets.length; i++) {
            String preset = presets[i];
            screen.registerWidget(Button.builder(Component.literal(preset), b -> {
                if (this.regTemplateBox != null) {
                    this.regTemplateBox.setValue(preset);
                    SYPassConfig.setRegisterCommandTemplate(preset);
                    screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.reg_template.saved").getString());
                }
            }).bounds(cardX + i * (pw + 2), y + 39, pw, 18).build());
        }

        int pY = y + 74;
        this.newPatternBox = new EditBox(font, cardX, pY, cardWidth - 45, 20, Component.translatable("sypass.gui.settings.custom_patterns.title"));
        this.newPatternBox.setMaxLength(128);
        this.newPatternBox.setHint(Component.translatable("sypass.gui.settings.custom_patterns.placeholder"));
        screen.registerWidget(this.newPatternBox);

        screen.registerWidget(Button.builder(Component.literal("+"), b -> {
            if (this.newPatternBox != null && !this.newPatternBox.getValue().isBlank()) {
                String raw = this.newPatternBox.getValue().trim();
                if (!AutoLoginHandler.isValidRegex(raw)) {
                    screen.setStatusMessage("§cInvalid regex pattern!");
                    return;
                }
                SYPassConfig.addCustomLoginPattern(raw);
                this.newPatternBox.setValue("");
                screen.setStatusMessage("§a" + Component.translatable("sypass.gui.settings.custom_patterns.added").getString());
                screen.rebuildView();
            }
        }).bounds(cardX + cardWidth - 40, pY, 40, 20).build());

        List<String> patterns = SYPassConfig.getCustomLoginPatterns();
        int totalPages = Math.max(1, (patterns.size() + PATTERNS_PER_PAGE - 1) / PATTERNS_PER_PAGE);
        patternPage = Math.max(0, Math.min(patternPage, totalPages - 1));

        int startIdx = patternPage * PATTERNS_PER_PAGE;
        int endIdx = Math.min(startIdx + PATTERNS_PER_PAGE, patterns.size());
        int listY = pY + 24;
        for (int i = startIdx; i < endIdx; i++) {
            String pat = patterns.get(i);
            int row = i - startIdx;
            screen.registerWidget(Button.builder(Component.literal("✕"), b -> {
                SYPassConfig.removeCustomLoginPattern(pat);
                screen.rebuildView();
            }).bounds(cardX + cardWidth - 22, listY + row * 18, 22, 16)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.delete.tooltip"))).build());
        }

        if (totalPages > 1) {
            Button prevBtn = Button.builder(Component.literal("<"), b -> {
                patternPage = Math.max(0, patternPage - 1);
                screen.rebuildView();
            }).bounds(cardX, y + 160, 24, 20).build();
            prevBtn.active = patternPage > 0;
            screen.registerWidget(prevBtn);

            Button nextBtn = Button.builder(Component.literal(">"), b -> {
                patternPage = Math.min(totalPages - 1, patternPage + 1);
                screen.rebuildView();
            }).bounds(cardX + cardWidth - 24, y + 160, 24, 20).build();
            nextBtn.active = patternPage < totalPages - 1;
            screen.registerWidget(nextBtn);

            addBackButton(cardX + 28, y + 160, cardWidth - 56);
        } else {
            addBackButton(cardX, y + 160, cardWidth);
        }
    }

    void render(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, int contentWidth) {
        int cardWidth = Math.min(420, contentWidth);
        int gap = 8;
        int colWidth = (cardWidth - gap) / 2;
        int cardX = (screenWidth - cardWidth) / 2;
        int startY = Math.max(38, (screenHeight - 188) / 2);

        switch (stage) {
            case MAIN -> {
                int delayTicks = SYPassConfig.getAutoLoginDelayTicks();
                float delaySec = delayTicks / 20.0f;
                guiGraphics.drawString(font, Component.translatable("sypass.gui.settings.delay", delayTicks, String.format(Locale.ROOT, "%.1f", delaySec)), cardX, startY + 76, 0xCCCCCC, true);
                guiGraphics.drawString(font, Component.translatable("sypass.gui.settings.pass_len", SYPassConfig.getDefaultPasswordLength()), cardX + colWidth + gap, startY + 76, 0xCCCCCC, true);
            }
            case CHAT_PROTECTION -> {
                guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.settings.chat_protect.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, startY + 4, 0xFFFFFF);
                SYPassConfig.ChatProtectionScope scope = SYPassConfig.getChatProtectionScope();
                String hintKey = (scope == SYPassConfig.ChatProtectionScope.CURRENT_SERVER)
                        ? "sypass.gui.settings.chat_scope.current_hint"
                        : "sypass.gui.settings.chat_scope.all_hint";
                guiGraphics.drawWordWrap(font, Component.translatable(hintKey).withStyle(ChatFormatting.GRAY), cardX, startY + 94, cardWidth, 0x888888);
            }
            case BITWARDEN -> {
                boolean bwEn = SYPassConfig.isBitwardenEnabled();
                guiGraphics.drawString(font, Component.translatable("sypass.gui.settings.server_url"), cardX, startY + 74, bwEn ? 0xCCCCCC : 0x777777, true);
            }
            case MASTER_PASSWORD -> {
                String titleKey = SYPassConfig.isMasterPasswordEnabled()
                        ? "sypass.gui.settings.master_pass.remove"
                        : "sypass.gui.settings.master_pass.setup";
                guiGraphics.drawCenteredString(font, Component.translatable(titleKey).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, startY + 4, 0xFFFFFF);
            }
            case LOGIN_PATTERNS -> {
                guiGraphics.drawString(font, Component.translatable("sypass.gui.settings.reg_template.title"), cardX, startY + 4, 0xCCCCCC, true);
                int pY = startY + 62;
                guiGraphics.drawString(font, Component.translatable("sypass.gui.settings.custom_patterns.title"), cardX, pY, 0xCCCCCC, true);

                List<String> patterns = SYPassConfig.getCustomLoginPatterns();
                int listY = pY + 39;
                if (patterns.isEmpty()) {
                    guiGraphics.drawString(font, Component.literal("§7(Default: /login, /l, enter, password, увійдіть, войдите...)"), cardX, listY + 3, 0x888888, true);
                } else {
                    int startIdx = patternPage * PATTERNS_PER_PAGE;
                    int endIdx = Math.min(startIdx + PATTERNS_PER_PAGE, patterns.size());
                    for (int i = startIdx; i < endIdx; i++) {
                        int row = i - startIdx;
                        guiGraphics.drawString(font, Component.literal("§b• " + patterns.get(i)), cardX, listY + row * 18 + 4, 0x55FFFF, true);
                    }
                }
            }
            case BACKUP -> {
                // Handled by widgets
            }
        }
    }

    private void addToggle(int x, int y, int width, String labelKey, String tooltipKey, BooleanSupplier getter, Consumer<Boolean> setter) {
        screen.registerWidget(Button.builder(
                Component.translatable(labelKey, GuiHelpers.onOffBadge(getter.getAsBoolean())),
                b -> {
                    boolean newVal = !getter.getAsBoolean();
                    setter.accept(newVal);
                    b.setMessage(Component.translatable(labelKey, GuiHelpers.onOffBadge(newVal)));
                }
        ).bounds(x, y, width, 20)
         .tooltip(Tooltip.create(Component.translatable(tooltipKey))).build());
    }

    private void addStepperRow(int startX, int y, int btnW, String[] labels, int[] deltas, Consumer<Integer> onDelta) {
        for (int i = 0; i < labels.length; i++) {
            int delta = deltas[i];
            screen.registerWidget(Button.builder(Component.literal(labels[i]), b -> onDelta.accept(delta))
                    .bounds(startX + (btnW + 2) * i, y, btnW, 18).build());
        }
    }

    private void addBackButton(int x, int y, int width) {
        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> goTo(Stage.MAIN))
                .bounds(x, y, width, 20).build());
    }

    private void goTo(Stage nextStage) {
        this.stage = nextStage;
        screen.rebuildView();
    }

    private static Component formatAutoLockText(int minutes) {
        return (minutes == 0)
                ? Component.translatable("sypass.gui.settings.autolock", Component.translatable("sypass.gui.settings.autolock.disabled").getString())
                : Component.translatable("sypass.gui.settings.autolock", Component.translatable("sypass.gui.settings.autolock.minutes", minutes).getString());
    }
}
