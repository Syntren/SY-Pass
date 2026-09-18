package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.io.File;
import java.util.*;

public class SYPassScreen extends Screen {

    private final Screen parent;

    public enum Tab {
        LOCAL_PASSWORDS,
        BITWARDEN,
        SETTINGS
    }

    public enum BwStage {
        CHECKING_STATUS,
        CLI_NOT_FOUND,
        LOGIN,
        OTP,
        API_KEY,
        SESSION_KEY,
        LOGGED_IN
    }

    public enum SettingsStage {
        MAIN,
        CHAT_PROTECTION,
        BACKUP,
        BITWARDEN,
        MASTER_PASSWORD,
        LOGIN_PATTERNS
    }

    public enum SortMode {
        FAVORITES_FIRST("sypass.gui.sort.favorites", "§e★"),
        ALPHABETICAL("sypass.gui.sort.alphabetical", "§fA-Z"),
        RECENT("sypass.gui.sort.recent", "§b🕒");

        private final String translationKey;
        private final String label;

        SortMode(String translationKey, String label) {
            this.translationKey = translationKey;
            this.label = label;
        }

        public String getTranslationKey() { return translationKey; }
        public String getLabel() { return label; }

        public SortMode next() {
            SortMode[] vals = values();
            return vals[(this.ordinal() + 1) % vals.length];
        }
    }

    private Tab activeTab = Tab.LOCAL_PASSWORDS;
    private BwStage bwStage = BitwardenManager.hasActiveSession() ? BwStage.LOGGED_IN : BwStage.CHECKING_STATUS;
    private SettingsStage settingsStage = SettingsStage.MAIN;
    private SortMode sortMode = SortMode.FAVORITES_FIRST;
    private boolean onlyFavorites = false;
    private boolean exportAsCsv = false;
    private String searchQuery = "";
    private String statusMessage = "";

    // Cached Bitwarden status to eliminate main thread hangs
    private BitwardenManager.BwStatusInfo cachedStatusInfo = null;
    private String savedEmail = "";
    private String savedPassword = "";
    private String selected2faMethod = "0"; // "0" = Authenticator, "1" = Email
    private boolean showMasterPassword = false;

    // Async Bitwarden deletion animation state
    private String activeBwDeletingKey = null;
    private String activeBwSuccessKey = null;
    private long bwSuccessUntilMs = 0L;
    private static final String[] LOADING_DOTS = new String[]{"§e.", "§e..", "§e..."};

    // Widgets
    private EditBox searchBox;
    private PasswordListWidget passwordList;
    private final Set<String> revealedPasswords = new HashSet<>();
    private String pendingDeleteKey = null;
    private String pendingBwDeleteKey = null;

    // Bitwarden inputs
    private EditBox bwEmailBox;
    private EditBox bwPasswordBox;
    private EditBox bwOtpBox;
    private EditBox bwClientIdBox;
    private EditBox bwClientSecretBox;
    private EditBox bwMasterPasswordBox;
    private EditBox bwSessionKeyBox;

    // Backup input
    private EditBox backupPassBox;

    // Master password & pattern inputs
    private EditBox mpNewPassBox;
    private EditBox mpConfirmPassBox;
    private EditBox regTemplateBox;
    private EditBox newPatternBox;

    public SYPassScreen() {
        this(null);
    }

    public SYPassScreen(Screen parent) {
        super(Component.translatable("sypass.gui.title"));
        this.parent = parent;
    }

    public void setStatusMessage(String message) {
        this.statusMessage = (message != null) ? message : "";
    }

    public void refreshPasswordList() {
        if (this.passwordList != null) {
            this.passwordList.refresh(this.searchQuery, this.sortMode, this.onlyFavorites);
        }
    }

    @Override
    protected void init() {
        super.init();

        int contentWidth = Math.min(440, this.width - 32);
        int contentX = (this.width - contentWidth) / 2;

        if (PasswordManager.isVaultLocked()) {
            initVaultLockScreen(contentX, contentWidth);
            return;
        }

        if (!SYPassConfig.isBitwardenEnabled() && activeTab == Tab.BITWARDEN) {
            activeTab = Tab.LOCAL_PASSWORDS;
        }

        // 1. Верхні вкладки
        int headerY = 8;
        int localWidth = 150;
        int bwWidth = SYPassConfig.isBitwardenEnabled() ? 120 : 0;
        int settingsWidth = 110;
        int totalTabsWidth = localWidth + (bwWidth > 0 ? bwWidth + 6 : 0) + settingsWidth + 6;
        int tabStartX = (this.width - totalTabsWidth) / 2;

        int currentCount = PasswordManager.getTotalCount();
        String localTabTitle = (activeTab == Tab.LOCAL_PASSWORDS ? "§e§l" : "§7") + Component.translatable("sypass.gui.tab.local", currentCount).getString();
        addRenderableWidget(Button.builder(Component.literal(localTabTitle), btn -> switchTab(Tab.LOCAL_PASSWORDS))
                .bounds(tabStartX, headerY, localWidth, 20).build());

        int nextX = tabStartX + localWidth + 6;
        if (SYPassConfig.isBitwardenEnabled()) {
            String bwTabTitle = (activeTab == Tab.BITWARDEN ? "§e§l" : "§7") + Component.translatable("sypass.gui.tab.bitwarden").getString();
            addRenderableWidget(Button.builder(Component.literal(bwTabTitle), btn -> {
                if (BitwardenManager.hasActiveSession()) {
                    this.bwStage = BwStage.LOGGED_IN;
                } else if (this.cachedStatusInfo != null) {
                    this.bwStage = this.cachedStatusInfo.isInstalled() ? BwStage.LOGIN : BwStage.CLI_NOT_FOUND;
                } else {
                    this.bwStage = BwStage.CHECKING_STATUS;
                    checkBwStatusAsync();
                }
                switchTab(Tab.BITWARDEN);
            }).bounds(nextX, headerY, bwWidth, 20).build());
            nextX += bwWidth + 6;
        }

        String settingsTabTitle = (activeTab == Tab.SETTINGS ? "§e§l" : "§7") + Component.translatable("sypass.gui.tab.settings").getString();
        addRenderableWidget(Button.builder(Component.literal(settingsTabTitle), btn -> {
            this.settingsStage = SettingsStage.MAIN;
            switchTab(Tab.SETTINGS);
        }).bounds(nextX, headerY, settingsWidth, 20).build());

        // 2. Вміст активної вкладки
        switch (activeTab) {
            case LOCAL_PASSWORDS -> initLocalPasswordsTab(contentX, contentWidth);
            case BITWARDEN -> initBitwardenTab(contentX, contentWidth);
            case SETTINGS -> initSettingsTab(contentX, contentWidth);
        }
    }

