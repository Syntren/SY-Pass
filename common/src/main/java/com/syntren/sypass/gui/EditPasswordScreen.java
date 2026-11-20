package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.AccountData;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.PasswordGenerator;
import com.syntren.sypass.util.ServerAddressUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public class EditPasswordScreen extends Screen {

    private static final int CARD_WIDTH = 310;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private final boolean isEditing;
    private final String initialServerIp;
    private final String initialUsername;
    private final String initialPassword;
    private final String initialCommand;
    private boolean syncWithBitwarden;

    private EditBox serverIpEditBox;
    private EditBox usernameEditBox;
    private EditBox passwordEditBox;
    private EditBox commandEditBox;
    private StringWidget errorWidget;

    private boolean showPassword = true;
    private int generatedPasswordLength;
    private Button lengthSelectorButton;

    public EditPasswordScreen(Screen parent) {
        this(parent, "", "", "", "/login",
                SYPassConfig.isBitwardenEnabled() && BitwardenManager.hasActiveSession() && SYPassConfig.isAutoSyncEnabled());
    }

    public EditPasswordScreen(Screen parent, String serverIp, String username, String password, String command, boolean isSynced) {
        super(Component.translatable((serverIp != null && !serverIp.isBlank()) ? "sypass.gui.edit.title.edit" : "sypass.gui.edit.title.add"));
        this.parent = parent;
        this.isEditing = (serverIp != null && !serverIp.isBlank());
        this.initialServerIp = serverIp != null ? serverIp : "";
        this.initialUsername = username != null ? username : "";
        this.initialPassword = password != null ? password : "";
        this.initialCommand = (command != null && !command.isBlank()) ? command : "/login";
        this.syncWithBitwarden = isSynced;
        this.generatedPasswordLength = SYPassConfig.getDefaultPasswordLength();
    }

    @Override
    protected void init() {
        super.init();

        String defaultServer = initialServerIp;
        if (defaultServer.isEmpty() && this.minecraft != null) {
            ServerData current = this.minecraft.getCurrentServer();
            if (current != null && current.ip != null) {
                defaultServer = current.ip;
            }
        }

        String defaultUser = initialUsername;
        if (defaultUser.isEmpty() && this.minecraft != null && this.minecraft.getUser() != null) {
            defaultUser = this.minecraft.getUser().getName();
        }

        LinearLayout rootLayout = LinearLayout.vertical().spacing(5);
        rootLayout.defaultCellSetting().alignHorizontallyCenter();

        rootLayout.addChild(new StringWidget(this.title, this.font));

        // 1. Server address
        rootLayout.addChild(new StringWidget(CARD_WIDTH, 9, Component.translatable("sypass.gui.edit.server").withStyle(s -> s.withColor(0xAAAAAA)), this.font).alignLeft());
        this.serverIpEditBox = new EditBox(this.font, 0, 0, CARD_WIDTH, FIELD_HEIGHT, Component.translatable("sypass.gui.edit.server"));
        this.serverIpEditBox.setMaxLength(256);
        this.serverIpEditBox.setValue(defaultServer);
        this.serverIpEditBox.setHint(Component.literal("mc.example.com / 127.0.0.1:25565"));
        if (SYPassConfig.isStreamerModeEnabled()) {
            this.serverIpEditBox.setFormatter((string, pos) -> FormattedCharSequence.forward(ServerAddressUtil.mask(string), Style.EMPTY));
        }
        rootLayout.addChild(this.serverIpEditBox);

        // 2. Username
        rootLayout.addChild(new StringWidget(CARD_WIDTH, 9, Component.translatable("sypass.gui.edit.username").withStyle(s -> s.withColor(0xAAAAAA)), this.font).alignLeft());
        this.usernameEditBox = new EditBox(this.font, 0, 0, CARD_WIDTH, FIELD_HEIGHT, Component.translatable("sypass.gui.edit.username"));
        this.usernameEditBox.setMaxLength(256);
        this.usernameEditBox.setValue(defaultUser);
        this.usernameEditBox.setHint(Component.literal("PlayerName"));
        rootLayout.addChild(this.usernameEditBox);

        // 3. Password & generator controls
        rootLayout.addChild(new StringWidget(CARD_WIDTH, 9, Component.translatable("sypass.gui.edit.password").withStyle(s -> s.withColor(0xAAAAAA)), this.font).alignLeft());

        LinearLayout passwordRow = LinearLayout.horizontal().spacing(3);
        int toolsWidth = 100;
        int passInputWidth = CARD_WIDTH - toolsWidth - 9;

        if (SYPassConfig.isStreamerModeEnabled()) {
            this.showPassword = false;
        }

        this.passwordEditBox = new EditBox(this.font, 0, 0, passInputWidth, FIELD_HEIGHT, Component.translatable("sypass.gui.edit.password"));
        this.passwordEditBox.setMaxLength(256);
        this.passwordEditBox.setValue(initialPassword);
        GuiHelpers.applyPasswordMask(this.passwordEditBox, showPassword);
        passwordRow.addChild(this.passwordEditBox);

        Button toggleShowPasswordButton = Button.builder(Component.literal(showPassword ? "§a●" : "§7○"), btn -> {
            if (SYPassConfig.isStreamerModeEnabled()) return;
            showPassword = !showPassword;
            btn.setMessage(Component.literal(showPassword ? "§a●" : "§7○"));
            btn.setTooltip(Tooltip.create(Component.translatable(showPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show")));
            GuiHelpers.applyPasswordMask(this.passwordEditBox, showPassword);
        }).bounds(0, 0, 22, FIELD_HEIGHT)
          .tooltip(Tooltip.create(Component.translatable(SYPassConfig.isStreamerModeEnabled()
                  ? "sypass.gui.settings.streamer_mode.tooltip"
                  : (showPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"))))
          .build();
        if (SYPassConfig.isStreamerModeEnabled()) {
            toggleShowPasswordButton.active = false;
        }
        passwordRow.addChild(toggleShowPasswordButton);

        Button generateButton = Button.builder(Component.literal("🎲"), btn -> {
            String generated = PasswordGenerator.generate(this.generatedPasswordLength);
            this.passwordEditBox.setValue(generated);
            PlatformHelper.get().copyToClipboard(generated);
            this.errorWidget.setMessage(Component.translatable("sypass.gui.edit.generated_copied").withStyle(s -> s.withColor(0x55FF55)));
        }).bounds(0, 0, 22, FIELD_HEIGHT)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.button.generate.tooltip")))
          .build();
        passwordRow.addChild(generateButton);

        passwordRow.addChild(Button.builder(Component.literal("-"), btn -> {
            this.generatedPasswordLength = Math.max(6, this.generatedPasswordLength - 1);
            SYPassConfig.setDefaultPasswordLength(this.generatedPasswordLength);
            updateLengthButton();
        }).bounds(0, 0, 16, FIELD_HEIGHT)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.edit.length.minus.tooltip")))
          .build());

        this.lengthSelectorButton = Button.builder(Component.literal(String.valueOf(this.generatedPasswordLength)), btn -> {
            this.generatedPasswordLength = switch (this.generatedPasswordLength) {
                case 8 -> 12;
                case 12 -> 16;
                case 16 -> 20;
                case 20 -> 24;
                case 24 -> 32;
                default -> 16;
            };
            SYPassConfig.setDefaultPasswordLength(this.generatedPasswordLength);
            updateLengthButton();
        }).bounds(0, 0, 22, FIELD_HEIGHT)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.edit.length.tooltip", this.generatedPasswordLength)))
          .build();
        passwordRow.addChild(this.lengthSelectorButton);

        passwordRow.addChild(Button.builder(Component.literal("+"), btn -> {
            this.generatedPasswordLength = Math.min(64, this.generatedPasswordLength + 1);
            SYPassConfig.setDefaultPasswordLength(this.generatedPasswordLength);
            updateLengthButton();
        }).bounds(0, 0, 16, FIELD_HEIGHT)
          .tooltip(Tooltip.create(Component.translatable("sypass.gui.edit.length.plus.tooltip")))
          .build());

        rootLayout.addChild(passwordRow);

        // 4. Login command
        rootLayout.addChild(new StringWidget(CARD_WIDTH, 9, Component.translatable("sypass.gui.edit.command").withStyle(s -> s.withColor(0xAAAAAA)), this.font).alignLeft());
        this.commandEditBox = new EditBox(this.font, 0, 0, CARD_WIDTH, FIELD_HEIGHT, Component.translatable("sypass.gui.edit.command"));
        this.commandEditBox.setMaxLength(256);
        this.commandEditBox.setValue(initialCommand);
        this.commandEditBox.setHint(Component.literal("/login"));
        rootLayout.addChild(this.commandEditBox);

        // 5. Optional Bitwarden sync checkbox
        if (SYPassConfig.isBitwardenEnabled()) {
            Checkbox syncCheckbox = Checkbox.builder(Component.translatable("sypass.gui.sync.toggle"), this.font)
                    .selected(this.syncWithBitwarden)
                    .onValueChange((checkbox, selected) -> this.syncWithBitwarden = selected)
                    .build();
            rootLayout.addChild(syncCheckbox);
        }

        // Status / validation message
        this.errorWidget = new StringWidget(CARD_WIDTH, 9, Component.empty(), this.font);
        rootLayout.addChild(this.errorWidget);

        // 6. Save / Cancel buttons
        LinearLayout buttonRow = LinearLayout.horizontal().spacing(8);
        int actionBtnWidth = (CARD_WIDTH - 8) / 2;

        buttonRow.addChild(Button.builder(Component.translatable("sypass.gui.button.save"), btn -> saveAndClose())
                .bounds(0, 0, actionBtnWidth, FIELD_HEIGHT).build());
        buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, btn -> onClose())
                .bounds(0, 0, actionBtnWidth, FIELD_HEIGHT).build());

        rootLayout.addChild(buttonRow);

        rootLayout.arrangeElements();
        FrameLayout.centerInRectangle(rootLayout, 0, 0, this.width, this.height);
        rootLayout.visitWidgets(this::addRenderableWidget);

        if (this.serverIpEditBox.getValue().isEmpty()) {
            setInitialFocus(this.serverIpEditBox);
        } else if (this.passwordEditBox.getValue().isEmpty()) {
            setInitialFocus(this.passwordEditBox);
        } else {
            setInitialFocus(this.usernameEditBox);
        }
    }

    private void updateLengthButton() {
        this.lengthSelectorButton.setMessage(Component.literal(String.valueOf(this.generatedPasswordLength)));
        this.lengthSelectorButton.setTooltip(Tooltip.create(Component.translatable("sypass.gui.edit.length.tooltip", this.generatedPasswordLength)));
    }

    private void saveAndClose() {
        String server = this.serverIpEditBox.getValue().trim();
        String user = this.usernameEditBox.getValue().trim();
        String pass = this.passwordEditBox.getValue().trim();
        String cmd = this.commandEditBox.getValue().trim();

        if (server.isEmpty()) {
            this.errorWidget.setMessage(Component.translatable("sypass.gui.edit.error.server").withStyle(s -> s.withColor(0xFF5555)));
            return;
        }
        if (user.isEmpty()) {
            this.errorWidget.setMessage(Component.translatable("sypass.gui.edit.error.user").withStyle(s -> s.withColor(0xFF5555)));
            return;
        }
        if (pass.isEmpty()) {
            this.errorWidget.setMessage(Component.translatable("sypass.gui.edit.error.pass").withStyle(s -> s.withColor(0xFF5555)));
            return;
        }

        AccountData currentAcc = isEditing ? PasswordManager.getPassword(initialServerIp, initialUsername) : null;
        String currentRemoteId = (currentAcc != null) ? currentAcc.remoteId() : "";
        boolean syncEnabled = SYPassConfig.isBitwardenEnabled() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession();

        if (isEditing && (!initialServerIp.equalsIgnoreCase(server) || !initialUsername.equalsIgnoreCase(user))) {
            PasswordManager.removePassword(initialServerIp, initialUsername);
            if (syncEnabled) {
                BitwardenManager.deleteSingleItemAsync(initialServerIp, initialUsername, currentRemoteId);
            }
            currentRemoteId = "";
        } else if (isEditing && !syncWithBitwarden && currentAcc != null && currentAcc.isSynced() && syncEnabled) {
            BitwardenManager.deleteFromBitwardenOnlyAsync(initialServerIp, initialUsername, currentRemoteId);
            currentRemoteId = "";
        }

        String finalCmd = cmd.isEmpty() ? "/login" : cmd;
        PasswordManager.savePassword(server, user, pass, finalCmd, syncWithBitwarden, currentRemoteId);

        if (syncWithBitwarden && syncEnabled) {
            BitwardenManager.pushSingleItemAsync(server, user, pass, finalCmd);
        }

        if (this.parent instanceof SYPassScreen sypassScreen) {
            String dispIp = SYPassConfig.isStreamerModeEnabled() ? ServerAddressUtil.mask(server) : server;
            sypassScreen.setStatusMessage(Component.translatable("sypass.gui.edit.saved", user, dispIp).getString());
            sypassScreen.refreshPasswordList();
        }

        this.onClose();
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int panelW = CARD_WIDTH + 24;
        int panelH = SYPassConfig.isBitwardenEnabled() ? 245 : 225;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, GuiHelpers.PANEL_BG);
        guiGraphics.renderOutline(panelX, panelY, panelW, panelH, GuiHelpers.PANEL_BORDER);
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
}
