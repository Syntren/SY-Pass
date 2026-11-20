package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Main SY-Pass Vault screen coordinator.
 * Delegates tab logic to {@link VaultLockView}, {@link PasswordListWidget},
 * {@link BitwardenTabController}, and {@link SettingsTabController}.
 */
public class SYPassScreen extends Screen {

    public enum Tab {
        LOCAL_PASSWORDS,
        BITWARDEN,
        SETTINGS
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

    private final Screen parent;
    private final VaultLockView lockView = new VaultLockView(this);
    private final BitwardenTabController bitwardenTab = new BitwardenTabController(this);
    private final SettingsTabController settingsTab = new SettingsTabController(this);

    private Tab activeTab = Tab.LOCAL_PASSWORDS;
    private SortMode sortMode = SortMode.FAVORITES_FIRST;
    private boolean onlyFavorites = false;
    private String searchQuery = "";
    private String statusMessage = "";

    private final Set<String> revealedPasswords = new HashSet<>();
    private String pendingDeleteKey = null;
    private String pendingBwDeleteKey = null;
    private String activeBwDeletingKey = null;
    private String activeBwSuccessKey = null;
    private long bwSuccessUntilMs = 0L;

    private PasswordListWidget passwordList;

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

    void rebuildView() {
        this.clearWidgets();
        this.init();
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T registerWidget(T widget) {
        return this.addRenderableWidget(widget);
    }

    // State accessors for PasswordListWidget
    boolean isPasswordRevealed(String key) { return revealedPasswords.contains(key); }

    boolean togglePasswordRevealed(String key) {
        if (!revealedPasswords.remove(key)) {
            revealedPasswords.add(key);
            return true;
        }
        return false;
    }

    String getPendingDeleteKey() { return pendingDeleteKey; }
    void setPendingDeleteKey(String key) { this.pendingDeleteKey = key; }

    String getPendingBwDeleteKey() { return pendingBwDeleteKey; }
    void setPendingBwDeleteKey(String key) { this.pendingBwDeleteKey = key; }

    String getActiveBwDeletingKey() { return activeBwDeletingKey; }
    void setActiveBwDeletingKey(String key) { this.activeBwDeletingKey = key; }

    String getActiveBwSuccessKey() { return activeBwSuccessKey; }
    long getBwSuccessUntilMs() { return bwSuccessUntilMs; }

    void setActiveBwSuccess(String key, long untilMs) {
        this.activeBwSuccessKey = key;
        this.bwSuccessUntilMs = untilMs;
    }

    void clearActiveBwSuccess() {
        this.activeBwSuccessKey = null;
        this.bwSuccessUntilMs = 0L;
    }

    @Override
    protected void init() {
        super.init();
        PasswordManager.recordActivity();

        int contentWidth = Math.min(440, this.width - 32);
        int contentX = (this.width - contentWidth) / 2;

        if (PasswordManager.isVaultLocked()) {
            lockView.init(this.width, this.height, contentWidth, this.font);
            return;
        }

        if (!SYPassConfig.isBitwardenEnabled() && activeTab == Tab.BITWARDEN) {
            activeTab = Tab.LOCAL_PASSWORDS;
        }

        initHeaderTabs();

        switch (activeTab) {
            case LOCAL_PASSWORDS -> initLocalPasswordsTab(contentX, contentWidth);
            case BITWARDEN -> bitwardenTab.init(this.width, this.height, contentWidth, this.font);
            case SETTINGS -> settingsTab.init(this.width, this.height, contentWidth, this.font);
        }
    }

    private void initHeaderTabs() {
        int headerY = 8;
        int localWidth = 150;
        int bwWidth = SYPassConfig.isBitwardenEnabled() ? 120 : 0;
        int settingsWidth = 110;
        int totalTabsWidth = localWidth + (bwWidth > 0 ? bwWidth + 6 : 0) + settingsWidth + 6;
        int tabStartX = (this.width - totalTabsWidth) / 2;

        String localTitle = (activeTab == Tab.LOCAL_PASSWORDS ? "§e§l" : "§7")
                + Component.translatable("sypass.gui.tab.local", PasswordManager.getTotalCount()).getString();
        addRenderableWidget(Button.builder(Component.literal(localTitle), btn -> switchTab(Tab.LOCAL_PASSWORDS))
                .bounds(tabStartX, headerY, localWidth, 20).build());

        int nextX = tabStartX + localWidth + 6;
        if (SYPassConfig.isBitwardenEnabled()) {
            String bwTitle = (activeTab == Tab.BITWARDEN ? "§e§l" : "§7")
                    + Component.translatable("sypass.gui.tab.bitwarden").getString();
            addRenderableWidget(Button.builder(Component.literal(bwTitle), btn -> {
                bitwardenTab.onTabSelected();
                switchTab(Tab.BITWARDEN);
            }).bounds(nextX, headerY, bwWidth, 20).build());
            nextX += bwWidth + 6;
        }

        String settingsTitle = (activeTab == Tab.SETTINGS ? "§e§l" : "§7")
                + Component.translatable("sypass.gui.tab.settings").getString();
        addRenderableWidget(Button.builder(Component.literal(settingsTitle), btn -> {
            settingsTab.resetToMain();
            switchTab(Tab.SETTINGS);
        }).bounds(nextX, headerY, settingsWidth, 20).build());
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
        rebuildView();
    }

    private void initLocalPasswordsTab(int contentX, int contentWidth) {
        int contentTop = 32;
        int sortBtnWidth = 34;
        int favFilterWidth = 22;
        int searchWidth = contentWidth - sortBtnWidth - favFilterWidth - 8;

        EditBox searchBox = new EditBox(this.font, contentX, contentTop, searchWidth, 20, Component.translatable("sypass.gui.search.placeholder"));
        searchBox.setHint(Component.translatable("sypass.gui.search.placeholder"));
        searchBox.setValue(this.searchQuery);
        searchBox.setResponder(query -> {
            this.searchQuery = query;
            refreshPasswordList();
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal(this.sortMode.getLabel()), btn -> {
            this.sortMode = this.sortMode.next();
            btn.setMessage(Component.literal(this.sortMode.getLabel()));
            btn.setTooltip(Tooltip.create(Component.translatable(this.sortMode.getTranslationKey())));
            refreshPasswordList();
        }).bounds(contentX + searchWidth + 4, contentTop, sortBtnWidth, 20)
          .tooltip(Tooltip.create(Component.translatable(this.sortMode.getTranslationKey()))).build());

        addRenderableWidget(Button.builder(Component.literal(this.onlyFavorites ? "§e★" : "§7☆"), btn -> {
            this.onlyFavorites = !this.onlyFavorites;
            btn.setMessage(Component.literal(this.onlyFavorites ? "§e★" : "§7☆"));
            btn.setTooltip(Tooltip.create(Component.translatable(this.onlyFavorites
                    ? "sypass.gui.filter.all.tooltip"
                    : "sypass.gui.filter.favorites.tooltip")));
            refreshPasswordList();
        }).bounds(contentX + searchWidth + 4 + sortBtnWidth + 4, contentTop, favFilterWidth, 20)
          .tooltip(Tooltip.create(Component.translatable(this.onlyFavorites
                  ? "sypass.gui.filter.all.tooltip"
                  : "sypass.gui.filter.favorites.tooltip"))).build());

        int listTop = contentTop + 24;
        int listHeight = this.height - listTop - 54;
        this.passwordList = new PasswordListWidget(this.minecraft, this.width, listHeight, listTop, 46, contentWidth, this);
        addRenderableWidget(this.passwordList);
        refreshPasswordList();

        int bottomRowY = this.height - 26;
        int gap = 6;
        if (SYPassConfig.isBitwardenEnabled()) {
            int addW = (contentWidth - gap * 2) * 38 / 100;
            int syncW = (contentWidth - gap * 2) * 36 / 100;
            int closeW = (contentWidth - gap * 2) - addW - syncW;

            addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.add"), btn -> {
                if (this.minecraft != null) this.minecraft.setScreen(new EditPasswordScreen(this));
            }).bounds(contentX, bottomRowY, addW, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.sync_cloud"), btn ->
                    bitwardenTab.handleFullSync(btn)
            ).bounds(contentX + addW + gap, bottomRowY, syncW, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                    .bounds(contentX + addW + gap + syncW + gap, bottomRowY, closeW, 20).build());
        } else {
            int addW = (contentWidth - gap) / 2;
            int closeW = contentWidth - gap - addW;

            addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.add"), btn -> {
                if (this.minecraft != null) this.minecraft.setScreen(new EditPasswordScreen(this));
            }).bounds(contentX, bottomRowY, addW, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("sypass.gui.button.close"), btn -> onClose())
                    .bounds(contentX + addW + gap, bottomRowY, closeW, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (PasswordManager.isVaultLocked()) {
            lockView.render(guiGraphics, this.font, this.width, this.height, this.statusMessage);
            return;
        }

        int contentWidth = Math.min(440, this.width - 32);

        if (activeTab == Tab.LOCAL_PASSWORDS && (this.passwordList == null || this.passwordList.children().isEmpty())) {
            int emptyY = this.height / 2 - 16;
            boolean showFavEmpty = onlyFavorites && PasswordManager.getTotalCount() > 0;
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable(showFavEmpty ? "sypass.gui.empty.favorites" : "sypass.gui.empty.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    this.width / 2, emptyY, 0xFFFFFF);
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable(showFavEmpty ? "sypass.gui.empty.favorites.desc" : "sypass.gui.empty.desc").withStyle(ChatFormatting.GRAY),
                    this.width / 2, emptyY + 12, 0xAAAAAA);
        } else if (activeTab == Tab.SETTINGS) {
            settingsTab.render(guiGraphics, this.font, this.width, this.height, contentWidth);
        } else if (activeTab == Tab.BITWARDEN) {
            bitwardenTab.render(guiGraphics, this.font, this.width, this.height, contentWidth);
        }

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

        guiGraphics.fill(panelX, 4, panelX + panelWidth, this.height - 4, GuiHelpers.PANEL_BG);
        guiGraphics.renderOutline(panelX, 4, panelWidth, this.height - 8, GuiHelpers.PANEL_BORDER);
    }

    @Override
    public void onClose() {
        this.revealedPasswords.clear();
        this.bitwardenTab.clearSensitiveFields();
        this.lockView.resetState();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
