package com.syntren.sypass.gui;

import com.syntren.sypass.storage.BitwardenManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages the Bitwarden tab stages, login/2FA/API-key/session-key flows, and async vault sync.
 */
final class BitwardenTabController {

    enum Stage {
        CHECKING_STATUS,
        CLI_NOT_FOUND,
        LOGIN,
        OTP,
        API_KEY,
        SESSION_KEY,
        LOGGED_IN
    }

    private final SYPassScreen screen;
    private Stage stage;
    private BitwardenManager.BwStatusInfo cachedStatusInfo = null;

    private String savedEmail = "";
    private String savedPassword = "";
    private String selected2faMethod = "0"; // "0" = Authenticator, "1" = Email
    private boolean showMasterPassword = false;

    private EditBox bwEmailBox;
    private EditBox bwPasswordBox;
    private EditBox bwOtpBox;
    private EditBox bwClientIdBox;
    private EditBox bwClientSecretBox;
    private EditBox bwMasterPasswordBox;
    private EditBox bwSessionKeyBox;

    BitwardenTabController(SYPassScreen screen) {
        this.screen = screen;
        this.stage = BitwardenManager.hasActiveSession() ? Stage.LOGGED_IN : Stage.CHECKING_STATUS;
    }

    void onTabSelected() {
        if (BitwardenManager.hasActiveSession()) {
            this.stage = Stage.LOGGED_IN;
        } else if (this.cachedStatusInfo != null) {
            this.stage = this.cachedStatusInfo.isInstalled() ? Stage.LOGIN : Stage.CLI_NOT_FOUND;
        } else {
            this.stage = Stage.CHECKING_STATUS;
            checkStatusAsync();
        }
    }

    void clearSensitiveFields() {
        this.savedPassword = "";
    }

