package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;

@Environment(EnvType.CLIENT)
public class EditPasswordScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final boolean isEditing;
    private final String initialServerIp;
    private final String initialUsername;
    private final String initialPassword;
    private final String initialCommand;
    private boolean syncWithBitwarden;

    private TextBoxComponent serverIpField;
    private TextBoxComponent usernameField;
    private TextBoxComponent passwordField;
    private TextBoxComponent commandField;
    private LabelComponent errorLabel;
    private boolean showPassword = true;

    public EditPasswordScreen(Screen parent) {
        this(parent, "", "", "", "/login", true);
    }

    public EditPasswordScreen(Screen parent, String serverIp, String username, String password, String command, boolean isSynced) {
        super(Text.translatable((serverIp != null && !serverIp.isBlank()) ? "sypass.gui.edit.title.edit" : "sypass.gui.edit.title.add"));
        this.parent = parent;
        this.isEditing = (serverIp != null && !serverIp.isBlank());
        this.initialServerIp = serverIp != null ? serverIp : "";
        this.initialUsername = username != null ? username : "";
        this.initialPassword = password != null ? password : "";
        this.initialCommand = (command != null && !command.isBlank()) ? command : "/login";
        this.syncWithBitwarden = isSynced;
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout rootComponent) {
        rootComponent.surface(Surface.VANILLA_TRANSLUCENT);
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER);
        rootComponent.verticalAlignment(VerticalAlignment.CENTER);

        String defaultServer = initialServerIp;
        if (defaultServer.isEmpty() && this.client != null) {
            ServerInfo currentServer = this.client.getCurrentServerEntry();
            if (currentServer != null && currentServer.address != null) {
                defaultServer = currentServer.address;
            }
        }

        String defaultUser = initialUsername;
        if (defaultUser.isEmpty() && this.client != null && this.client.getSession() != null) {
            defaultUser = this.client.getSession().getUsername();
        }

        int cardWidth = Math.min(360, this.width - 30);
        FlowLayout card = Containers.verticalFlow(Sizing.fixed(cardWidth), Sizing.content());
        card.gap(6);
        card.horizontalAlignment(HorizontalAlignment.CENTER);
        card.surface(Surface.PANEL);
        card.padding(Insets.of(12));

        card.child(Components.label(this.title).shadow(true).margins(Insets.bottom(4)));

        // 1. IP Сервера
        card.child(Components.label(Text.translatable("sypass.gui.edit.server").formatted(Formatting.GRAY))
                .horizontalSizing(Sizing.fill(100)));
        this.serverIpField = Components.textBox(Sizing.fill(100));
        this.serverIpField.setMaxLength(256);
        this.serverIpField.setText(defaultServer);
        this.serverIpField.setCursor(0, false);
        this.serverIpField.setPlaceholder(Text.literal("mc.example.com / 127.0.0.1:25565"));
        card.child(this.serverIpField);

        // 2. Нікнейм
        card.child(Components.label(Text.translatable("sypass.gui.edit.username").formatted(Formatting.GRAY))
                .horizontalSizing(Sizing.fill(100)));
        this.usernameField = Components.textBox(Sizing.fill(100));
        this.usernameField.setMaxLength(256);
        this.usernameField.setText(defaultUser);
        this.usernameField.setCursor(0, false);
        this.usernameField.setPlaceholder(Text.literal("PlayerName"));
        card.child(this.usernameField);

        // 3. Пароль
        card.child(Components.label(Text.translatable("sypass.gui.edit.password").formatted(Formatting.GRAY))
                .horizontalSizing(Sizing.fill(100)));

        FlowLayout passRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
        passRow.gap(4);

        this.passwordField = Components.textBox(Sizing.fixed(cardWidth - 84));
        this.passwordField.setMaxLength(256);
        this.passwordField.setText(initialPassword);
        this.passwordField.setCursor(0, false);
        this.passwordField.setPlaceholder(Text.translatable("sypass.gui.edit.password"));
        applyPasswordMask(this.passwordField, showPassword);

        ButtonComponent togglePassBtn = Components.button(Text.literal(showPassword ? "§a●" : "§7○"), b -> {
            showPassword = !showPassword;
            b.setMessage(Text.literal(showPassword ? "§a●" : "§7○"));
            b.tooltip(Text.translatable(showPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));
            applyPasswordMask(this.passwordField, showPassword);
        });
        togglePassBtn.horizontalSizing(Sizing.fixed(25));
        togglePassBtn.tooltip(Text.translatable(showPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));

        ButtonComponent generateBtn = Components.button(Text.literal("🎲"), b -> {
            String gen = com.syntren.sypass.util.PasswordGenerator.generateDefault();
            this.passwordField.setText(gen);
            if (this.client != null && this.client.keyboard != null) {
                this.client.keyboard.setClipboard(gen);
            }
            this.errorLabel.color(Color.ofRgb(0x55FF55));
            this.errorLabel.text(Text.translatable("sypass.gui.edit.generated_copied"));
        });
        generateBtn.horizontalSizing(Sizing.fixed(25));
        generateBtn.tooltip(Text.translatable("sypass.gui.button.generate.tooltip"));

        passRow.child(this.passwordField);
        passRow.child(togglePassBtn);
        passRow.child(generateBtn);
        card.child(passRow);

        // 4. Команда
        card.child(Components.label(Text.translatable("sypass.gui.edit.command").formatted(Formatting.GRAY))
                .horizontalSizing(Sizing.fill(100)));
        this.commandField = Components.textBox(Sizing.fill(100));
        this.commandField.setMaxLength(256);
        this.commandField.setText(initialCommand);
        this.commandField.setCursor(0, false);
        this.commandField.setPlaceholder(Text.literal("/login"));
        card.child(this.commandField);

        // Перемикач синхронізації з Bitwarden
        ButtonComponent syncToggleBtn = Components.button(
                Text.literal(syncWithBitwarden ? "§a☁ " : "§7☁ ").append(Text.translatable("sypass.gui.sync.toggle")),
                b -> {
                    syncWithBitwarden = !syncWithBitwarden;
                    b.setMessage(Text.literal(syncWithBitwarden ? "§a☁ " : "§7☁ ").append(Text.translatable("sypass.gui.sync.toggle")));
                }
        );
        syncToggleBtn.horizontalSizing(Sizing.fill(100));
        card.child(syncToggleBtn);

        // Повідомлення про помилку
        this.errorLabel = Components.label(Text.empty());
        this.errorLabel.color(Color.ofRgb(0xFF5555));
        this.errorLabel.shadow(true);
        this.errorLabel.margins(Insets.vertical(2));
        card.child(this.errorLabel);

        // Кнопки "Зберегти" та "Скасувати"
        FlowLayout buttonRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
        buttonRow.gap(8);
        buttonRow.horizontalAlignment(HorizontalAlignment.CENTER);

        int btnWidth = (cardWidth - 32) / 2;

        ButtonComponent saveBtn = Components.button(Text.translatable("sypass.gui.button.save"), b -> saveAndClose());
        saveBtn.horizontalSizing(Sizing.fixed(btnWidth));

        ButtonComponent cancelBtn = Components.button(Text.translatable("sypass.gui.button.cancel"), b -> close());
        cancelBtn.horizontalSizing(Sizing.fixed(btnWidth));

        buttonRow.child(saveBtn);
        buttonRow.child(cancelBtn);
        card.child(buttonRow);

        rootComponent.child(card);

        if (this.serverIpField.getText().isEmpty()) {
            this.serverIpField.setFocused(true);
        } else if (this.passwordField.getText().isEmpty()) {
            this.passwordField.setFocused(true);
        } else {
            this.usernameField.setFocused(true);
        }
    }

    private void applyPasswordMask(TextBoxComponent field, boolean show) {
        if (show) {
            field.setRenderTextProvider((text, index) -> OrderedText.styledForwardsVisitedString(text, Style.EMPTY));
        } else {
            field.setRenderTextProvider((text, index) -> OrderedText.styledForwardsVisitedString("•".repeat(text.length()), Style.EMPTY));
        }
    }

    private void saveAndClose() {
        String server = this.serverIpField.getText().trim();
        String user = this.usernameField.getText().trim();
        String pass = this.passwordField.getText().trim();
        String cmd = this.commandField.getText().trim();

        if (server.isEmpty()) {
            this.errorLabel.text(Text.translatable("sypass.gui.edit.error.server"));
            return;
        }
        if (user.isEmpty()) {
            this.errorLabel.text(Text.translatable("sypass.gui.edit.error.user"));
            return;
        }
        if (pass.isEmpty()) {
            this.errorLabel.text(Text.translatable("sypass.gui.edit.error.pass"));
            return;
        }

        PasswordManager.AccountData currentAcc = isEditing ? PasswordManager.getPassword(initialServerIp, initialUsername) : null;
        String currentRemoteId = (currentAcc != null) ? currentAcc.remoteId() : "";

        if (isEditing && (!initialServerIp.equalsIgnoreCase(server) || !initialUsername.equalsIgnoreCase(user))) {
            PasswordManager.removePassword(initialServerIp, initialUsername);
            if (SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
                BitwardenManager.deleteSingleItemAsync(initialServerIp, initialUsername, currentRemoteId);
            }
            currentRemoteId = "";
        } else if (isEditing && !syncWithBitwarden && currentAcc != null && currentAcc.isSynced() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.deleteFromBitwardenOnlyAsync(initialServerIp, initialUsername, currentRemoteId);
            currentRemoteId = "";
        }

        PasswordManager.savePassword(server, user, pass, cmd.isEmpty() ? "/login" : cmd, syncWithBitwarden, currentRemoteId);

        if (syncWithBitwarden && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.pushSingleItemAsync(server, user, pass, cmd.isEmpty() ? "/login" : cmd);
        }

        if (this.parent instanceof SYPassScreen sypassScreen) {
            sypassScreen.setStatusMessage(Text.translatable("sypass.gui.edit.saved", user, server).getString());
            sypassScreen.refreshPasswordList();
        }

        this.close();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}