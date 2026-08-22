package com.syntren.sypass.gui;

import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class EditPasswordScreen extends Screen {


    private final Screen parent;
    private final boolean isEditing;
    private final String initialServerIp;
    private final String initialUsername;
    private final String initialPassword;
    private final String initialCommand;

    private TextFieldWidget serverIpField;
    private TextFieldWidget usernameField;
    private TextFieldWidget passwordField;
    private TextFieldWidget commandField;

    private String errorMessage = "";

    public EditPasswordScreen(Screen parent) {
        this(parent, "", "", "", "/login");
    }

    public EditPasswordScreen(Screen parent, String serverIp, String username, String password, String command) {
        super(Text.literal((serverIp != null && !serverIp.isBlank()) ? "Редагування пароля" : "Додати новий пароль"));
        this.parent = parent;
        this.isEditing = (serverIp != null && !serverIp.isBlank());
        this.initialServerIp = serverIp != null ? serverIp : "";
        this.initialUsername = username != null ? username : "";
        this.initialPassword = password != null ? password : "";
        this.initialCommand = (command != null && !command.isBlank()) ? command : "/login";
    }

    @Override
    protected void init() {
        super.init();
        this.clearChildren();

        int centerX = this.width / 2;
        int fieldWidth = 260;
        int startX = centerX - fieldWidth / 2;
        int startY = Math.max(30, this.height / 2 - 95);

        // 1. Поле IP сервера
        String defaultServer = initialServerIp;
        if (defaultServer.isEmpty() && this.client != null) {
            ServerInfo currentServer = this.client.getCurrentServerEntry();
            if (currentServer != null && currentServer.address != null) {
                defaultServer = currentServer.address;
            }
        }
        this.serverIpField = new TextFieldWidget(this.textRenderer, startX, startY + 14, fieldWidth, 20, Text.literal("IP Сервера"));
        this.serverIpField.setMaxLength(256);
        this.serverIpField.setText(defaultServer);
        this.serverIpField.setPlaceholder(Text.literal("mc.example.com або 127.0.0.1:25565"));
        this.addSelectableChild(this.serverIpField);

        // 2. Поле нікнейму
        String defaultUser = initialUsername;
        if (defaultUser.isEmpty() && this.client != null && this.client.getSession() != null) {
            defaultUser = this.client.getSession().getUsername();
        }
        this.usernameField = new TextFieldWidget(this.textRenderer, startX, startY + 52, fieldWidth, 20, Text.literal("Нікнейм"));
        this.usernameField.setMaxLength(256);
        this.usernameField.setText(defaultUser);
        this.usernameField.setPlaceholder(Text.literal("PlayerName"));
        this.addSelectableChild(this.usernameField);

        // 3. Поле пароля
        this.passwordField = new TextFieldWidget(this.textRenderer, startX, startY + 90, fieldWidth, 20, Text.literal("Пароль"));
        this.passwordField.setMaxLength(256);
        this.passwordField.setText(initialPassword);
        this.passwordField.setPlaceholder(Text.literal("Ваш пароль до сервера"));
        this.addSelectableChild(this.passwordField);

        // 4. Поле команди
        this.commandField = new TextFieldWidget(this.textRenderer, startX, startY + 128, fieldWidth, 20, Text.literal("Команда"));
        this.commandField.setMaxLength(256);
        this.commandField.setText(initialCommand);
        this.commandField.setPlaceholder(Text.literal("/login"));
        this.addSelectableChild(this.commandField);

        // Кнопки "Зберегти" та "Скасувати"
        int btnWidth = 125;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("💾 Зберегти"), b -> saveAndClose())
                .dimensions(startX, startY + 158, btnWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("❌ Скасувати"), b -> close())
                .dimensions(startX + fieldWidth - btnWidth, startY + 158, btnWidth, 20).build());

        // Встановлюємо фокус на відповідне поле
        if (serverIpField.getText().isEmpty()) {
            this.setFocused(serverIpField);
        } else if (passwordField.getText().isEmpty()) {
            this.setFocused(passwordField);
        } else {
            this.setFocused(usernameField);
        }
    }

    private void saveAndClose() {
        String server = this.serverIpField.getText().trim();
        String user = this.usernameField.getText().trim();
        String pass = this.passwordField.getText().trim();
        String cmd = this.commandField.getText().trim();

        if (server.isEmpty()) {
            this.errorMessage = "§cВкажіть IP або назву сервера!";
            return;
        }
        if (user.isEmpty()) {
            this.errorMessage = "§cВкажіть нікнейм гравця!";
            return;
        }
        if (pass.isEmpty()) {
            this.errorMessage = "§cВведіть пароль!";
            return;
        }

        // Якщо редагували та змінили сервер/нік — видаляємо старий запис
        if (isEditing && (!initialServerIp.equalsIgnoreCase(server) || !initialUsername.equalsIgnoreCase(user))) {
            PasswordManager.removePassword(initialServerIp, initialUsername);
        }

        PasswordManager.savePassword(server, user, pass, cmd.isEmpty() ? "/login" : cmd);

        if (this.parent instanceof SYPassScreen sypassScreen) {
            sypassScreen.setStatusMessage("§aЗбережено пароль для §e" + user + " §a(" + server + ")");
            sypassScreen.refreshPasswordList();
        }

        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int fieldWidth = 260;
        int startX = centerX - fieldWidth / 2;
        int startY = Math.max(30, this.height / 2 - 95);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, startY - 16, 0xFFFFFF);

        context.drawTextWithShadow(this.textRenderer, "IP / Назва сервера:", startX, startY + 3, 0xAAAAAA);
        if (serverIpField != null) serverIpField.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(this.textRenderer, "Нікнейм:", startX, startY + 41, 0xAAAAAA);
        if (usernameField != null) usernameField.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(this.textRenderer, "Пароль:", startX, startY + 79, 0xAAAAAA);
        if (passwordField != null) passwordField.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(this.textRenderer, "Команда авторизації:", startX, startY + 117, 0xAAAAAA);
        if (commandField != null) commandField.render(context, mouseX, mouseY, delta);

        if (!errorMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorMessage, centerX, startY + 185, 0xFF5555);
        }
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