    private void initVaultLockScreen(int contentX, int contentWidth) {
        int cardWidth = Math.min(280, contentWidth);
        int cardX = (this.width - cardWidth) / 2;
        int y = (this.height - 120) / 2;

        EditBox unlockPassBox = new EditBox(this.font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.lock.desc"));
        unlockPassBox.setMaxLength(128);
        unlockPassBox.setHint(Component.translatable("sypass.gui.lock.desc"));
        unlockPassBox.setFocused(true);
        addRenderableWidget(unlockPassBox);

        Button unlockBtn = Button.builder(Component.translatable("sypass.gui.lock.unlock_btn"), b -> {
            String val = unlockPassBox.getValue();
            if (val.isBlank()) return;
            char[] chars = val.toCharArray();
            if (PasswordManager.unlockVault(chars)) {
                setStatusMessage("");
                clearWidgets();
                init();
            } else {
                setStatusMessage("§c" + Component.translatable("sypass.gui.lock.wrong_pass").getString());
            }
        }).bounds(cardX, y + 52, cardWidth, 20).build();
        addRenderableWidget(unlockBtn);

        Button resetBtn = Button.builder(Component.translatable("sypass.gui.lock.reset_btn"), b -> {
            if (PasswordManager.resetVaultWithBitwarden()) {
                setStatusMessage("§aVault reset. Unlocked.");
                clearWidgets();
                init();
            }
        }).bounds(cardX, y + 78, cardWidth, 20)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.lock.reset_confirm")))
          .build();
        addRenderableWidget(resetBtn);
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
        this.clearWidgets();
        this.init();
    }

    private void checkBwStatusAsync() {
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwStatusInfo status = BitwardenManager.getStatusInfo();
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    this.cachedStatusInfo = status;
                    if (status.isUnlocked() || BitwardenManager.hasActiveSession()) {
                        this.bwStage = BwStage.LOGGED_IN;
                    } else if (!status.isInstalled()) {
                        this.bwStage = BwStage.CLI_NOT_FOUND;
                    } else {
                        this.bwStage = BwStage.LOGIN;
                    }
                    this.clearWidgets();
                    this.init();
                });
            }
        });
    }

    // ==========================================
    // Вкладка 1: Паролі (LOCAL_PASSWORDS)
    // ==========================================
    private void initLocalPasswordsTab(int contentX, int contentWidth) {
        int contentTop = 32;

        int sortBtnWidth = 34;
        int favFilterWidth = 22;
        int searchWidth = contentWidth - sortBtnWidth - favFilterWidth - 8;

        this.searchBox = new EditBox(this.font, contentX, contentTop, searchWidth, 20, Component.translatable("sypass.gui.search.placeholder"));
        this.searchBox.setHint(Component.translatable("sypass.gui.search.placeholder"));
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setResponder(query -> {
            this.searchQuery = query;
            refreshPasswordList();
        });
        addRenderableWidget(this.searchBox);

        Button sortBtn = Button.builder(Component.literal(this.sortMode.getLabel()), btn -> {
            this.sortMode = this.sortMode.next();
            btn.setMessage(Component.literal(this.sortMode.getLabel()));
            btn.setTooltip(Tooltip.create(Component.translatable(this.sortMode.getTranslationKey())));
            refreshPasswordList();
        }).bounds(contentX + searchWidth + 4, contentTop, sortBtnWidth, 20)
          .tooltip(Tooltip.create(Component.translatable(this.sortMode.getTranslationKey())))
          .build();
        addRenderableWidget(sortBtn);

        Button favFilterBtn = Button.builder(Component.literal(this.onlyFavorites ? "§e★" : "§7☆"), btn -> {
            this.onlyFavorites = !this.onlyFavorites;
            btn.setMessage(Component.literal(this.onlyFavorites ? "§e★" : "§7☆"));
            btn.setTooltip(Tooltip.create(Component.translatable(this.onlyFavorites ? "sypass.gui.filter.all.tooltip" : "sypass.gui.filter.favorites.tooltip")));
            refreshPasswordList();
        }).bounds(contentX + searchWidth + 4 + sortBtnWidth + 4, contentTop, favFilterWidth, 20)
          .tooltip(Tooltip.create(Component.translatable(this.onlyFavorites ? "sypass.gui.filter.all.tooltip" : "sypass.gui.filter.favorites.tooltip")))
          .build();
        addRenderableWidget(favFilterBtn);

        int listTop = contentTop + 24;
        int listHeight = this.height - listTop - 54;
        this.passwordList = new PasswordListWidget(this.minecraft, this.width, listHeight, listTop, 46, contentWidth, this);
        addRenderableWidget(this.passwordList);
        refreshPasswordList();

        int bottomRowY = this.height - 26;
        boolean bwEnabled = SYPassConfig.isBitwardenEnabled();

        if (bwEnabled) {
            int gap = 6;
            int addW = (contentWidth - gap * 2) * 38 / 100;
            int syncW = (contentWidth - gap * 2) * 36 / 100;
            int closeW = (contentWidth - gap * 2) - addW - syncW;

            Button addBtn = Button.builder(Component.translatable("sypass.gui.button.add"), btn -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new EditPasswordScreen(this));
                }
            }).bounds(contentX, bottomRowY, addW, 20).build();
            addRenderableWidget(addBtn);

            Button syncBtn = Button.builder(Component.translatable("sypass.gui.button.sync_cloud"), btn -> handleFullSync())
                    .bounds(contentX + addW + gap, bottomRowY, syncW, 20).build();
            addRenderableWidget(syncBtn);

            Button closeBtn = Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                    .bounds(contentX + addW + gap + syncW + gap, bottomRowY, closeW, 20).build();
            addRenderableWidget(closeBtn);
        } else {
            int gap = 6;
            int addW = (contentWidth - gap) / 2;
            int closeW = contentWidth - gap - addW;

            Button addBtn = Button.builder(Component.translatable("sypass.gui.button.add"), btn -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new EditPasswordScreen(this));
                }
            }).bounds(contentX, bottomRowY, addW, 20).build();
            addRenderableWidget(addBtn);

            Button closeBtn = Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                    .bounds(contentX + addW + gap, bottomRowY, closeW, 20).build();
            addRenderableWidget(closeBtn);
        }
    }

    private void handleFullSync() {
        if (!BitwardenManager.hasActiveSession()) {
            setStatusMessage("§c" + Component.translatable("sypass.gui.status.not_logged_in").getString());
            return;
        }

        setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwSyncResult pullRes = BitwardenManager.pullFromBitwarden();
            BitwardenManager.BwSyncResult pushRes = BitwardenManager.pushToBitwarden();
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    setStatusMessage("§a" + Component.translatable("sypass.gui.status.sync_success", pullRes.count(), pushRes.count()).getString());
                    refreshPasswordList();
                });
            }
        });
    }

    // ==========================================
    // Вкладка 2: Bitwarden (BITWARDEN)
    // ==========================================
    private void initBitwardenTab(int contentX, int contentWidth) {
        int formWidth = Math.min(320, contentWidth);
        int formX = (this.width - formWidth) / 2;
        int y = 42;

        switch (bwStage) {
            case CHECKING_STATUS -> {
                // Візуалізується в render
            }
            case CLI_NOT_FOUND -> {
                int btnW = formWidth;
                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.button.guide"), btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                            if (confirmed) {
                                Util.getPlatform().openUri("https://bitwarden.com/help/cli/");
                            }
                            this.minecraft.setScreen(this);
                        }, "https://bitwarden.com/help/cli/", true));
                    }
                }).bounds(formX, y + 60, btnW, 20).tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.guide.tooltip"))).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.button.open_folder"), btn -> {
                    File dir = PlatformHelper.get().getConfigDir().resolve("sypass").toFile();
                    Util.getPlatform().openFile(dir);
                }).bounds(formX, y + 84, btnW, 20).tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.open_folder.tooltip"))).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.button.check_again"), btn -> {
                    BitwardenManager.invalidateStatusCache();
                    this.bwStage = BwStage.CHECKING_STATUS;
                    checkBwStatusAsync();
                }).bounds(formX, y + 108, btnW, 20).build());
            }
            case LOGIN -> {
                // Тільки Email та Master Password, без поля 2FA!
                this.bwEmailBox = new EditBox(this.font, formX, y + 16, formWidth, 20, Component.translatable("sypass.gui.bw.login.email_placeholder"));
                this.bwEmailBox.setMaxLength(256);
                this.bwEmailBox.setValue(this.savedEmail);
                this.bwEmailBox.setHint(Component.translatable("sypass.gui.bw.login.email_placeholder"));
                addRenderableWidget(this.bwEmailBox);

                int passFieldW = formWidth - 26;
                this.bwPasswordBox = new EditBox(this.font, formX, y + 48, passFieldW, 20, Component.translatable("sypass.gui.bw.login.password_placeholder"));
                this.bwPasswordBox.setMaxLength(256);
                this.bwPasswordBox.setValue(this.savedPassword);
                this.bwPasswordBox.setHint(Component.translatable("sypass.gui.bw.login.password_placeholder"));
                updateBwPasswordMask();
                addRenderableWidget(this.bwPasswordBox);

                Button toggleEyeBtn = Button.builder(Component.literal(showMasterPassword ? "§a●" : "§7○"), btn -> {
                    showMasterPassword = !showMasterPassword;
                    btn.setMessage(Component.literal(showMasterPassword ? "§a●" : "§7○"));
                    btn.setTooltip(Tooltip.create(Component.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show")));
                    updateBwPasswordMask();
                }).bounds(formX + passFieldW + 2, y + 48, 24, 20)
                  .tooltip(Tooltip.create(Component.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show")))
                  .build();
                addRenderableWidget(toggleEyeBtn);

                final Button[] loginNavButtons = new Button[4]; // [0]=loginBtn, [1]=apiKeyBtn, [2]=sessionKeyBtn, [3]=registerBtn

                Button loginBtn = Button.builder(Component.translatable("sypass.gui.bw.login.button_login"), btn -> {
                    this.savedEmail = this.bwEmailBox.getValue().trim();
                    this.savedPassword = this.bwPasswordBox.getValue().trim();

                    if (this.savedEmail.isEmpty() || this.savedPassword.isEmpty()) {
                        setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_credentials").getString());
                        return;
                    }

                    List<Button> toDisable = new ArrayList<>();
                    for (Button b : loginNavButtons) {
                        if (b != null) toDisable.add(b);
                    }
                    handleLogin(null, null, toDisable);
                }).bounds(formX, y + 78, formWidth, 20).build();
                loginNavButtons[0] = loginBtn;
                addRenderableWidget(loginBtn);

                int halfW = (formWidth - 6) / 2;
                Button apiKeyBtn = Button.builder(Component.translatable("sypass.gui.bw.login.apikey_tab"), btn -> {
                    this.bwStage = BwStage.API_KEY;
                    clearWidgets();
                    init();
                }).bounds(formX, y + 104, halfW, 20).build();
                loginNavButtons[1] = apiKeyBtn;
                addRenderableWidget(apiKeyBtn);

                Button sessionKeyBtn = Button.builder(Component.translatable("sypass.gui.bw.login.session_tab"), btn -> {
                    this.bwStage = BwStage.SESSION_KEY;
                    clearWidgets();
                    init();
                }).bounds(formX + halfW + 6, y + 104, halfW, 20).build();
                loginNavButtons[2] = sessionKeyBtn;
                addRenderableWidget(sessionKeyBtn);

                Button registerBtn = Button.builder(Component.translatable("sypass.gui.bw.login.register"), btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                            if (confirmed) {
                                Util.getPlatform().openUri("https://vault.bitwarden.com/#/register");
                            }
                            this.minecraft.setScreen(this);
                        }, "https://vault.bitwarden.com/#/register", true));
                    }
                }).bounds(formX, y + 130, formWidth, 20)
                  .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.login.register.tooltip")))
                  .build();
                loginNavButtons[3] = registerBtn;
                addRenderableWidget(registerBtn);
            }
            case OTP -> {
                // Вікно двоетапної автентифікації 2FA (динамічно з'являється при запиті CLI)
                int methodBtnW = (formWidth - 6) / 2;
                boolean isAuthSelected = !"1".equals(selected2faMethod);

                final Button[] otpNavButtons = new Button[5]; // [0]=confirmOtpBtn, [1]=backBtn, [2]=authBtn, [3]=emailBtn, [4]=sendEmailBtn

                Button authBtn = Button.builder(
                        Component.translatable("sypass.gui.bw.otp.method_authenticator")
                                .withStyle(isAuthSelected ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                        btn -> {
                            this.selected2faMethod = "0";
                            clearWidgets();
                            init();
                        }
                ).bounds(formX, y + 16, methodBtnW, 20).build();
                otpNavButtons[2] = authBtn;
                addRenderableWidget(authBtn);

                Button emailBtn = Button.builder(
                        Component.translatable("sypass.gui.bw.otp.method_email")
                                .withStyle(!isAuthSelected ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                        btn -> {
                            this.selected2faMethod = "1";
                            clearWidgets();
                            init();
                        }
                ).bounds(formX + methodBtnW + 6, y + 16, methodBtnW, 20).build();
                otpNavButtons[3] = emailBtn;
                addRenderableWidget(emailBtn);

                this.bwOtpBox = new EditBox(this.font, formX, y + 42, formWidth, 20, Component.translatable("sypass.gui.bw.otp.placeholder"));
                this.bwOtpBox.setMaxLength(32);
                this.bwOtpBox.setHint(Component.translatable("sypass.gui.bw.otp.placeholder"));
                addRenderableWidget(this.bwOtpBox);

                Button confirmOtpBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.confirm"), btn -> {
                    String otp = this.bwOtpBox.getValue().trim();
                    if (!otp.isEmpty()) {
                        List<Button> toDisable = new ArrayList<>();
                        for (Button b : otpNavButtons) {
                            if (b != null) toDisable.add(b);
                        }
                        handleLogin(otp, selected2faMethod, toDisable);
                    }
                }).bounds(formX, y + 68, formWidth, 20).build();
                otpNavButtons[0] = confirmOtpBtn;
                addRenderableWidget(confirmOtpBtn);

                int nextBtnY = y + 92;
                if ("1".equals(selected2faMethod)) {
                    Button sendEmailBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.send_email"), btn -> {
                        btn.active = false;
                        if (otpNavButtons[1] != null) otpNavButtons[1].active = false;
                        setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());
                        BitwardenManager.getExecutor().execute(() -> {
                            BitwardenManager.sendEmail2faCode(savedEmail, savedPassword);
                            if (this.minecraft != null) {
                                this.minecraft.execute(() -> {
                                    btn.active = true;
                                    if (otpNavButtons[1] != null) otpNavButtons[1].active = true;
                                    setStatusMessage("§a" + Component.translatable("sypass.gui.bw.otp.email_sent").getString());
                                });
                            }
                        });
                    }).bounds(formX, nextBtnY, formWidth, 20)
                      .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.otp.send_email_tooltip")))
                      .build();
                    otpNavButtons[4] = sendEmailBtn;
                    addRenderableWidget(sendEmailBtn);
                    nextBtnY += 24;
                }

                Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
                    this.bwStage = BwStage.LOGIN;
                    this.statusMessage = "";
                    clearWidgets();
                    init();
                }).bounds(formX, nextBtnY, formWidth, 20).build();
                otpNavButtons[1] = backBtn;
                addRenderableWidget(backBtn);
            }
            case API_KEY -> {
                this.bwClientIdBox = new EditBox(this.font, formX, y + 16, formWidth, 20, Component.translatable("sypass.gui.bw.apikey.client_id"));
                this.bwClientIdBox.setMaxLength(256);
                this.bwClientIdBox.setHint(Component.translatable("sypass.gui.bw.apikey.client_id"));
                addRenderableWidget(this.bwClientIdBox);

                this.bwClientSecretBox = new EditBox(this.font, formX, y + 54, formWidth, 20, Component.translatable("sypass.gui.bw.apikey.client_secret"));
                this.bwClientSecretBox.setMaxLength(256);
                this.bwClientSecretBox.setHint(Component.translatable("sypass.gui.bw.apikey.client_secret"));
                this.bwClientSecretBox.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
                addRenderableWidget(this.bwClientSecretBox);

                this.bwMasterPasswordBox = new EditBox(this.font, formX, y + 92, formWidth, 20, Component.translatable("sypass.gui.bw.login.password_placeholder"));
                this.bwMasterPasswordBox.setMaxLength(256);
                this.bwMasterPasswordBox.setHint(Component.translatable("sypass.gui.bw.login.password_placeholder"));
                this.bwMasterPasswordBox.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
                addRenderableWidget(this.bwMasterPasswordBox);

                final Button[] apiNavButtons = new Button[2]; // [0]=loginBtn, [1]=backBtn

                Button loginBtn = Button.builder(Component.translatable("sypass.gui.bw.apikey.login_btn"), btn -> {
                    String id = this.bwClientIdBox.getValue().trim();
                    String secret = this.bwClientSecretBox.getValue().trim();
                    String masterPass = this.bwMasterPasswordBox.getValue().trim();

                    if (id.isEmpty() || secret.isEmpty() || masterPass.isEmpty()) {
                        setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_apikey").getString());
                        return;
                    }

                    btn.active = false;
                    if (apiNavButtons[1] != null) apiNavButtons[1].active = false;
                    setStatusMessage("§e" + Component.translatable("sypass.gui.bw.login.logging_in_apikey").getString());
                    BitwardenManager.getExecutor().execute(() -> {
                        BitwardenManager.BwLoginResponse resp = BitwardenManager.loginWithApiKey(id, secret, masterPass);
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                btn.active = true;
                                if (apiNavButtons[1] != null) apiNavButtons[1].active = true;
                                setStatusMessage(resp.message());
                                if (resp.isSuccess()) {
                                    this.bwStage = BwStage.LOGGED_IN;
                                    clearWidgets();
                                    init();
                                }
                            });
                        }
                    });
                }).bounds(formX, y + 120, formWidth, 20).build();
                apiNavButtons[0] = loginBtn;
                addRenderableWidget(loginBtn);

                Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
                    this.bwStage = BwStage.LOGIN;
                    clearWidgets();
                    init();
                }).bounds(formX, y + 144, formWidth, 20).build();
                apiNavButtons[1] = backBtn;
                addRenderableWidget(backBtn);
            }
            case SESSION_KEY -> {
                this.bwSessionKeyBox = new EditBox(this.font, formX, y + 40, formWidth, 20, Component.translatable("sypass.gui.bw.session.placeholder"));
                this.bwSessionKeyBox.setMaxLength(256);
                this.bwSessionKeyBox.setHint(Component.translatable("sypass.gui.bw.session.placeholder"));
                addRenderableWidget(this.bwSessionKeyBox);

                final Button[] sessionNavButtons = new Button[2]; // [0]=unlockBtn, [1]=backBtn

                Button unlockBtn = Button.builder(Component.translatable("sypass.gui.bw.session.unlock_btn"), btn -> {
                    String key = this.bwSessionKeyBox.getValue().trim();
                    if (key.isEmpty()) {
                        setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_session").getString());
                        return;
                    }

                    btn.active = false;
                    if (sessionNavButtons[1] != null) sessionNavButtons[1].active = false;
                    setStatusMessage("§e" + Component.translatable("sypass.gui.bw.session.verifying").getString());
                    BitwardenManager.getExecutor().execute(() -> {
                        BitwardenManager.BwLoginResponse resp = BitwardenManager.loginWithSessionKey(key);
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                btn.active = true;
                                if (sessionNavButtons[1] != null) sessionNavButtons[1].active = true;
                                setStatusMessage(resp.message());
                                if (resp.isSuccess()) {
                                    this.bwStage = BwStage.LOGGED_IN;
                                    clearWidgets();
                                    init();
                                }
                            });
                        }
                    });
                }).bounds(formX, y + 70, formWidth, 20).build();
                sessionNavButtons[0] = unlockBtn;
                addRenderableWidget(unlockBtn);

                Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
                    this.bwStage = BwStage.LOGIN;
                    clearWidgets();
                    init();
                }).bounds(formX, y + 96, formWidth, 20).build();
                sessionNavButtons[1] = backBtn;
                addRenderableWidget(backBtn);
            }
            case LOGGED_IN -> {
                int btnW = formWidth;
                int startY = y + 25;

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.pull"), btn -> {
                    btn.active = false;
                    BitwardenManager.getExecutor().execute(() -> {
                        BitwardenManager.BwSyncResult res = BitwardenManager.pullFromBitwarden();
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                btn.active = true;
                                setStatusMessage(res.message());
                            });
                        }
                    });
                }).bounds(formX, startY, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.push"), btn -> {
                    btn.active = false;
                    BitwardenManager.getExecutor().execute(() -> {
                        BitwardenManager.BwSyncResult res = BitwardenManager.pushToBitwarden();
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                btn.active = true;
                                setStatusMessage(res.message());
                            });
                        }
                    });
                }).bounds(formX, startY + 24, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.full_sync"), btn -> {
                    btn.active = false;
                    handleFullSync();
                    btn.active = true;
                }).bounds(formX, startY + 48, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.literal("§c" + Component.translatable("sypass.gui.bw.logged.logout").getString()), btn -> {
                    BitwardenManager.logout();
                    this.bwStage = BwStage.LOGIN;
                    this.cachedStatusInfo = null;
                    setStatusMessage("§e" + Component.translatable("sypass.gui.bw.logout_success").getString());
                    clearWidgets();
                    init();
                }).bounds(formX, startY + 76, btnW, 20).build());
            }
        }

        addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                .bounds((this.width - 140) / 2, this.height - 26, 140, 20).build());
    }

    private void updateBwPasswordMask() {
        if (this.bwPasswordBox != null) {
            if (showMasterPassword) {
                this.bwPasswordBox.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward(text, Style.EMPTY));
            } else {
                this.bwPasswordBox.setFormatter((text, firstCharIndex) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
            }
        }
    }

    private void handleLogin(String otp, String method, List<Button> buttonsToDisable) {
        if (buttonsToDisable != null) {
            for (Button b : buttonsToDisable) {
                if (b != null) b.active = false;
            }
        }
        setStatusMessage("§e" + Component.translatable("sypass.gui.bw.login.logging_in").getString());

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwLoginResponse resp = BitwardenManager.login(savedEmail, savedPassword, otp, method);
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (buttonsToDisable != null) {
                        for (Button b : buttonsToDisable) {
                            if (b != null) b.active = true;
                        }
                    }
                    switch (resp.status()) {
                        case SUCCESS -> {
                            this.bwStage = BwStage.LOGGED_IN;
                            setStatusMessage("§a" + resp.message());
                            clearWidgets();
                            init();
                            handleFullSync();
                        }
                        case NEED_OTP -> {
                            this.bwStage = BwStage.OTP;
                            if (resp.message().toLowerCase().contains("email")) {
                                this.selected2faMethod = "1";
                            }
                            setStatusMessage("§6" + resp.message());
                            clearWidgets();
                            init();
                        }
                        case INVALID_OTP -> {
                            this.bwStage = BwStage.OTP;
                            setStatusMessage("§c" + resp.message());
                        }
                        default -> {
                            if (otp != null && !otp.isBlank()) {
                                this.bwStage = BwStage.OTP;
                            } else {
                                this.bwStage = BwStage.LOGIN;
                            }
                            setStatusMessage("§c" + resp.message());
                        }
                    }
                });
            }
        });
    }

    // ==========================================
    // Вкладка 3: Налаштування (SETTINGS)
    // ==========================================
    private void initSettingsTab(int contentX, int contentWidth) {
        int cardWidth = Math.min(420, contentWidth);
        int gap = 8;
        int colWidth = (cardWidth - gap) / 2;
        int cardX = (this.width - cardWidth) / 2;

        int totalSettingsH = 158;
        int startY = Math.max(38, (this.height - totalSettingsH - 30) / 2);
        int y = startY;

        switch (settingsStage) {
            case MAIN -> {
                // Рядок 1: Авто-вхід (ліворуч) та Сповіщення (праворуч)
                boolean autoLogin = SYPassConfig.isAutoLoginEnabled();
                Button autoLoginBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.autologin", autoLogin ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isAutoLoginEnabled();
                            SYPassConfig.setAutoLoginEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.autologin", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.autologin.tooltip"))).build();
                addRenderableWidget(autoLoginBtn);

                boolean toasts = SYPassConfig.isToastsEnabled();
                Button toastsBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.toasts", toasts ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isToastsEnabled();
                            SYPassConfig.setToastsEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.toasts", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX + colWidth + gap, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.toasts.tooltip"))).build();
                addRenderableWidget(toastsBtn);

                y += 24;
                // Рядок 2: Розумний авто-вхід (ліворуч) та Розумна реєстрація (праворуч)
                boolean smartLogin = SYPassConfig.isSmartAutoLoginEnabled();
                Button smartLoginBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.smart_login", smartLogin ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isSmartAutoLoginEnabled();
                            SYPassConfig.setSmartAutoLoginEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.smart_login", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.smart_login.tooltip"))).build();
                addRenderableWidget(smartLoginBtn);

                boolean smartRegister = SYPassConfig.isSmartAutoRegisterEnabled();
                Button smartRegisterBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.smart_register", smartRegister ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isSmartAutoRegisterEnabled();
                            SYPassConfig.setSmartAutoRegisterEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.smart_register", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX + colWidth + gap, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.smart_register.tooltip"))).build();
                addRenderableWidget(smartRegisterBtn);

                y += 24;
                // Рядок 3: Захист від перезапису (ліворуч) та Захист чату (праворуч)
                boolean protectOverwrite = SYPassConfig.isPreventRegisterOverwriteEnabled();
                Button protectOverwriteBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.prevent_overwrite", protectOverwrite ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isPreventRegisterOverwriteEnabled();
                            SYPassConfig.setPreventRegisterOverwriteEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.prevent_overwrite", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.prevent_overwrite.tooltip"))).build();
                addRenderableWidget(protectOverwriteBtn);

                boolean chatProtect = SYPassConfig.isChatLeakProtectionEnabled();
                String chatStatusStr = chatProtect ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§7" + Component.translatable("sypass.gui.settings.off").getString();
                Button chatProtectMenuBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.chat_protect.menu_btn", chatStatusStr),
                        b -> {
                            this.settingsStage = SettingsStage.CHAT_PROTECTION;
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX + colWidth + gap, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.chat_protect.tooltip"))).build();
                addRenderableWidget(chatProtectMenuBtn);

                y += 26;
                // Рядок 4: Числові регулятори (Затримка ліворуч, Довжина пароля праворуч)
                int dBtnW = (colWidth - 6) / 4;

                addRenderableWidget(Button.builder(Component.literal("-10t"), b -> {
                    SYPassConfig.setAutoLoginDelayTicks(SYPassConfig.getAutoLoginDelayTicks() - 10);
                }).bounds(cardX, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("-5t"), b -> {
                    SYPassConfig.setAutoLoginDelayTicks(SYPassConfig.getAutoLoginDelayTicks() - 5);
                }).bounds(cardX + dBtnW + 2, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("+5t"), b -> {
                    SYPassConfig.setAutoLoginDelayTicks(SYPassConfig.getAutoLoginDelayTicks() + 5);
                }).bounds(cardX + (dBtnW + 2) * 2, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("+10t"), b -> {
                    SYPassConfig.setAutoLoginDelayTicks(SYPassConfig.getAutoLoginDelayTicks() + 10);
                }).bounds(cardX + (dBtnW + 2) * 3, y + 12, dBtnW, 18).build());

                int lStartX = cardX + colWidth + gap;
                addRenderableWidget(Button.builder(Component.literal("-4"), b -> {
                    SYPassConfig.setDefaultPasswordLength(SYPassConfig.getDefaultPasswordLength() - 4);
                }).bounds(lStartX, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("-1"), b -> {
                    SYPassConfig.setDefaultPasswordLength(SYPassConfig.getDefaultPasswordLength() - 1);
                }).bounds(lStartX + dBtnW + 2, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("+1"), b -> {
                    SYPassConfig.setDefaultPasswordLength(SYPassConfig.getDefaultPasswordLength() + 1);
                }).bounds(lStartX + (dBtnW + 2) * 2, y + 12, dBtnW, 18).build());

                addRenderableWidget(Button.builder(Component.literal("+4"), b -> {
                    SYPassConfig.setDefaultPasswordLength(SYPassConfig.getDefaultPasswordLength() + 4);
                }).bounds(lStartX + (dBtnW + 2) * 3, y + 12, dBtnW, 18).build());

                y += 34;
                // Рядок 5: Підменю (Резервні копії ліворуч, Bitwarden праворуч)
                Button backupMenuBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.backup.menu_btn"),
                        b -> {
                            this.settingsStage = SettingsStage.BACKUP;
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.backup.menu_btn.tooltip"))).build();
                addRenderableWidget(backupMenuBtn);

                boolean bwEnabled = SYPassConfig.isBitwardenEnabled();
                String bwStatusStr = bwEnabled ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§7" + Component.translatable("sypass.gui.settings.off").getString();
                Button bwMenuBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.bw.menu_btn", bwStatusStr),
                        b -> {
                            this.settingsStage = SettingsStage.BITWARDEN;
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX + colWidth + gap, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.bw.menu_btn.tooltip"))).build();
                addRenderableWidget(bwMenuBtn);

                y += 24;
                // Рядок 6: Майстер-пароль (ліворуч) та Шаблони авто-входу (праворуч)
                boolean mpEnabled = SYPassConfig.isMasterPasswordEnabled();
                String mpStatusStr = mpEnabled ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§7" + Component.translatable("sypass.gui.settings.off").getString();
                Button masterPassBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.master_pass.menu_btn", mpStatusStr),
                        b -> {
                            this.settingsStage = SettingsStage.MASTER_PASSWORD;
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.master_pass.tooltip"))).build();
                addRenderableWidget(masterPassBtn);

                Button patternsBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.patterns.menu_btn"),
                        b -> {
                            this.settingsStage = SettingsStage.LOGIN_PATTERNS;
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX + colWidth + gap, y, colWidth, 20)
                 .tooltip(Tooltip.create(Component.translatable("sypass.gui.settings.patterns.tooltip"))).build();
                addRenderableWidget(patternsBtn);

                y += 24;
                // Рядок 7: Відкрити папку config/sypass — ширина точно збігається з 2 колонками вище (cardWidth)
                Button openFolderBtn = Button.builder(Component.translatable("sypass.gui.bw.button.open_folder"), b -> {
                    File dir = PlatformHelper.get().getConfigDir().resolve("sypass").toFile();
                    Util.getPlatform().openFile(dir);
                }).bounds(cardX, y, cardWidth, 20)
                  .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.open_folder.tooltip"))).build();
                addRenderableWidget(openFolderBtn);
            }
            case CHAT_PROTECTION -> {
                boolean chatProtect = SYPassConfig.isChatLeakProtectionEnabled();
                Button toggleBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.chat_protect", chatProtect ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isChatLeakProtectionEnabled();
                            SYPassConfig.setChatLeakProtectionEnabled(newVal);
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX, y + 25, cardWidth, 20).build();
                addRenderableWidget(toggleBtn);

                SYPassConfig.ChatProtectionScope scope = SYPassConfig.getChatProtectionScope();
                Button scopeBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.chat_scope.mode", Component.translatable(scope.getTranslationKey())),
                        b -> {
                            if (!chatProtect) return;
                            SYPassConfig.ChatProtectionScope newScope = (scope == SYPassConfig.ChatProtectionScope.CURRENT_SERVER)
                                    ? SYPassConfig.ChatProtectionScope.ALL_SERVERS
                                    : SYPassConfig.ChatProtectionScope.CURRENT_SERVER;
                            SYPassConfig.setChatProtectionScope(newScope);
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX, y + 65, cardWidth, 20).build();
                scopeBtn.active = chatProtect;
                addRenderableWidget(scopeBtn);

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
                    this.settingsStage = SettingsStage.MAIN;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 120, cardWidth, 20).build());
            }
            case BACKUP -> {
                this.backupPassBox = new EditBox(this.font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.settings.backup.pass_placeholder"));
                this.backupPassBox.setMaxLength(128);
                this.backupPassBox.setHint(Component.translatable("sypass.gui.settings.backup.pass_placeholder"));
                if (!this.exportAsCsv) {
                    addRenderableWidget(this.backupPassBox);
                }

                Component formatLabel = exportAsCsv
                        ? Component.translatable("sypass.gui.settings.backup.csv_checkbox")
                        : Component.translatable("sypass.gui.settings.backup.format_json");

                Button formatBtn = Button.builder(formatLabel, b -> {
                    this.exportAsCsv = !this.exportAsCsv;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 50, cardWidth, 20).build();
                addRenderableWidget(formatBtn);

                int btnW = (cardWidth - 8) / 3;
                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.export"), b -> {
                    if (this.exportAsCsv) {
                        String file = PasswordManager.exportBitwardenCsv();
                        if (file != null) setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.exported_csv", file).getString());
                    } else {
                        String pass = (this.backupPassBox != null) ? this.backupPassBox.getValue().trim() : "";
                        String file = PasswordManager.exportBackup(pass);
                        if (file != null) setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.exported", file).getString());
                    }
                }).bounds(cardX, y + 75, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.import"), b -> {
                    int imported = this.exportAsCsv ? PasswordManager.importLatestCsvBackup() : PasswordManager.importLatestBackup(this.backupPassBox != null ? this.backupPassBox.getValue().trim() : null);
                    if (imported >= 0) {
                        setStatusMessage("§a" + Component.translatable("sypass.gui.settings.backup.imported", imported).getString());
                    } else {
                        setStatusMessage("§c" + Component.translatable("sypass.gui.settings.backup.import_failed").getString());
                    }
                }).bounds(cardX + btnW + 4, y + 75, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.settings.backup.open_folder"), b -> {
                    File dir = PlatformHelper.get().getConfigDir().resolve("sypass").resolve("backups").toFile();
                    if (!dir.exists()) dir.mkdirs();
                    Util.getPlatform().openFile(dir);
                }).bounds(cardX + (btnW + 4) * 2, y + 75, btnW, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
                    this.settingsStage = SettingsStage.MAIN;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 105, cardWidth, 20).build());
            }
            case BITWARDEN -> {
                boolean bwEn = SYPassConfig.isBitwardenEnabled();
                Button enableBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.enable_bw", bwEn ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !SYPassConfig.isBitwardenEnabled();
                            SYPassConfig.setBitwardenEnabled(newVal);
                            clearWidgets();
                            init();
                        }
                ).bounds(cardX, y + 25, cardWidth, 20).build();
                addRenderableWidget(enableBtn);

                // Auto-sync: залишається видимим, стає сірим/неактивним при вимкненій опції
                boolean autoSync = SYPassConfig.isAutoSyncEnabled();
                Button autoSyncBtn = Button.builder(
                        Component.translatable("sypass.gui.settings.autosync", autoSync ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            if (!bwEn) return;
                            boolean newVal = !SYPassConfig.isAutoSyncEnabled();
                            SYPassConfig.setAutoSyncEnabled(newVal);
                            b.setMessage(Component.translatable("sypass.gui.settings.autosync", newVal ? "§a" + Component.translatable("sypass.gui.settings.on").getString() : "§c" + Component.translatable("sypass.gui.settings.off").getString()));
                        }
                ).bounds(cardX, y + 50, cardWidth, 20).build();
                autoSyncBtn.active = bwEn;
                addRenderableWidget(autoSyncBtn);

                // Поле URL сервера: залишається видимим, стає неактивним при вимкненій опції
                EditBox serverUrlBox = new EditBox(this.font, cardX, y + 85, cardWidth - 28, 20, Component.translatable("sypass.gui.settings.server_url"));
                serverUrlBox.setMaxLength(256);
                serverUrlBox.setValue(SYPassConfig.getCustomServerUrl());
                serverUrlBox.setHint(Component.translatable("sypass.gui.settings.server_url.placeholder"));
                serverUrlBox.setEditable(bwEn);
                serverUrlBox.setTextColor(bwEn ? 0xFFFFFF : 0x777777);
                addRenderableWidget(serverUrlBox);

                Button saveUrlBtn = Button.builder(Component.literal("✔"), b -> {
                    if (!bwEn) return;
                    String url = serverUrlBox.getValue().trim();
                    SYPassConfig.setCustomServerUrl(url);
                    BitwardenManager.configureServer(url);
                    setStatusMessage("§a" + Component.translatable("sypass.gui.settings.server_url.saved").getString());
                }).bounds(cardX + cardWidth - 24, y + 85, 24, 20).build();
                saveUrlBtn.active = bwEn;
                addRenderableWidget(saveUrlBtn);

                // Кнопка відкриття папки
                Button openFolderBwBtn = Button.builder(Component.translatable("sypass.gui.bw.button.open_folder"), b -> {
                    File dir = PlatformHelper.get().getConfigDir().resolve("sypass").toFile();
                    Util.getPlatform().openFile(dir);
                }).bounds(cardX, y + 110, cardWidth, 20).build();
                openFolderBwBtn.active = bwEn;
                addRenderableWidget(openFolderBwBtn);

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
                    this.settingsStage = SettingsStage.MAIN;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 135, cardWidth, 20).build());
            }
            case MASTER_PASSWORD -> {
                boolean mpEn = SYPassConfig.isMasterPasswordEnabled();
                if (!mpEn) {
                    this.mpNewPassBox = new EditBox(this.font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
                    this.mpNewPassBox.setMaxLength(128);
                    this.mpNewPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
                    addRenderableWidget(this.mpNewPassBox);

                    this.mpConfirmPassBox = new EditBox(this.font, cardX, y + 50, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.confirm_pass"));
                    this.mpConfirmPassBox.setMaxLength(128);
                    this.mpConfirmPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.confirm_pass"));
                    addRenderableWidget(this.mpConfirmPassBox);

                    addRenderableWidget(Button.builder(Component.translatable("sypass.gui.settings.master_pass.setup"), b -> {
                        String p1 = (this.mpNewPassBox != null) ? this.mpNewPassBox.getValue() : "";
                        String p2 = (this.mpConfirmPassBox != null) ? this.mpConfirmPassBox.getValue() : "";
                        if (p1.length() < 4) {
                            setStatusMessage("§cPassword too short (min 4 chars)!");
                            return;
                        }
                        if (!p1.equals(p2)) {
                            setStatusMessage("§c" + Component.translatable("sypass.gui.settings.master_pass.mismatch").getString());
                            return;
                        }
                        if (PasswordManager.enableMasterPassword(p1.toCharArray())) {
                            setStatusMessage("§a" + Component.translatable("sypass.gui.settings.master_pass.success_on").getString());
                            this.settingsStage = SettingsStage.MAIN;
                            clearWidgets();
                            init();
                        } else {
                            setStatusMessage("§cFailed to enable Master Password!");
                        }
                    }).bounds(cardX, y + 75, cardWidth, 20).build());
                } else {
                    this.mpNewPassBox = new EditBox(this.font, cardX, y + 25, cardWidth, 20, Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
                    this.mpNewPassBox.setMaxLength(128);
                    this.mpNewPassBox.setHint(Component.translatable("sypass.gui.settings.master_pass.enter_pass"));
                    addRenderableWidget(this.mpNewPassBox);

                    addRenderableWidget(Button.builder(Component.translatable("sypass.gui.settings.master_pass.remove"), b -> {
                        String p1 = (this.mpNewPassBox != null) ? this.mpNewPassBox.getValue() : "";
                        if (PasswordManager.disableMasterPassword(p1.toCharArray())) {
                            setStatusMessage("§a" + Component.translatable("sypass.gui.settings.master_pass.success_off").getString());
                            this.settingsStage = SettingsStage.MAIN;
                            clearWidgets();
                            init();
                        } else {
                            setStatusMessage("§c" + Component.translatable("sypass.gui.lock.wrong_pass").getString());
                        }
                    }).bounds(cardX, y + 50, cardWidth, 20).build());
                }

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
                    this.settingsStage = SettingsStage.MAIN;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 105, cardWidth, 20).build());
            }
            case LOGIN_PATTERNS -> {
                // Section 1: Template & Presets
                this.regTemplateBox = new EditBox(this.font, cardX, y + 16, cardWidth - 65, 20, Component.translatable("sypass.gui.settings.reg_template.title"));
                this.regTemplateBox.setMaxLength(128);
                this.regTemplateBox.setValue(SYPassConfig.getRegisterCommandTemplate());
                addRenderableWidget(this.regTemplateBox);

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.save"), b -> {
                    if (this.regTemplateBox != null) {
                        SYPassConfig.setRegisterCommandTemplate(this.regTemplateBox.getValue().trim());
                        setStatusMessage("§a" + Component.translatable("sypass.gui.settings.reg_template.saved").getString());
                    }
                }).bounds(cardX + cardWidth - 60, y + 16, 60, 20).build());

                int pw = (cardWidth - 6) / 4;
                String[] presets = new String[]{"/register %p% %p%", "/reg %p% %p%", "/register %p%", "/reg %p%"};
                for (int i = 0; i < presets.length; i++) {
                    String preset = presets[i];
                    addRenderableWidget(Button.builder(Component.literal(preset), b -> {
                        if (this.regTemplateBox != null) {
                            this.regTemplateBox.setValue(preset);
                            SYPassConfig.setRegisterCommandTemplate(preset);
                            setStatusMessage("§a" + Component.translatable("sypass.gui.settings.reg_template.saved").getString());
                        }
                    }).bounds(cardX + i * (pw + 2), y + 39, pw, 18).build());
                }

                // Section 2: Custom Smart Login Patterns
                int pY = y + 74;
                this.newPatternBox = new EditBox(this.font, cardX, pY, cardWidth - 45, 20, Component.translatable("sypass.gui.settings.custom_patterns.title"));
                this.newPatternBox.setMaxLength(128);
                this.newPatternBox.setHint(Component.translatable("sypass.gui.settings.custom_patterns.placeholder"));
                addRenderableWidget(this.newPatternBox);

                addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    if (this.newPatternBox != null && !this.newPatternBox.getValue().isBlank()) {
                        SYPassConfig.addCustomLoginPattern(this.newPatternBox.getValue().trim());
                        this.newPatternBox.setValue("");
                        setStatusMessage("§a" + Component.translatable("sypass.gui.settings.custom_patterns.added").getString());
                        clearWidgets();
                        init();
                    }
                }).bounds(cardX + cardWidth - 40, pY, 40, 20).build());

                List<String> patterns = SYPassConfig.getCustomLoginPatterns();
                int listY = pY + 24;
                int maxShow = Math.min(3, patterns.size());
                for (int i = 0; i < maxShow; i++) {
                    String pat = patterns.get(i);
                    int itemY = listY + i * 18;
                    addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                        SYPassConfig.removeCustomLoginPattern(pat);
                        clearWidgets();
                        init();
                    }).bounds(cardX + cardWidth - 22, itemY, 22, 16)
                      .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.delete.tooltip")))
                      .build());
                }

                addRenderableWidget(Button.builder(Component.translatable("sypass.gui.bw.otp.back"), b -> {
                    this.settingsStage = SettingsStage.MAIN;
                    clearWidgets();
                    init();
                }).bounds(cardX, y + 160, cardWidth, 20).build());
            }
        }

        addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                .bounds((this.width - 140) / 2, this.height - 26, 140, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (PasswordManager.isVaultLocked()) {
            int y = (this.height - 120) / 2;
            guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.lock.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, y + 5, 0xFFFFFF);
            if (statusMessage != null && !statusMessage.isBlank()) {
                guiGraphics.drawCenteredString(this.font, Component.literal(statusMessage), this.width / 2, y + 105, 0xFFFFFF);
            }
            return;
        }

        int contentWidth = Math.min(440, this.width - 32);

        // Порожній стан на вкладці паролів
        if (activeTab == Tab.LOCAL_PASSWORDS && PasswordManager.getTotalCount() == 0) {
            int emptyY = this.height / 2 - 16;
            guiGraphics.drawCenteredString(this.font, Component.translatable(onlyFavorites ? "sypass.gui.empty.favorites" : "sypass.gui.empty.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, emptyY, 0xFFFFFF);
            guiGraphics.drawCenteredString(this.font, Component.translatable(onlyFavorites ? "sypass.gui.empty.favorites.desc" : "sypass.gui.empty.desc").withStyle(ChatFormatting.GRAY), this.width / 2, emptyY + 12, 0xAAAAAA);
        }

        // Текстові підказки в Settings
        if (activeTab == Tab.SETTINGS) {
            int cardWidth = Math.min(420, contentWidth);
            int gap = 8;
            int colWidth = (cardWidth - gap) / 2;
            int cardX = (this.width - cardWidth) / 2;

            int totalSettingsH = 158;
            int startY = Math.max(38, (this.height - totalSettingsH - 30) / 2);

            if (settingsStage == SettingsStage.MAIN) {
                int delayTicks = SYPassConfig.getAutoLoginDelayTicks();
                float delaySec = delayTicks / 20.0f;
                guiGraphics.drawString(this.font, Component.translatable("sypass.gui.settings.delay", delayTicks, String.format(Locale.ROOT, "%.1f", delaySec)), cardX, startY + 76, 0xCCCCCC, true);

                int curLen = SYPassConfig.getDefaultPasswordLength();
                guiGraphics.drawString(this.font, Component.translatable("sypass.gui.settings.pass_len", curLen), cardX + colWidth + gap, startY + 76, 0xCCCCCC, true);
            } else if (settingsStage == SettingsStage.CHAT_PROTECTION) {
                guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.settings.chat_protect.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, startY + 4, 0xFFFFFF);
                SYPassConfig.ChatProtectionScope scope = SYPassConfig.getChatProtectionScope();
                String hintKey = (scope == SYPassConfig.ChatProtectionScope.CURRENT_SERVER)
                        ? "sypass.gui.settings.chat_scope.current_hint"
                        : "sypass.gui.settings.chat_scope.all_hint";
                guiGraphics.drawWordWrap(this.font, Component.translatable(hintKey).withStyle(ChatFormatting.GRAY), cardX, startY + 94, cardWidth, 0x888888);
            } else if (settingsStage == SettingsStage.BITWARDEN) {
                boolean bwEn = SYPassConfig.isBitwardenEnabled();
                guiGraphics.drawString(this.font, Component.translatable("sypass.gui.settings.server_url"), cardX, startY + 74, bwEn ? 0xCCCCCC : 0x777777, true);
            } else if (settingsStage == SettingsStage.MASTER_PASSWORD) {
                boolean mpEn = SYPassConfig.isMasterPasswordEnabled();
                String titleKey = mpEn ? "sypass.gui.settings.master_pass.remove" : "sypass.gui.settings.master_pass.setup";
                guiGraphics.drawCenteredString(this.font, Component.translatable(titleKey).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, startY + 4, 0xFFFFFF);
            } else if (settingsStage == SettingsStage.LOGIN_PATTERNS) {
                guiGraphics.drawString(this.font, Component.translatable("sypass.gui.settings.reg_template.title"), cardX, startY + 4, 0xCCCCCC, true);
                int pY = startY + 62;
                guiGraphics.drawString(this.font, Component.translatable("sypass.gui.settings.custom_patterns.title"), cardX, pY, 0xCCCCCC, true);

                List<String> patterns = SYPassConfig.getCustomLoginPatterns();
                int listY = pY + 39;
                if (patterns.isEmpty()) {
                    guiGraphics.drawString(this.font, Component.literal("§7(Default: /login, /l, enter, password, увійдіть, войдите...)"), cardX, listY + 3, 0x888888, true);
                } else {
                    int maxShow = Math.min(3, patterns.size());
                    for (int i = 0; i < maxShow; i++) {
                        String pat = patterns.get(i);
                        guiGraphics.drawString(this.font, Component.literal("§b• " + pat), cardX, listY + i * 18 + 4, 0x55FFFF, true);
                    }
                }
            }
        }

        // Bitwarden заголовки (читаються суто з пам'яті / кешу без виклику системних процесів)
        if (activeTab == Tab.BITWARDEN) {
            int formWidth = Math.min(320, contentWidth);
            int formX = (this.width - formWidth) / 2;
            int y = 42;

            switch (bwStage) {
                case CHECKING_STATUS -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.checking_status").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height / 2 - 10, 0xFFFFFF);
                }
                case CLI_NOT_FOUND -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.not_found.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), this.width / 2, y, 0xFF5555);
                    guiGraphics.drawWordWrap(this.font, Component.translatable("sypass.gui.bw.not_found.desc1").withStyle(ChatFormatting.GRAY), formX, y + 14, formWidth, 0xAAAAAA);
                }
                case LOGIN -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.login.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, y, 0xFFFFFF);
                }
                case OTP -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.otp.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, y, 0xFFFFFF);
                }
                case API_KEY -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.apikey.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, y, 0xFFFFFF);
                }
                case SESSION_KEY -> {
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.session.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), this.width / 2, y, 0xFFFFFF);
                    guiGraphics.drawWordWrap(this.font, Component.translatable("sypass.gui.bw.session.desc2").withStyle(ChatFormatting.GREEN), formX, y + 14, formWidth, 0x55FF55);
                }
                case LOGGED_IN -> {
                    String userEmail = (cachedStatusInfo != null && cachedStatusInfo.userEmail() != null && !cachedStatusInfo.userEmail().isBlank())
                            ? cachedStatusInfo.userEmail()
                            : Component.translatable("sypass.gui.tab.bitwarden").getString();

                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.logged.connected").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), this.width / 2, y, 0x55FF55);
                    guiGraphics.drawCenteredString(this.font, Component.translatable("sypass.gui.bw.logged.account", BitwardenManager.maskEmail(userEmail)).withStyle(ChatFormatting.WHITE), this.width / 2, y + 12, 0xFFFFFF);
                }
            }
        }

        // Статусне повідомлення внизу екрана
        if (!statusMessage.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.literal(this.statusMessage), this.width / 2, this.height - 40, 0x55FF55);
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int contentWidth = Math.min(440, this.width - 32);
        int panelWidth = contentWidth + 16;
        int panelX = (this.width - panelWidth) / 2;

        guiGraphics.fill(panelX, 4, panelX + panelWidth, this.height - 4, 0xD0101010);
        guiGraphics.renderOutline(panelX, 4, panelWidth, this.height - 8, 0xFF3C3C3C);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==========================================
    // Внутрішній віджет списку паролів (Vanilla)
    // ==========================================
    public static class PasswordListWidget extends ContainerObjectSelectionList<PasswordListWidget.PasswordEntry> {
        private final SYPassScreen parentScreen;
        private final int fixedRowWidth;

        public PasswordListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight, int fixedRowWidth, SYPassScreen parentScreen) {
            super(minecraft, width, height, y, itemHeight);
            this.parentScreen = parentScreen;
            this.fixedRowWidth = fixedRowWidth;
        }

        public void refresh(String query, SortMode sortMode, boolean onlyFavorites) {
            this.clearEntries();
            Map<String, Map<String, PasswordManager.AccountData>> allData = PasswordManager.getAllData();
            List<PasswordEntryData> dataList = new ArrayList<>();

            String lowerQuery = (query != null) ? query.toLowerCase(Locale.ROOT).trim() : "";

            for (Map.Entry<String, Map<String, PasswordManager.AccountData>> sEntry : allData.entrySet()) {
                String serverIp = sEntry.getKey();
                for (Map.Entry<String, PasswordManager.AccountData> aEntry : sEntry.getValue().entrySet()) {
                    String username = aEntry.getKey();
                    PasswordManager.AccountData acc = aEntry.getValue();

                    if (onlyFavorites && !acc.isFavorite()) {
                        continue;
                    }

                    if (!lowerQuery.isEmpty() && !serverIp.toLowerCase(Locale.ROOT).contains(lowerQuery)
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
                    if (sComp != 0) return sComp;
                    return a.username.compareToIgnoreCase(b.username);
                });
                case ALPHABETICAL -> dataList.sort((a, b) -> {
                    int sComp = a.serverIp.compareToIgnoreCase(b.serverIp);
                    if (sComp != 0) return sComp;
                    return a.username.compareToIgnoreCase(b.username);
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

        private record PasswordEntryData(String serverIp, String username, PasswordManager.AccountData data) {}

        public static class PasswordEntry extends ContainerObjectSelectionList.Entry<PasswordEntry> {
            private final SYPassScreen screen;
            private final String serverIp;
            private final String username;
            private final PasswordManager.AccountData data;
            private final String key;

            private final List<AbstractWidget> children = new ArrayList<>();
            private final Button favBtn;
            private final Button copyBtn;
            private final Button toggleEyeBtn;
            private final Button editBtn;
            private Button deleteBwBtn = null;
            private final Button deleteBtn;

            public PasswordEntry(SYPassScreen screen, String serverIp, String username, PasswordManager.AccountData data) {
                this.screen = screen;
                this.serverIp = serverIp;
                this.username = username;
                this.data = data;
                this.key = serverIp + ":::" + username;

                boolean isRevealed = screen.revealedPasswords.contains(key);
                boolean canDeleteFromBw = SYPassConfig.isBitwardenEnabled() && data.isSynced() && BitwardenManager.hasActiveSession();

                // 1. Кнопка Обране (★)
                this.favBtn = Button.builder(Component.literal(data.isFavorite() ? "§e★" : "§7☆"), btn -> {
                    PasswordManager.toggleFavorite(serverIp, username);
                    screen.refreshPasswordList();
                }).bounds(0, 0, 20, 20)
                  .tooltip(Tooltip.create(Component.translatable(data.isFavorite() ? "sypass.gui.button.unfavorite.tooltip" : "sypass.gui.button.favorite.tooltip")))
                  .build();
                children.add(favBtn);

                // 2. Кнопка Копіювати (📋)
                this.copyBtn = Button.builder(Component.literal("📋"), btn -> {
                    PasswordManager.updateLastUsed(serverIp, username);
                    PasswordManager.copyPasswordToClipboard(serverIp, username);
                    screen.setStatusMessage(Component.translatable("sypass.gui.status.copied", username).getString());
                }).bounds(0, 0, 20, 20)
                  .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.copy.tooltip")))
                  .build();
                children.add(copyBtn);

                // 3. Кнопка Показати/Сховати пароль (●/○)
                this.toggleEyeBtn = Button.builder(Component.literal(isRevealed ? "§a●" : "§7○"), btn -> {
                    if (screen.revealedPasswords.contains(key)) {
                        screen.revealedPasswords.remove(key);
                        btn.setMessage(Component.literal("§7○"));
                        btn.setTooltip(Tooltip.create(Component.translatable("sypass.gui.button.show")));
                    } else {
                        screen.revealedPasswords.add(key);
                        btn.setMessage(Component.literal("§a●"));
                        btn.setTooltip(Tooltip.create(Component.translatable("sypass.gui.button.hide")));
                    }
                }).bounds(0, 0, 20, 20)
                  .tooltip(Tooltip.create(Component.translatable(isRevealed ? "sypass.gui.button.hide" : "sypass.gui.button.show")))
                  .build();
                children.add(toggleEyeBtn);

                // 4. Кнопка Редагувати (✎)
                this.editBtn = Button.builder(Component.literal("✎"), btn -> {
                    if (screen.minecraft != null) {
                        PasswordManager.AccountData realAcc = PasswordManager.getPassword(serverIp, username);
                        String pass = (realAcc != null) ? realAcc.getPasswordAsString() : "";
                        screen.minecraft.setScreen(new EditPasswordScreen(screen, serverIp, username, pass, data.command(), data.isSynced()));
                    }
                }).bounds(0, 0, 20, 20)
                  .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.edit.tooltip")))
                  .build();
                children.add(editBtn);

                // 5. Кнопка видалення лише з Bitwarden (якщо синхронізовано)
                if (canDeleteFromBw) {
                    boolean isPendingBw = key.equals(screen.pendingBwDeleteKey);
                    boolean isDeleting = key.equals(screen.activeBwDeletingKey);
                    boolean isSuccess = key.equals(screen.activeBwSuccessKey);

                    String btnText;
                    if (isDeleting) {
                        int dotIdx = (int) ((System.currentTimeMillis() / 350L) % 3);
                        btnText = LOADING_DOTS[dotIdx];
                    } else if (isSuccess) {
                        btnText = "§a✔";
                    } else if (isPendingBw) {
                        btnText = "§4✔?";
                    } else {
                        btnText = "§c☁-";
                    }

                    this.deleteBwBtn = Button.builder(Component.literal(btnText), btn -> {
                        if (key.equals(screen.activeBwDeletingKey) || key.equals(screen.activeBwSuccessKey)) {
                            return;
                        }
                        if (key.equals(screen.pendingBwDeleteKey)) {
                            screen.pendingBwDeleteKey = null;
                            screen.activeBwDeletingKey = key;
                            btn.active = false;
                            btn.setMessage(Component.literal("§e."));
                            screen.setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());

                            BitwardenManager.deleteFromBitwardenOnlyAsync(serverIp, username, data.remoteId()).thenAccept(success -> {
                                if (screen.minecraft != null) {
                                    screen.minecraft.execute(() -> {
                                        screen.activeBwDeletingKey = null;
                                        if (success) {
                                            screen.activeBwSuccessKey = key;
                                            screen.bwSuccessUntilMs = System.currentTimeMillis() + 2000L;
                                            screen.setStatusMessage(Component.translatable("sypass.gui.status.deleted_bw", username, serverIp).getString());
                                            btn.setMessage(Component.literal("§a✔"));
                                        } else {
                                            screen.setStatusMessage("§c" + Component.translatable("sypass.gui.status.deleted_bw_failed", username).getString());
                                            screen.refreshPasswordList();
                                        }
                                    });
                                }
                            });
                        } else {
                            screen.pendingBwDeleteKey = key;
                            screen.pendingDeleteKey = null;
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

                // 6. Кнопка Видалити локальний запис (✖)
                boolean isPendingDel = key.equals(screen.pendingDeleteKey);
                this.deleteBtn = Button.builder(Component.literal(isPendingDel ? "§4✔?" : "§c✖"), btn -> {
                    if (key.equals(screen.pendingDeleteKey)) {
                        screen.pendingDeleteKey = null;
                        screen.pendingBwDeleteKey = null;
                        PasswordManager.removePassword(serverIp, username);
                        if (data.isSynced() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
                            BitwardenManager.deleteSingleItemAsync(serverIp, username, data.remoteId());
                        }
                        screen.setStatusMessage(Component.translatable("sypass.gui.status.deleted", username, serverIp).getString());
                        screen.refreshPasswordList();
                    } else {
                        screen.pendingDeleteKey = key;
                        screen.pendingBwDeleteKey = null;
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

                ResourceLocation icon = ServerIconManager.getServerIcon(serverIp);
                guiGraphics.blit(icon, left + 6, cardY + 9, 24, 24, 0.0F, 0.0F, 64, 64, 64, 64);

                Font font = mc.font;
                int textX = left + 36;

                String cloudBadge = SYPassConfig.isBitwardenEnabled() ? (data.isSynced() ? "§a☁ " : "§7☁ ") : "";
                String serverTitle = cloudBadge + (data.isFavorite() ? "§e★ " : "") + "§6§l" + serverIp;
                guiGraphics.drawString(font, Component.literal(serverTitle), textX, cardY + 5, 0xFFFFFF, true);

                boolean isRevealed = screen.revealedPasswords.contains(key);
                String userTitle = isActiveAccount ? "§a§l" + username : "§e" + username;
                String passTitle;
                if (isRevealed) {
                    PasswordManager.AccountData realAcc = PasswordManager.getPassword(serverIp, username);
                    passTitle = (realAcc != null) ? " §b" + realAcc.getPasswordAsString() : " §b";
                } else {
                    passTitle = " §7••••••••";
                }
                guiGraphics.drawString(font, Component.literal(userTitle + passTitle), textX, cardY + 16, 0xFFFFFF, true);

                // Рядок /login тепер має 6px відступу від нижнього краю картки
                guiGraphics.drawString(font, Component.literal("§8" + data.command()), textX, cardY + 27, 0x888888, true);

                // Анімація видалення та успіху на кнопці deleteBwBtn
                if (deleteBwBtn != null) {
                    if (key.equals(screen.activeBwDeletingKey)) {
                        deleteBwBtn.active = false;
                        int dotIdx = (int) ((System.currentTimeMillis() / 350L) % 3);
                        deleteBwBtn.setMessage(Component.literal(LOADING_DOTS[dotIdx]));
                    } else if (key.equals(screen.activeBwSuccessKey)) {
                        deleteBwBtn.active = false;
                        if (System.currentTimeMillis() < screen.bwSuccessUntilMs) {
                            deleteBwBtn.setMessage(Component.literal("§a✔"));
                        } else {
                            screen.activeBwSuccessKey = null;
                            screen.refreshPasswordList();
                        }
                    }
                }

                int btnY = cardY + 11;
                int totalBtnWidth = (children.size() * 22);
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
}
