package com.syntren.sypass.gui;

import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class SYPassScreen extends Screen {

    private final Screen parent;

    public enum Tab {
        LOCAL_PASSWORDS,
        BITWARDEN
    }

    public enum BwStage {
        LOGIN,
        OTP,
        API_KEY,
        SERVER_CONFIG,
        LOGGED_IN
    }

    private Tab activeTab = Tab.LOCAL_PASSWORDS;
    private BwStage bwStage = BwStage.LOGIN;

    // 2FA метод: "1" = Email (за замовчуванням), "0" = Authenticator App (TOTP)
    private String selected2faMethod = "1";

    // Віджети локальної вкладки
    private TextFieldWidget searchField;
    private PasswordListWidget passwordListWidget;

    // Віджети Bitwarden вкладки
    private TextFieldWidget bwEmailField;
    private TextFieldWidget bwPasswordField;
    private TextFieldWidget bwOtpField;
    private TextFieldWidget bwClientIdField;
    private TextFieldWidget bwClientSecretField;
    private TextFieldWidget bwServerUrlField;

    private String statusMessage = "";
    private boolean isProcessing = false;
    private String savedEmail = "";
    private String savedPassword = "";
    private BitwardenManager.BwStatusInfo cachedStatusInfo = null;

    public SYPassScreen() {
        this(null);
    }

    public SYPassScreen(Screen parent) {
        super(Text.literal("SY-Pass Manager"));
        this.parent = parent;
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
    }

    public void refreshPasswordList() {
        if (passwordListWidget != null) {
            String query = searchField != null ? searchField.getText() : "";
            passwordListWidget.refresh(query);
        }
    }

    @Override
    protected void init() {
        super.init();
        this.clearChildren();

        int centerX = this.width / 2;

        // Вкладки у верхній частині
        int count = PasswordManager.getTotalCount();
        Text localTabText = Text.literal("🔑 Локальні паролі (" + count + ")")
                .formatted(activeTab == Tab.LOCAL_PASSWORDS ? Formatting.YELLOW : Formatting.GRAY);
        Text bwTabText = Text.literal("☁ Bitwarden")
                .formatted(activeTab == Tab.BITWARDEN ? Formatting.YELLOW : Formatting.GRAY);

        this.addDrawableChild(ButtonWidget.builder(localTabText, b -> {
            activeTab = Tab.LOCAL_PASSWORDS;
            clearAndInit();
        }).dimensions(centerX - 170, 8, 165, 20).build());

        this.addDrawableChild(ButtonWidget.builder(bwTabText, b -> {
            activeTab = Tab.BITWARDEN;
            updateBitwardenStatusAsync();
            clearAndInit();
        }).dimensions(centerX + 5, 8, 165, 20).build());

        if (activeTab == Tab.LOCAL_PASSWORDS) {
            initLocalTab(centerX);
        } else {
            initBitwardenTab(centerX);
        }
    }

    private void initLocalTab(int centerX) {
        // Поле пошуку
        int listWidth = Math.min(420, this.width - 30);
        int listX = (this.width - listWidth) / 2;

        this.searchField = new TextFieldWidget(this.textRenderer, listX, 34, listWidth, 20, Text.literal("Пошук"));
        this.searchField.setMaxLength(256);
        this.searchField.setPlaceholder(Text.literal("🔍 Пошук за сервером або ніком..."));
        this.searchField.setChangedListener(text -> {
            if (passwordListWidget != null) {
                passwordListWidget.refresh(text);
            }
        });
        this.addSelectableChild(this.searchField);

        // Список паролів
        int listTop = 58;
        int listHeight = this.height - listTop - 34;
        this.passwordListWidget = new PasswordListWidget(this.client, this.width, listHeight, listTop, 46, this);
        this.passwordListWidget.refresh(this.searchField.getText());
        this.addSelectableChild(this.passwordListWidget);

        // Нижня панель кнопок
        int bottomY = this.height - 26;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("➕ Додати пароль"), b -> {
            if (this.client != null) {
                this.client.setScreen(new EditPasswordScreen(this));
            }
        }).dimensions(centerX - 190, bottomY, 120, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("🔄 Оновити"), b -> {
            refreshPasswordList();
            statusMessage = "§aСписок оновлено!";
        }).dimensions(centerX - 65, bottomY, 80, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Закрити"), b -> this.close())
                .dimensions(centerX + 20, bottomY, 170, 20).build());
    }

    private void initBitwardenTab(int centerX) {
        int centerY = this.height / 2;

        if (BitwardenManager.hasActiveSession() && bwStage == BwStage.LOGIN) {
            bwStage = BwStage.LOGGED_IN;
        }

        // Нижня кнопка закриття для вкладки Bitwarden
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Закрити"), b -> this.close())
                .dimensions(centerX - 100, this.height - 26, 200, 20).build());

        if (isProcessing) return;

        switch (bwStage) {
            case LOGIN -> {
                // Перемикач режимів входу
                this.addDrawableChild(ButtonWidget.builder(Text.literal("🔑 Пароль"), b -> {
                    bwStage = BwStage.LOGIN;
                    clearAndInit();
                }).dimensions(centerX - 155, 34, 100, 18).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("⚙ API Key"), b -> {
                    bwStage = BwStage.API_KEY;
                    clearAndInit();
                }).dimensions(centerX - 50, 34, 100, 18).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("🌐 Сервер"), b -> {
                    bwStage = BwStage.SERVER_CONFIG;
                    clearAndInit();
                }).dimensions(centerX + 55, 34, 100, 18).build());

                // Форма входу
                int fieldW = 240;
                int startX = centerX - fieldW / 2;

                this.bwEmailField = new TextFieldWidget(this.textRenderer, startX, centerY - 48, fieldW, 20, Text.literal("Email"));
                this.bwEmailField.setMaxLength(256);
                this.bwEmailField.setPlaceholder(Text.literal("Email від Bitwarden..."));
                if (!savedEmail.isEmpty()) this.bwEmailField.setText(savedEmail);
                this.addSelectableChild(this.bwEmailField);

                this.bwPasswordField = new TextFieldWidget(this.textRenderer, startX, centerY - 20, fieldW, 20, Text.literal("Password"));
                this.bwPasswordField.setMaxLength(256);
                this.bwPasswordField.setPlaceholder(Text.literal("Майстер-пароль..."));
                if (!savedPassword.isEmpty()) this.bwPasswordField.setText(savedPassword);
                this.addSelectableChild(this.bwPasswordField);

                // Перемикач типу 2FA
                String methodLabel = selected2faMethod.equals("1") ? "2FA: 📧 Email" : "2FA: 📱 Authenticator";
                this.addDrawableChild(ButtonWidget.builder(Text.literal(methodLabel), b -> {
                    selected2faMethod = selected2faMethod.equals("1") ? "0" : "1";
                    clearAndInit();
                }).dimensions(startX, centerY + 8, fieldW, 18).tooltip(Tooltip.of(Text.literal("Оберіть метод двофакторної автентифікації вашого акаунта"))).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("🔑 Увійти"), b -> handleLogin(null))
                        .dimensions(startX, centerY + 30, fieldW, 20).build());
            }
            case OTP -> {
                int fieldW = 220;
                int startX = centerX - fieldW / 2;

                this.bwOtpField = new TextFieldWidget(this.textRenderer, startX, centerY - 32, fieldW, 20, Text.literal("OTP Code"));
                this.bwOtpField.setMaxLength(32);
                this.bwOtpField.setPlaceholder(Text.literal("Введіть код підтвердження..."));
                this.addSelectableChild(this.bwOtpField);
                this.setFocused(this.bwOtpField);
                this.bwOtpField.setFocused(true);

                // Перемикач типу 2FA
                String methodText = selected2faMethod.equals("1") ? "Метод: 📧 Email" : "Метод: 📱 Authenticator";
                this.addDrawableChild(ButtonWidget.builder(Text.literal(methodText), b -> {
                    selected2faMethod = selected2faMethod.equals("1") ? "0" : "1";
                    clearAndInit();
                }).dimensions(startX, centerY - 6, fieldW, 18).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("✔ Підтвердити 2FA"), b -> {
                    String otp = this.bwOtpField.getText().trim();
                    if (!otp.isEmpty()) handleLogin(otp);
                }).dimensions(startX, centerY + 18, fieldW, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("← Назад"), b -> {
                    bwStage = BwStage.LOGIN;
                    statusMessage = "";
                    clearAndInit();
                }).dimensions(startX, centerY + 42, fieldW, 18).build());
            }
            case API_KEY -> {
                int fieldW = 240;
                int startX = centerX - fieldW / 2;

                this.addDrawableChild(ButtonWidget.builder(Text.literal("← Назад до входу за паролем"), b -> {
                    bwStage = BwStage.LOGIN;
                    clearAndInit();
                }).dimensions(centerX - 100, 34, 200, 18).build());

                this.bwClientIdField = new TextFieldWidget(this.textRenderer, startX, centerY - 55, fieldW, 20, Text.literal("Client ID"));
                this.bwClientIdField.setMaxLength(256);
                this.bwClientIdField.setPlaceholder(Text.literal("user.xxxxxxxx-xxxx-xxxx..."));
                this.addSelectableChild(this.bwClientIdField);

                this.bwClientSecretField = new TextFieldWidget(this.textRenderer, startX, centerY - 30, fieldW, 20, Text.literal("Client Secret"));
                this.bwClientSecretField.setMaxLength(256);
                this.bwClientSecretField.setPlaceholder(Text.literal("Client Secret..."));
                this.addSelectableChild(this.bwClientSecretField);

                this.bwPasswordField = new TextFieldWidget(this.textRenderer, startX, centerY - 5, fieldW, 20, Text.literal("Master Password"));
                this.bwPasswordField.setMaxLength(256);
                this.bwPasswordField.setPlaceholder(Text.literal("Майстер-пароль для розблокування..."));
                this.addSelectableChild(this.bwPasswordField);

                this.addDrawableChild(ButtonWidget.builder(Text.literal("🔑 Увійти за API Key"), b -> handleApiKeyLogin())
                        .dimensions(startX, centerY + 22, fieldW, 20).build());
            }
            case SERVER_CONFIG -> {
                int fieldW = 260;
                int startX = centerX - fieldW / 2;

                this.addDrawableChild(ButtonWidget.builder(Text.literal("← Назад до входу"), b -> {
                    bwStage = BwStage.LOGIN;
                    clearAndInit();
                }).dimensions(centerX - 100, 34, 200, 18).build());

                this.bwServerUrlField = new TextFieldWidget(this.textRenderer, startX, centerY - 20, fieldW, 20, Text.literal("Server URL"));
                this.bwServerUrlField.setMaxLength(256);
                this.bwServerUrlField.setText("https://vault.bitwarden.com");
                this.bwServerUrlField.setPlaceholder(Text.literal("https://vault.bitwarden.com або ваш сервер"));
                this.addSelectableChild(this.bwServerUrlField);

                this.addDrawableChild(ButtonWidget.builder(Text.literal("💾 Зберегти URL сервера"), b -> {
                    String url = bwServerUrlField.getText().trim();
                    if (!url.isEmpty()) {
                        boolean ok = BitwardenManager.setServerUrl(url);
                        statusMessage = ok ? "§aСервер збережено: " + url : "§cПомилка налаштування сервера";
                        bwStage = BwStage.LOGIN;
                        clearAndInit();
                    }
                }).dimensions(startX, centerY + 10, fieldW, 20).build());
            }
            case LOGGED_IN -> {
                this.addDrawableChild(ButtonWidget.builder(Text.literal("📥 Отримати паролі (Pull)"), b -> handlePull())
                        .dimensions(centerX - 100, centerY - 35, 200, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("📤 Вивантажити паролі (Push)"), b -> handlePush())
                        .dimensions(centerX - 100, centerY - 10, 200, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("🔄 Повна синхронізація (Pull + Push)"), b -> handleFullSync())
                        .dimensions(centerX - 100, centerY + 15, 200, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("🚪 Вийти з Bitwarden"), b -> {
                    BitwardenManager.logout();
                    bwStage = BwStage.LOGIN;
                    statusMessage = "§eВийшли з акаунту Bitwarden.";
                    updateBitwardenStatusAsync();
                    clearAndInit();
                }).dimensions(centerX - 100, centerY + 45, 200, 20).build());
            }
        }
    }

    private void updateBitwardenStatusAsync() {
        new Thread(() -> {
            BitwardenManager.BwStatusInfo info = BitwardenManager.getStatusInfo();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.cachedStatusInfo = info;
                    if (info.isUnlocked() || BitwardenManager.hasActiveSession()) {
                        this.bwStage = BwStage.LOGGED_IN;
                    }
                });
            }
        }).start();
    }

    private void handleLogin(String otp) {
        if (otp == null) {
            this.savedEmail = this.bwEmailField.getText().trim();
            this.savedPassword = this.bwPasswordField.getText().trim();
        }

        if (savedEmail.isEmpty() || savedPassword.isEmpty()) {
            this.statusMessage = "§cВведіть Email та майстер-пароль!";
            return;
        }

        this.isProcessing = true;
        this.statusMessage = "§eАвторизація в Bitwarden...";
        this.clearAndInit();

        new Thread(() -> {
            BitwardenManager.BwLoginResponse response = BitwardenManager.login(savedEmail, savedPassword, otp, selected2faMethod);
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    switch (response.status()) {
                        case SUCCESS -> {
                            this.bwStage = BwStage.LOGGED_IN;
                            this.statusMessage = "§a" + response.message();
                            handlePull();
                        }
                        case NEED_OTP -> {
                            this.bwStage = BwStage.OTP;
                            this.statusMessage = "§6" + response.message();
                        }
                        case INVALID_OTP -> {
                            this.bwStage = BwStage.OTP;
                            this.statusMessage = "§c" + response.message();
                        }
                        case INVALID_PASSWORD -> {
                            this.bwStage = BwStage.LOGIN;
                            this.statusMessage = "§c" + response.message();
                        }
                        case CLI_NOT_FOUND -> {
                            this.bwStage = BwStage.LOGIN;
                            this.statusMessage = "§c" + response.message();
                        }
                        default -> {
                            this.bwStage = BwStage.LOGIN;
                            this.statusMessage = "§c" + response.message();
                        }
                    }
                    this.clearAndInit();
                });
            }
        }).start();
    }

    private void handleApiKeyLogin() {
        String clientId = this.bwClientIdField.getText().trim();
        String clientSecret = this.bwClientSecretField.getText().trim();
        String masterPass = this.bwPasswordField.getText().trim();

        if (clientId.isEmpty() || clientSecret.isEmpty() || masterPass.isEmpty()) {
            this.statusMessage = "§cЗаповніть усі поля API ключа та пароль!";
            return;
        }

        this.isProcessing = true;
        this.statusMessage = "§eАвторизація за API ключем...";
        this.clearAndInit();

        new Thread(() -> {
            BitwardenManager.BwLoginResponse response = BitwardenManager.loginWithApiKey(clientId, clientSecret, masterPass);
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    if (response.isSuccess()) {
                        this.bwStage = BwStage.LOGGED_IN;
                        this.statusMessage = "§a" + response.message();
                        handlePull();
                    } else {
                        this.statusMessage = "§c" + response.message();
                    }
                    this.clearAndInit();
                });
            }
        }).start();
    }

    private void handlePull() {
        this.isProcessing = true;
        this.statusMessage = "§eСинхронізація (Pull) з Bitwarden...";
        this.clearAndInit();

        new Thread(() -> {
            BitwardenManager.BwSyncResult result = BitwardenManager.pullFromBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = result.success() ? "§a" + result.message() : "§c" + result.message();
                    refreshPasswordList();
                    this.clearAndInit();
                });
            }
        }).start();
    }

    private void handlePush() {
        this.isProcessing = true;
        this.statusMessage = "§eВивантаження (Push) до Bitwarden...";
        this.clearAndInit();

        new Thread(() -> {
            BitwardenManager.BwSyncResult result = BitwardenManager.pushToBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = result.success() ? "§a" + result.message() : "§c" + result.message();
                    this.clearAndInit();
                });
            }
        }).start();
    }

    private void handleFullSync() {
        this.isProcessing = true;
        this.statusMessage = "§eПовна синхронізація...";
        this.clearAndInit();

        new Thread(() -> {
            BitwardenManager.BwSyncResult pullRes = BitwardenManager.pullFromBitwarden();
            BitwardenManager.BwSyncResult pushRes = BitwardenManager.pushToBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = "§aСинхронізовано! (Pull: " + pullRes.count() + ", Push: " + pushRes.count() + ")";
                    refreshPasswordList();
                    this.clearAndInit();
                });
            }
        }).start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        // Відображення списку паролів у локальній вкладці
        if (activeTab == Tab.LOCAL_PASSWORDS) {
            if (passwordListWidget != null) {
                passwordListWidget.render(context, mouseX, mouseY, delta);
            }
            if (searchField != null) {
                searchField.render(context, mouseX, mouseY, delta);
            }

            if (PasswordManager.getTotalCount() == 0) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        "§7Немає збережених паролів.",
                        centerX, this.height / 2 - 10, 0xAAAAAA);
                context.drawCenteredTextWithShadow(this.textRenderer,
                        "§8Натисніть 'Додати пароль' або скористайтесь командою /sypass set",
                        centerX, this.height / 2 + 5, 0x888888);
            }
        } else {
            // Вкладка Bitwarden
            if (isProcessing) {
                context.drawCenteredTextWithShadow(this.textRenderer, "§b⏳ Зачекайте, виконується операція...", centerX, this.height / 2, 0xFFFFFF);
            } else {
                if (bwStage == BwStage.LOGIN) {
                    context.drawCenteredTextWithShadow(this.textRenderer, "Вхід до сховища Bitwarden", centerX, this.height / 2 - 65, 0xFFFFFF);
                    if (bwEmailField != null) bwEmailField.render(context, mouseX, mouseY, delta);
                    if (bwPasswordField != null) bwPasswordField.render(context, mouseX, mouseY, delta);
                } else if (bwStage == BwStage.OTP) {
                    context.drawCenteredTextWithShadow(this.textRenderer, "Двофакторна автентифікація (2FA)", centerX, this.height / 2 - 55, 0xFFAA00);
                    if (bwOtpField != null) bwOtpField.render(context, mouseX, mouseY, delta);
                } else if (bwStage == BwStage.API_KEY) {
                    context.drawCenteredTextWithShadow(this.textRenderer, "Вхід за допомогою API Key", centerX, this.height / 2 - 70, 0xFFFFFF);
                    if (bwClientIdField != null) bwClientIdField.render(context, mouseX, mouseY, delta);
                    if (bwClientSecretField != null) bwClientSecretField.render(context, mouseX, mouseY, delta);
                    if (bwPasswordField != null) bwPasswordField.render(context, mouseX, mouseY, delta);
                } else if (bwStage == BwStage.SERVER_CONFIG) {
                    context.drawCenteredTextWithShadow(this.textRenderer, "Налаштування сервера Bitwarden / Vaultwarden", centerX, this.height / 2 - 40, 0xFFFFFF);
                    if (bwServerUrlField != null) bwServerUrlField.render(context, mouseX, mouseY, delta);
                } else if (bwStage == BwStage.LOGGED_IN) {
                    context.drawCenteredTextWithShadow(this.textRenderer, "§a✔ Сховище Bitwarden підключено", centerX, this.height / 2 - 60, 0x55FF55);
                    if (cachedStatusInfo != null && !cachedStatusInfo.userEmail().isEmpty()) {
                        context.drawCenteredTextWithShadow(this.textRenderer, "§7Акаунт: §f" + cachedStatusInfo.userEmail(), centerX, this.height / 2 - 48, 0xAAAAAA);
                    }
                }

                // Відображення діагностики CLI
                if (cachedStatusInfo != null && !cachedStatusInfo.isInstalled()) {
                    context.drawCenteredTextWithShadow(this.textRenderer,
                            "§c⚠ Bitwarden CLI (bw) не знайдено в системі!", centerX, this.height - 45, 0xFF5555);
                }
            }
        }

        // Повідомлення про статус
        if (!statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, statusMessage, centerX, this.height - 40, 0xFFFFFF);
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

    // ==========================================
    // Вкладений клас списку паролів (Scrollable)
    // ==========================================
    @Environment(EnvType.CLIENT)
    public static class PasswordListWidget extends ElementListWidget<PasswordListWidget.PasswordEntry> {

        private final SYPassScreen parentScreen;

        public PasswordListWidget(MinecraftClient client, int width, int height, int y, int itemHeight, SYPassScreen parentScreen) {
            super(client, width, height, y, itemHeight);
            this.parentScreen = parentScreen;
            this.centerListVertically = false;
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 30);
        }

        @Override
        protected int getScrollbarX() {
            return (this.width + getRowWidth()) / 2 + 6;
        }

        public void refresh(String query) {
            this.clearEntries();

            String lowerQuery = query == null ? "" : query.trim().toLowerCase();
            Map<String, Map<String, PasswordManager.AccountData>> allData = PasswordManager.getAllData();

            for (Map.Entry<String, Map<String, PasswordManager.AccountData>> serverEntry : allData.entrySet()) {
                String serverIp = serverEntry.getKey();
                for (Map.Entry<String, PasswordManager.AccountData> accEntry : serverEntry.getValue().entrySet()) {
                    String username = accEntry.getKey();
                    PasswordManager.AccountData data = accEntry.getValue();

                    boolean matches = lowerQuery.isEmpty() ||
                            serverIp.toLowerCase().contains(lowerQuery) ||
                            username.toLowerCase().contains(lowerQuery) ||
                            data.command().toLowerCase().contains(lowerQuery);

                    if (matches) {
                        this.addEntry(new PasswordEntry(this.client, this.parentScreen, serverIp, username, data));
                    }
                }
            }
        }

        @Environment(EnvType.CLIENT)
        public static class PasswordEntry extends ElementListWidget.Entry<PasswordEntry> {
            private final MinecraftClient client;
            private final SYPassScreen parentScreen;
            private final String serverIp;
            private final String username;
            private final PasswordManager.AccountData data;
            private boolean showPassword = false;

            private final ButtonWidget copyButton;
            private final ButtonWidget toggleVisButton;
            private final ButtonWidget editButton;
            private final ButtonWidget deleteButton;
            private final List<Element> children = new ArrayList<>();
            private final List<Selectable> selectables = new ArrayList<>();

            public PasswordEntry(MinecraftClient client, SYPassScreen parentScreen, String serverIp, String username, PasswordManager.AccountData data) {
                this.client = client;
                this.parentScreen = parentScreen;
                this.serverIp = serverIp;
                this.username = username;
                this.data = data;

                // Кнопка копіювання
                this.copyButton = ButtonWidget.builder(Text.literal("📋"), b -> {
                    if (client.keyboard != null) {
                        client.keyboard.setClipboard(data.password());
                        parentScreen.setStatusMessage("§aСкопійовано пароль для §e" + username + "§a!");
                    }
                }).dimensions(0, 0, 20, 20).tooltip(Tooltip.of(Text.literal("Скопіювати пароль у буфер"))).build();

                // Кнопка приховати / показати
                this.toggleVisButton = ButtonWidget.builder(Text.literal("👁"), b -> {
                    this.showPassword = !this.showPassword;
                    b.setMessage(Text.literal(this.showPassword ? "🙈" : "👁"));
                    b.setTooltip(Tooltip.of(Text.literal(this.showPassword ? "Приховати пароль" : "Показати пароль")));
                }).dimensions(0, 0, 20, 20).tooltip(Tooltip.of(Text.literal("Показати / сховати пароль"))).build();

                // Кнопка редагування
                this.editButton = ButtonWidget.builder(Text.literal("✏"), b -> {
                    client.setScreen(new EditPasswordScreen(parentScreen, serverIp, username, data.password(), data.command()));
                }).dimensions(0, 0, 20, 20).tooltip(Tooltip.of(Text.literal("Редагувати запис"))).build();

                // Кнопка видалення
                this.deleteButton = ButtonWidget.builder(Text.literal("🗑"), b -> {
                    PasswordManager.removePassword(serverIp, username);
                    parentScreen.setStatusMessage("§cВидалено пароль для " + username + " (" + serverIp + ")");
                    parentScreen.refreshPasswordList();
                }).dimensions(0, 0, 20, 20).tooltip(Tooltip.of(Text.literal("Видалити пароль"))).build();

                children.add(copyButton);
                children.add(toggleVisButton);
                children.add(editButton);
                children.add(deleteButton);

                selectables.add(copyButton);
                selectables.add(toggleVisButton);
                selectables.add(editButton);
                selectables.add(deleteButton);
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
                // Фон та рамка картки
                int bgColor = hovered ? 0x55000000 : 0x30000000;
                int borderColor = hovered ? 0xFFAAAAAA : 0x40666666;
                context.fill(x - 2, y, x + entryWidth + 2, y + entryHeight - 2, bgColor);
                context.drawBorder(x - 2, y, entryWidth + 4, entryHeight - 2, borderColor);

                // Доступна ширина для тексту з урахуванням кнопок праворуч
                int textMaxWidth = entryWidth - 96;

                // Рядок 1: Сервер (виділено, обрізається при необхідності)
                String serverLabel = "🌐 " + serverIp;
                String trimmedServer = client.textRenderer.trimToWidth(serverLabel, textMaxWidth);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(trimmedServer).formatted(Formatting.GOLD, Formatting.BOLD),
                        x + 6, y + 4, 0xFFFFFF);

                // Рядок 2: Нікнейм та Пароль (дві адаптивні колонки)
                int halfWidth = (textMaxWidth - 8) / 2;

                String userLabel = "👤 " + username;
                String trimmedUser = client.textRenderer.trimToWidth(userLabel, halfWidth);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(trimmedUser).formatted(Formatting.YELLOW),
                        x + 6, y + 17, 0xFFFFFF);

                String passDisplay = showPassword ? ("🔑 " + data.password()) : "🔑 ••••••••";
                String trimmedPass = client.textRenderer.trimToWidth(passDisplay, halfWidth);
                int passColor = showPassword ? 0x55FFFF : 0x888888;
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(trimmedPass),
                        x + 6 + halfWidth + 8, y + 17, passColor);

                // Рядок 3: Команда авторизації
                String cmdDisplay = "⚙ " + data.command();
                String trimmedCmd = client.textRenderer.trimToWidth(cmdDisplay, textMaxWidth);
                context.drawTextWithShadow(client.textRenderer,
                        Text.literal(trimmedCmd).formatted(Formatting.DARK_GRAY),
                        x + 6, y + 30, 0x888888);

                // Розміщення кнопок дій праворуч
                int btnY = y + (entryHeight - 20) / 2;
                int rightX = x + entryWidth - 2;

                deleteButton.setX(rightX - 20);
                deleteButton.setY(btnY);
                deleteButton.render(context, mouseX, mouseY, tickDelta);

                editButton.setX(rightX - 42);
                editButton.setY(btnY);
                editButton.render(context, mouseX, mouseY, tickDelta);

                toggleVisButton.setX(rightX - 64);
                toggleVisButton.setY(btnY);
                toggleVisButton.render(context, mouseX, mouseY, tickDelta);

                copyButton.setX(rightX - 86);
                copyButton.setY(btnY);
                copyButton.render(context, mouseX, mouseY, tickDelta);
            }

            @Override
            public List<? extends Element> children() {
                return children;
            }

            @Override
            public List<? extends Selectable> selectableChildren() {
                return selectables;
            }
        }
    }
}