    void checkStatusAsync() {
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwStatusInfo status = BitwardenManager.getStatusInfo();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                this.cachedStatusInfo = status;
                if (status.isUnlocked() || BitwardenManager.hasActiveSession()) {
                    this.stage = Stage.LOGGED_IN;
                } else if (!status.isInstalled()) {
                    this.stage = Stage.CLI_NOT_FOUND;
                } else {
                    this.stage = Stage.LOGIN;
                }
                screen.rebuildView();
            });
        });
    }

    void handleFullSync(Button triggerButton) {
        if (!BitwardenManager.hasActiveSession()) {
            screen.setStatusMessage("§c" + Component.translatable("sypass.gui.status.not_logged_in").getString());
            return;
        }
        if (triggerButton != null) {
            triggerButton.active = false;
        }
        screen.setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwSyncResult pullRes = BitwardenManager.pullFromBitwarden();
            BitwardenManager.BwSyncResult pushRes = BitwardenManager.pushToBitwarden();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (triggerButton != null) {
                    triggerButton.active = true;
                }
                screen.setStatusMessage("§a" + Component.translatable("sypass.gui.status.sync_success", pullRes.count(), pushRes.count()).getString());
                screen.refreshPasswordList();
            });
        });
    }

    void init(int screenWidth, int screenHeight, int contentWidth, Font font) {
        int formWidth = Math.min(320, contentWidth);
        int formX = (screenWidth - formWidth) / 2;
        int y = 42;

        switch (stage) {
            case CHECKING_STATUS -> {
                // Rendered as centered text
            }
            case CLI_NOT_FOUND -> initCliNotFound(formX, y, formWidth);
            case LOGIN -> initLogin(formX, y, formWidth, font);
            case OTP -> initOtp(formX, y, formWidth, font);
            case API_KEY -> initApiKey(formX, y, formWidth, font);
            case SESSION_KEY -> initSessionKey(formX, y, formWidth, font);
            case LOGGED_IN -> initLoggedIn(formX, y, formWidth);
        }

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> screen.onClose())
                .bounds((screenWidth - 140) / 2, screenHeight - 26, 140, 20).build());
    }

    private void initCliNotFound(int formX, int y, int formWidth) {
        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.button.guide"), btn ->
                GuiHelpers.openExternalLink(Minecraft.getInstance(), screen, "https://bitwarden.com/help/cli/")
        ).bounds(formX, y + 60, formWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.guide.tooltip"))).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.button.open_folder"), btn ->
                GuiHelpers.openConfigFolder()
        ).bounds(formX, y + 84, formWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.button.open_folder.tooltip"))).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.button.check_again"), btn -> {
            BitwardenManager.invalidateStatusCache();
            this.stage = Stage.CHECKING_STATUS;
            checkStatusAsync();
        }).bounds(formX, y + 108, formWidth, 20).build());
    }

    private void initLogin(int formX, int y, int formWidth, Font font) {
        this.bwEmailBox = new EditBox(font, formX, y + 16, formWidth, 20, Component.translatable("sypass.gui.bw.login.email_placeholder"));
        this.bwEmailBox.setMaxLength(256);
        this.bwEmailBox.setValue(this.savedEmail);
        this.bwEmailBox.setHint(Component.translatable("sypass.gui.bw.login.email_placeholder"));
        screen.registerWidget(this.bwEmailBox);

        int passFieldW = formWidth - 26;
        this.bwPasswordBox = new EditBox(font, formX, y + 48, passFieldW, 20, Component.translatable("sypass.gui.bw.login.password_placeholder"));
        this.bwPasswordBox.setMaxLength(256);
        this.bwPasswordBox.setValue(this.savedPassword);
        this.bwPasswordBox.setHint(Component.translatable("sypass.gui.bw.login.password_placeholder"));
        GuiHelpers.applyPasswordMask(this.bwPasswordBox, showMasterPassword);
        screen.registerWidget(this.bwPasswordBox);

        screen.registerWidget(Button.builder(Component.literal(showMasterPassword ? "§a●" : "§7○"), btn -> {
            showMasterPassword = !showMasterPassword;
            btn.setMessage(Component.literal(showMasterPassword ? "§a●" : "§7○"));
            btn.setTooltip(Tooltip.create(Component.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show")));
            GuiHelpers.applyPasswordMask(this.bwPasswordBox, showMasterPassword);
        }).bounds(formX + passFieldW + 2, y + 48, 24, 20)
          .tooltip(Tooltip.create(Component.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show")))
          .build());

        List<Button> navButtons = new ArrayList<>();

        Button loginBtn = Button.builder(Component.translatable("sypass.gui.bw.login.button_login"), btn -> {
            this.savedEmail = this.bwEmailBox.getValue().trim();
            this.savedPassword = this.bwPasswordBox.getValue().trim();
            if (this.savedEmail.isEmpty() || this.savedPassword.isEmpty()) {
                screen.setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_credentials").getString());
                return;
            }
            handleLogin(null, null, navButtons);
        }).bounds(formX, y + 78, formWidth, 20).build();
        navButtons.add(loginBtn);
        screen.registerWidget(loginBtn);

        int halfW = (formWidth - 6) / 2;
        Button apiKeyBtn = Button.builder(Component.translatable("sypass.gui.bw.login.apikey_tab"), btn -> {
            this.stage = Stage.API_KEY;
            screen.rebuildView();
        }).bounds(formX, y + 104, halfW, 20).build();
        navButtons.add(apiKeyBtn);
        screen.registerWidget(apiKeyBtn);

        Button sessionKeyBtn = Button.builder(Component.translatable("sypass.gui.bw.login.session_tab"), btn -> {
            this.stage = Stage.SESSION_KEY;
            screen.rebuildView();
        }).bounds(formX + halfW + 6, y + 104, halfW, 20).build();
        navButtons.add(sessionKeyBtn);
        screen.registerWidget(sessionKeyBtn);

        Button registerBtn = Button.builder(Component.translatable("sypass.gui.bw.login.register"), btn ->
                GuiHelpers.openExternalLink(Minecraft.getInstance(), screen, "https://vault.bitwarden.com/#/register")
        ).bounds(formX, y + 130, formWidth, 20)
         .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.login.register.tooltip"))).build();
        navButtons.add(registerBtn);
        screen.registerWidget(registerBtn);
    }

    private void initOtp(int formX, int y, int formWidth, Font font) {
        int methodBtnW = (formWidth - 6) / 2;
        boolean isAuthSelected = !"1".equals(selected2faMethod);
        List<Button> navButtons = new ArrayList<>();

        Button authBtn = Button.builder(
                Component.translatable("sypass.gui.bw.otp.method_authenticator")
                        .withStyle(isAuthSelected ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                btn -> {
                    this.selected2faMethod = "0";
                    screen.rebuildView();
                }
        ).bounds(formX, y + 16, methodBtnW, 20).build();
        navButtons.add(authBtn);
        screen.registerWidget(authBtn);

        Button emailBtn = Button.builder(
                Component.translatable("sypass.gui.bw.otp.method_email")
                        .withStyle(!isAuthSelected ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                btn -> {
                    this.selected2faMethod = "1";
                    screen.rebuildView();
                }
        ).bounds(formX + methodBtnW + 6, y + 16, methodBtnW, 20).build();
        navButtons.add(emailBtn);
        screen.registerWidget(emailBtn);

        this.bwOtpBox = new EditBox(font, formX, y + 42, formWidth, 20, Component.translatable("sypass.gui.bw.otp.placeholder"));
        this.bwOtpBox.setMaxLength(32);
        this.bwOtpBox.setHint(Component.translatable("sypass.gui.bw.otp.placeholder"));
        screen.registerWidget(this.bwOtpBox);

        Button confirmOtpBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.confirm"), btn -> {
            String otp = this.bwOtpBox.getValue().trim();
            if (!otp.isEmpty()) {
                handleLogin(otp, selected2faMethod, navButtons);
            }
        }).bounds(formX, y + 68, formWidth, 20).build();
        navButtons.add(confirmOtpBtn);
        screen.registerWidget(confirmOtpBtn);

        int nextBtnY = y + 92;
        if ("1".equals(selected2faMethod)) {
            Button sendEmailBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.send_email"), btn -> {
                setButtonsActive(navButtons, false);
                screen.setStatusMessage("§e" + Component.translatable("sypass.gui.status.syncing").getString());
                BitwardenManager.getExecutor().execute(() -> {
                    BitwardenManager.sendEmail2faCode(savedEmail, savedPassword);
                    Minecraft.getInstance().execute(() -> {
                        setButtonsActive(navButtons, true);
                        screen.setStatusMessage("§a" + Component.translatable("sypass.gui.bw.otp.email_sent").getString());
                    });
                });
            }).bounds(formX, nextBtnY, formWidth, 20)
              .tooltip(Tooltip.create(Component.translatable("sypass.gui.bw.otp.send_email_tooltip")))
              .build();
            navButtons.add(sendEmailBtn);
            screen.registerWidget(sendEmailBtn);
            nextBtnY += 24;
        }

        Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
            this.stage = Stage.LOGIN;
            screen.setStatusMessage("");
            screen.rebuildView();
        }).bounds(formX, nextBtnY, formWidth, 20).build();
        navButtons.add(backBtn);
        screen.registerWidget(backBtn);
    }

    private void initApiKey(int formX, int y, int formWidth, Font font) {
        this.bwClientIdBox = new EditBox(font, formX, y + 16, formWidth, 20, Component.translatable("sypass.gui.bw.apikey.client_id"));
        this.bwClientIdBox.setMaxLength(256);
        this.bwClientIdBox.setHint(Component.translatable("sypass.gui.bw.apikey.client_id"));
        screen.registerWidget(this.bwClientIdBox);

        this.bwClientSecretBox = new EditBox(font, formX, y + 54, formWidth, 20, Component.translatable("sypass.gui.bw.apikey.client_secret"));
        this.bwClientSecretBox.setMaxLength(256);
        this.bwClientSecretBox.setHint(Component.translatable("sypass.gui.bw.apikey.client_secret"));
        GuiHelpers.applyPasswordMask(this.bwClientSecretBox, false);
        screen.registerWidget(this.bwClientSecretBox);

        this.bwMasterPasswordBox = new EditBox(font, formX, y + 92, formWidth, 20, Component.translatable("sypass.gui.bw.login.password_placeholder"));
        this.bwMasterPasswordBox.setMaxLength(256);
        this.bwMasterPasswordBox.setHint(Component.translatable("sypass.gui.bw.login.password_placeholder"));
        GuiHelpers.applyPasswordMask(this.bwMasterPasswordBox, false);
        screen.registerWidget(this.bwMasterPasswordBox);

        List<Button> navButtons = new ArrayList<>();
        Button loginBtn = Button.builder(Component.translatable("sypass.gui.bw.apikey.login_btn"), btn -> {
            String id = this.bwClientIdBox.getValue().trim();
            String secret = this.bwClientSecretBox.getValue().trim();
            String masterPass = this.bwMasterPasswordBox.getValue().trim();

            if (id.isEmpty() || secret.isEmpty() || masterPass.isEmpty()) {
                screen.setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_apikey").getString());
                return;
            }

            setButtonsActive(navButtons, false);
            screen.setStatusMessage("§e" + Component.translatable("sypass.gui.bw.login.logging_in_apikey").getString());
            BitwardenManager.getExecutor().execute(() -> {
                BitwardenManager.BwLoginResponse resp = BitwardenManager.loginWithApiKey(id, secret, masterPass);
                Minecraft.getInstance().execute(() -> {
                    setButtonsActive(navButtons, true);
                    screen.setStatusMessage(resp.message());
                    if (resp.isSuccess()) {
                        this.stage = Stage.LOGGED_IN;
                        screen.rebuildView();
                    }
                });
            });
        }).bounds(formX, y + 120, formWidth, 20).build();
        navButtons.add(loginBtn);
        screen.registerWidget(loginBtn);

        Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
            this.stage = Stage.LOGIN;
            screen.rebuildView();
        }).bounds(formX, y + 144, formWidth, 20).build();
        navButtons.add(backBtn);
        screen.registerWidget(backBtn);
    }

    private void initSessionKey(int formX, int y, int formWidth, Font font) {
        this.bwSessionKeyBox = new EditBox(font, formX, y + 40, formWidth, 20, Component.translatable("sypass.gui.bw.session.placeholder"));
        this.bwSessionKeyBox.setMaxLength(256);
        this.bwSessionKeyBox.setHint(Component.translatable("sypass.gui.bw.session.placeholder"));
        GuiHelpers.applyPasswordMask(this.bwSessionKeyBox, false);
        screen.registerWidget(this.bwSessionKeyBox);

        List<Button> navButtons = new ArrayList<>();
        Button unlockBtn = Button.builder(Component.translatable("sypass.gui.bw.session.unlock_btn"), btn -> {
            String key = this.bwSessionKeyBox.getValue().trim();
            if (key.isEmpty()) {
                screen.setStatusMessage("§c" + Component.translatable("sypass.gui.bw.error.empty_session").getString());
                return;
            }

            setButtonsActive(navButtons, false);
            screen.setStatusMessage("§e" + Component.translatable("sypass.gui.bw.session.verifying").getString());
            BitwardenManager.getExecutor().execute(() -> {
                BitwardenManager.BwLoginResponse resp = BitwardenManager.loginWithSessionKey(key);
                Minecraft.getInstance().execute(() -> {
                    setButtonsActive(navButtons, true);
                    screen.setStatusMessage(resp.message());
                    if (resp.isSuccess()) {
                        this.stage = Stage.LOGGED_IN;
                        screen.rebuildView();
                    }
                });
            });
        }).bounds(formX, y + 70, formWidth, 20).build();
        navButtons.add(unlockBtn);
        screen.registerWidget(unlockBtn);

        Button backBtn = Button.builder(Component.translatable("sypass.gui.bw.otp.back"), btn -> {
            this.stage = Stage.LOGIN;
            screen.rebuildView();
        }).bounds(formX, y + 96, formWidth, 20).build();
        navButtons.add(backBtn);
        screen.registerWidget(backBtn);
    }

    private void initLoggedIn(int formX, int y, int formWidth) {
        int startY = y + 25;

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.pull"), btn -> {
            btn.active = false;
            BitwardenManager.getExecutor().execute(() -> {
                BitwardenManager.BwSyncResult res = BitwardenManager.pullFromBitwarden();
                Minecraft.getInstance().execute(() -> {
                    btn.active = true;
                    screen.setStatusMessage(res.message());
                });
            });
        }).bounds(formX, startY, formWidth, 20).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.push"), btn -> {
            btn.active = false;
            BitwardenManager.getExecutor().execute(() -> {
                BitwardenManager.BwSyncResult res = BitwardenManager.pushToBitwarden();
                Minecraft.getInstance().execute(() -> {
                    btn.active = true;
                    screen.setStatusMessage(res.message());
                });
            });
        }).bounds(formX, startY + 24, formWidth, 20).build());

        screen.registerWidget(Button.builder(Component.translatable("sypass.gui.bw.logged.full_sync"), btn ->
                handleFullSync(btn)
        ).bounds(formX, startY + 48, formWidth, 20).build());

        screen.registerWidget(Button.builder(Component.literal("§c" + Component.translatable("sypass.gui.bw.logged.logout").getString()), btn -> {
            BitwardenManager.logout();
            clearSensitiveFields();
            this.stage = Stage.LOGIN;
            this.cachedStatusInfo = null;
            screen.setStatusMessage("§e" + Component.translatable("sypass.gui.bw.logout_success").getString());
            screen.rebuildView();
        }).bounds(formX, startY + 76, formWidth, 20).build());
    }

    private void handleLogin(String otp, String method, List<Button> buttonsToDisable) {
        setButtonsActive(buttonsToDisable, false);
        screen.setStatusMessage("§e" + Component.translatable("sypass.gui.bw.login.logging_in").getString());

        final String emailToUse = savedEmail;
        final String passToUse = savedPassword;
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwLoginResponse resp = BitwardenManager.login(emailToUse, passToUse, otp, method);
            Minecraft.getInstance().execute(() -> {
                setButtonsActive(buttonsToDisable, true);
                switch (resp.status()) {
                    case SUCCESS -> {
                        clearSensitiveFields();
                        this.stage = Stage.LOGGED_IN;
                        screen.setStatusMessage("§a" + resp.message());
                        screen.rebuildView();
                        handleFullSync(null);
                    }
                    case NEED_OTP -> {
                        this.stage = Stage.OTP;
                        if (resp.message().toLowerCase(Locale.ROOT).contains("email")) {
                            this.selected2faMethod = "1";
                        }
                        screen.setStatusMessage("§6" + resp.message());
                        screen.rebuildView();
                    }
                    case INVALID_OTP -> {
                        this.stage = Stage.OTP;
                        screen.setStatusMessage("§c" + resp.message());
                    }
                    default -> {
                        this.stage = (otp != null && !otp.isBlank()) ? Stage.OTP : Stage.LOGIN;
                        screen.setStatusMessage("§c" + resp.message());
                    }
                }
            });
        });
    }

    void render(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, int contentWidth) {
        int formWidth = Math.min(320, contentWidth);
        int formX = (screenWidth - formWidth) / 2;
        int y = 42;

        switch (stage) {
            case CHECKING_STATUS -> guiGraphics.drawCenteredString(
                    font,
                    Component.translatable("sypass.gui.bw.checking_status").withStyle(ChatFormatting.YELLOW),
                    screenWidth / 2, screenHeight / 2 - 10, 0xFFFFFF
            );
            case CLI_NOT_FOUND -> {
                guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.not_found.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), screenWidth / 2, y, 0xFF5555);
                guiGraphics.drawWordWrap(font, Component.translatable("sypass.gui.bw.not_found.desc1").withStyle(ChatFormatting.GRAY), formX, y + 14, formWidth, 0xAAAAAA);
            }
            case LOGIN -> guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.login.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, y, 0xFFFFFF);
            case OTP -> guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.otp.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, y, 0xFFFFFF);
            case API_KEY -> guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.apikey.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, y, 0xFFFFFF);
            case SESSION_KEY -> {
                guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.session.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), screenWidth / 2, y, 0xFFFFFF);
                guiGraphics.drawWordWrap(font, Component.translatable("sypass.gui.bw.session.desc2").withStyle(ChatFormatting.GREEN), formX, y + 14, formWidth, 0x55FF55);
            }
            case LOGGED_IN -> {
                String userEmail = (cachedStatusInfo != null && cachedStatusInfo.userEmail() != null && !cachedStatusInfo.userEmail().isBlank())
                        ? cachedStatusInfo.userEmail()
                        : Component.translatable("sypass.gui.tab.bitwarden").getString();
                guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.logged.connected").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), screenWidth / 2, y, 0x55FF55);
                guiGraphics.drawCenteredString(font, Component.translatable("sypass.gui.bw.logged.account", BitwardenManager.maskEmail(userEmail)).withStyle(ChatFormatting.WHITE), screenWidth / 2, y + 12, 0xFFFFFF);
            }
        }
    }

    private static void setButtonsActive(List<Button> buttons, boolean active) {
        if (buttons == null) return;
        for (Button b : buttons) {
            if (b != null) b.active = active;
        }
    }
}
