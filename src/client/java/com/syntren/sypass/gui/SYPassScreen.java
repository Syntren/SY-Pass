package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.CheckboxComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.TextureComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Environment(EnvType.CLIENT)
public class SYPassScreen extends BaseOwoScreen<FlowLayout> {

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
        LOGGED_IN,
        CONFIRM_LOGOUT,
        CONFIRM_DELETE_CLI
    }

    private enum SettingsStage {
        MAIN,
        BACKUP,
        BITWARDEN
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
    private BwStage preConfirmStage = BwStage.LOGGED_IN;
    private SettingsStage settingsStage = SettingsStage.MAIN;
    private SortMode sortMode = SortMode.FAVORITES_FIRST;
    private boolean onlyFavorites = false;
    private boolean exportAsCsv = false;
    private static final int PAGE_SIZE = 50;
    private int displayLimit = PAGE_SIZE;

    private String searchQuery = "";
    private String statusMessage = "";
    private boolean isProcessing = false;
    private String savedEmail = "";
    private String savedPassword = "";
    private boolean showMasterPassword = false;
    private BitwardenManager.BwStatusInfo cachedStatusInfo = null;

    private LabelComponent checkingStatusLabel;
    private FlowLayout checkingProgressBar;
    private LabelComponent processingStatusLabel;
    private FlowLayout processingProgressBar;

    private final Set<String> revealedPasswords = new HashSet<>();
    private FlowLayout passwordListContainer;
    private String pendingDeleteKey = null;
    private String pendingBwDeleteKey = null;
    private String selected2faMethod = "0";
    private LabelComponent persistentStatusLabel = null;

    private static final java.util.regex.Pattern STATUS_PREFIX_PATTERN = java.util.regex.Pattern.compile("^[§a-f0-9⏳⟳ ]+");
    private static final java.util.regex.Pattern STATUS_SUFFIX_DOTS_PATTERN = java.util.regex.Pattern.compile("\\.+$");
    private int lastDotCount = -1;
    private String cachedBaseStatus = "";
    private String lastRawStatusMessage = null;

    public SYPassScreen() {
        this(null);
    }

    public SYPassScreen(Screen parent) {
        super(Text.translatable("sypass.gui.title"));
        this.parent = parent;
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message != null ? message : "";
        if (this.persistentStatusLabel != null) {
            this.persistentStatusLabel.text(Text.literal(this.statusMessage));
        }
    }

    public void refreshPasswordList() {
        if (passwordListContainer != null) {
            populatePasswordList(passwordListContainer, this.searchQuery);
        }
    }

    private void rebuildUI() {
        if (this.uiAdapter != null) {
            this.uiAdapter.rootComponent.clearChildren();
            this.build(this.uiAdapter.rootComponent);
        }
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout rootComponent) {
        rootComponent.surface(Surface.VANILLA_TRANSLUCENT);
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER);
        rootComponent.verticalAlignment(VerticalAlignment.TOP);
        rootComponent.padding(Insets.of(6));

        // 1. Верхній рядок вкладок
        FlowLayout tabRow = Containers.horizontalFlow(Sizing.content(), Sizing.fixed(20));
        tabRow.gap(8);
        tabRow.margins(Insets.bottom(4));

        if (!com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled() && activeTab == Tab.BITWARDEN) {
            activeTab = Tab.LOCAL_PASSWORDS;
        }

        int count = PasswordManager.getTotalCount();
        Text localTabText = Text.translatable("sypass.gui.tab.local", count)
                .formatted(activeTab == Tab.LOCAL_PASSWORDS ? Formatting.YELLOW : Formatting.GRAY);
        ButtonComponent localTabBtn = Components.button(localTabText, b -> {
            activeTab = Tab.LOCAL_PASSWORDS;
            rebuildUI();
        });
        localTabBtn.horizontalSizing(Sizing.fixed(150));

        ButtonComponent bwTabBtn = null;
        if (com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled()) {
            Text bwTabText = Text.translatable("sypass.gui.tab.bitwarden")
                    .formatted(activeTab == Tab.BITWARDEN ? Formatting.YELLOW : Formatting.GRAY);
            bwTabBtn = Components.button(bwTabText, b -> {
                activeTab = Tab.BITWARDEN;
                BitwardenManager.invalidateStatusCache();
                this.cachedStatusInfo = null;
                if (BitwardenManager.hasActiveSession()) {
                    this.bwStage = BwStage.LOGGED_IN;
                } else if (!BitwardenManager.isCliInstalled()) {
                    this.bwStage = BwStage.CLI_NOT_FOUND;
                } else {
                    this.bwStage = BwStage.CHECKING_STATUS;
                }
                updateBitwardenStatusAsync();
                rebuildUI();
            });
            bwTabBtn.horizontalSizing(Sizing.fixed(120));
        }

        Text settingsTabText = Text.translatable("sypass.gui.tab.settings")
                .formatted(activeTab == Tab.SETTINGS ? Formatting.YELLOW : Formatting.GRAY);
        ButtonComponent settingsTabBtn = Components.button(settingsTabText, b -> {
            activeTab = Tab.SETTINGS;
            rebuildUI();
        });
        settingsTabBtn.horizontalSizing(Sizing.fixed(110));

        tabRow.child(localTabBtn);
        if (bwTabBtn != null) {
            tabRow.child(bwTabBtn);
        }
        tabRow.child(settingsTabBtn);
        rootComponent.child(tabRow);

        // 2. Основна частина вкладки
        if (activeTab == Tab.LOCAL_PASSWORDS) {
            buildLocalTab(rootComponent);
        } else if (activeTab == Tab.BITWARDEN && com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled()) {
            buildBitwardenTab(rootComponent);
        } else {
            buildSettingsTab(rootComponent);
        }
    }

    // ==========================================
    // Локальна вкладка (Пошук + Скрол-список)
    // ==========================================
    private void buildLocalTab(FlowLayout root) {
        int contentWidth = Math.min(460, this.width - 30);

        FlowLayout searchRow = Containers.horizontalFlow(Sizing.fixed(contentWidth), Sizing.fixed(20));
        searchRow.gap(4);
        searchRow.verticalAlignment(VerticalAlignment.CENTER);
        searchRow.margins(Insets.bottom(4));

        int sortBtnWidth = 34;
        int favFilterWidth = 22;
        int searchWidth = contentWidth - sortBtnWidth - favFilterWidth - 8;

        TextBoxComponent searchBox = Components.textBox(Sizing.fixed(searchWidth));
        searchBox.setMaxLength(256);
        searchBox.setText(this.searchQuery);
        searchBox.setCursor(0, false);
        searchBox.setPlaceholder(Text.translatable("sypass.gui.search.placeholder"));
        searchBox.onChanged().subscribe(text -> {
            this.searchQuery = text;
            this.displayLimit = PAGE_SIZE;
            refreshPasswordList();
        });

        ButtonComponent sortBtn = Components.button(Text.literal(this.sortMode.getLabel()), b -> {
            this.sortMode = this.sortMode.next();
            b.setMessage(Text.literal(this.sortMode.getLabel()));
            b.tooltip(Text.translatable(this.sortMode.getTranslationKey()));
            this.displayLimit = PAGE_SIZE;
            refreshPasswordList();
        });
        sortBtn.horizontalSizing(Sizing.fixed(sortBtnWidth));
        sortBtn.tooltip(Text.translatable(this.sortMode.getTranslationKey()));

        ButtonComponent favFilterBtn = Components.button(Text.literal(this.onlyFavorites ? "§e★" : "§7☆"), b -> {
            this.onlyFavorites = !this.onlyFavorites;
            b.setMessage(Text.literal(this.onlyFavorites ? "§e★" : "§7☆"));
            b.tooltip(Text.translatable(this.onlyFavorites ? "sypass.gui.filter.all.tooltip" : "sypass.gui.filter.favorites.tooltip"));
            this.displayLimit = PAGE_SIZE;
            refreshPasswordList();
        });
        favFilterBtn.horizontalSizing(Sizing.fixed(favFilterWidth));
        favFilterBtn.tooltip(Text.translatable(this.onlyFavorites ? "sypass.gui.filter.all.tooltip" : "sypass.gui.filter.favorites.tooltip"));

        searchRow.child(searchBox);
        searchRow.child(sortBtn);
        searchRow.child(favFilterBtn);
        root.child(searchRow);

        this.passwordListContainer = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.passwordListContainer.gap(4);
        this.passwordListContainer.horizontalAlignment(HorizontalAlignment.CENTER);
        populatePasswordList(this.passwordListContainer, this.searchQuery);

        int scrollHeight = Math.max(100, this.height - 100);
        ScrollContainer<FlowLayout> scrollContainer = Containers.verticalScroll(
                Sizing.fixed(contentWidth),
                Sizing.fixed(scrollHeight),
                this.passwordListContainer
        );
        scrollContainer.margins(Insets.bottom(4));
        root.child(scrollContainer);

        FlowLayout bottomPanel = Containers.verticalFlow(Sizing.content(), Sizing.content());
        bottomPanel.gap(4);
        bottomPanel.horizontalAlignment(HorizontalAlignment.CENTER);

        boolean bwEnabled = com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled();
        if (!bwEnabled && statusMessage != null && statusMessage.contains("Bitwarden")) {
            statusMessage = "";
        }

        this.persistentStatusLabel = Components.label(Text.literal(statusMessage != null ? statusMessage : "")).shadow(true);
        bottomPanel.child(this.persistentStatusLabel);

        FlowLayout bottomButtons = Containers.horizontalFlow(Sizing.content(), Sizing.fixed(20));
        bottomButtons.gap(6);

        int addWidth = bwEnabled ? 130 : 160;
        int closeWidth = bwEnabled ? 90 : 140;

        ButtonComponent addBtn = Components.button(Text.translatable("sypass.gui.button.add"), b -> {
            if (this.client != null) {
                this.client.setScreen(new EditPasswordScreen(this));
            }
        });
        addBtn.horizontalSizing(Sizing.fixed(addWidth));
        bottomButtons.child(addBtn);

        if (bwEnabled) {
            ButtonComponent syncBtn = Components.button(Text.translatable("sypass.gui.button.sync_cloud"), b -> handleFullSync());
            syncBtn.horizontalSizing(Sizing.fixed(120));
            bottomButtons.child(syncBtn);
        }

        ButtonComponent closeBtn = Components.button(Text.translatable("sypass.gui.button.close"), b -> this.close());
        closeBtn.horizontalSizing(Sizing.fixed(closeWidth));
        bottomButtons.child(closeBtn);

        bottomPanel.child(bottomButtons);
        root.child(bottomPanel);
    }

    private record EntryItem(String serverIp, String username, PasswordManager.AccountData data) {}

    private void populatePasswordList(FlowLayout container, String query) {
        container.clearChildren();

        String lowerQuery = query == null ? "" : query.trim().toLowerCase();
        Map<String, Map<String, PasswordManager.AccountData>> allData = PasswordManager.getAllData();
        List<EntryItem> entries = new ArrayList<>();

        for (Map.Entry<String, Map<String, PasswordManager.AccountData>> serverEntry : allData.entrySet()) {
            String serverIp = serverEntry.getKey();
            for (Map.Entry<String, PasswordManager.AccountData> accEntry : serverEntry.getValue().entrySet()) {
                String username = accEntry.getKey();
                PasswordManager.AccountData data = accEntry.getValue();

                if (this.onlyFavorites && !data.isFavorite()) {
                    continue;
                }

                boolean matches = lowerQuery.isEmpty() ||
                        serverIp.toLowerCase().contains(lowerQuery) ||
                        username.toLowerCase().contains(lowerQuery) ||
                        data.command().toLowerCase().contains(lowerQuery);

                if (matches) {
                    entries.add(new EntryItem(serverIp, username, data));
                }
            }
        }

        switch (this.sortMode) {
            case FAVORITES_FIRST -> entries.sort((a, b) -> {
                if (a.data().isFavorite() != b.data().isFavorite()) {
                    return a.data().isFavorite() ? -1 : 1;
                }
                int sComp = a.serverIp().compareToIgnoreCase(b.serverIp());
                if (sComp != 0) return sComp;
                return a.username().compareToIgnoreCase(b.username());
            });
            case ALPHABETICAL -> entries.sort((a, b) -> {
                int sComp = a.serverIp().compareToIgnoreCase(b.serverIp());
                if (sComp != 0) return sComp;
                return a.username().compareToIgnoreCase(b.username());
            });
            case RECENT -> entries.sort((a, b) -> {
                int rComp = Long.compare(b.data().lastUsed(), a.data().lastUsed());
                if (rComp != 0) return rComp;
                int sComp = a.serverIp().compareToIgnoreCase(b.serverIp());
                if (sComp != 0) return sComp;
                return a.username().compareToIgnoreCase(b.username());
            });
        }

        if (entries.isEmpty()) {
            FlowLayout emptyLayout = Containers.verticalFlow(Sizing.fill(100), Sizing.fixed(80));
            emptyLayout.gap(4);
            emptyLayout.horizontalAlignment(HorizontalAlignment.CENTER);
            emptyLayout.verticalAlignment(VerticalAlignment.CENTER);
            emptyLayout.child(Components.label(Text.translatable(this.onlyFavorites ? "sypass.gui.empty.favorites" : "sypass.gui.empty.title")).shadow(true));
            emptyLayout.child(Components.label(Text.translatable(this.onlyFavorites ? "sypass.gui.empty.favorites.desc" : "sypass.gui.empty.desc")).shadow(true));
            container.child(emptyLayout);
            return;
        }

        int totalMatches = entries.size();
        List<EntryItem> visibleEntries = (totalMatches > this.displayLimit) ? entries.subList(0, this.displayLimit) : entries;

        boolean hasFavorites = false;
        boolean hasNonFavorites = false;
        for (EntryItem entry : visibleEntries) {
            if (entry.data().isFavorite()) {
                hasFavorites = true;
            } else {
                hasNonFavorites = true;
            }
            if (hasFavorites && hasNonFavorites) break;
        }

        if (this.sortMode == SortMode.FAVORITES_FIRST && !this.onlyFavorites && hasFavorites && hasNonFavorites) {
            boolean favHeaderAdded = false;
            boolean otherHeaderAdded = false;

            for (EntryItem entry : visibleEntries) {
                if (entry.data().isFavorite()) {
                    if (!favHeaderAdded) {
                        container.child(createSectionHeader(Text.translatable("sypass.gui.section.favorites").formatted(Formatting.YELLOW, Formatting.BOLD)));
                        favHeaderAdded = true;
                    }
                } else {
                    if (!otherHeaderAdded) {
                        container.child(createSectionHeader(Text.translatable("sypass.gui.section.other").formatted(Formatting.GRAY, Formatting.BOLD)));
                        otherHeaderAdded = true;
                    }
                }
                container.child(createPasswordCard(entry.serverIp(), entry.username(), entry.data()));
            }
        } else {
            for (EntryItem entry : visibleEntries) {
                container.child(createPasswordCard(entry.serverIp(), entry.username(), entry.data()));
            }
        }

        if (totalMatches > this.displayLimit) {
            int remaining = totalMatches - this.displayLimit;
            ButtonComponent loadMoreBtn = Components.button(
                    Text.translatable("sypass.gui.button.load_more", remaining),
                    b -> {
                        this.displayLimit += PAGE_SIZE;
                        refreshPasswordList();
                    }
            );
            loadMoreBtn.horizontalSizing(Sizing.fill(100));
            loadMoreBtn.margins(Insets.vertical(4));
            container.child(loadMoreBtn);
        }
    }

    private Component createSectionHeader(Text text) {
        FlowLayout header = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(16));
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.margins(Insets.of(4, 2, 2, 2));
        header.child(Components.label(text).shadow(true));
        return header;
    }

    private Component createPasswordCard(String serverIp, String username, PasswordManager.AccountData data) {
        String key = serverIp + ":::" + username;
        int contentWidth = Math.min(460, this.width - 30);

        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        String currentClientUser = (mc != null && mc.getSession() != null) ? mc.getSession().getUsername() : null;
        boolean isActiveAccount = currentClientUser != null && currentClientUser.equalsIgnoreCase(username);

        FlowLayout card = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        card.verticalAlignment(VerticalAlignment.CENTER);
        card.surface(isActiveAccount ? Surface.PANEL.and(Surface.outline(0x8855FF55)) : Surface.PANEL);
        card.padding(Insets.of(6));
        card.margins(Insets.vertical(2));

        // 1. Server icon (24x24) to the left of server name
        int iconSize = 24;
        int iconMargin = 6;
        Identifier serverIconId = ServerIconManager.getServerIcon(serverIp);
        TextureComponent iconComponent = Components.texture(serverIconId, 0, 0, 64, 64, 64, 64);
        iconComponent.horizontalSizing(Sizing.fixed(iconSize));
        iconComponent.verticalSizing(Sizing.fixed(iconSize));
        iconComponent.margins(Insets.right(iconMargin));
        card.child(iconComponent);

        boolean canDeleteFromBw = com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled() && data.isSynced() && BitwardenManager.hasActiveSession();
        int actionsWidth = canDeleteFromBw ? 142 : 115;
        int infoWidth = contentWidth - actionsWidth - iconSize - iconMargin - 15;
        FlowLayout infoLayout = Containers.verticalFlow(Sizing.fixed(infoWidth), Sizing.content());
        infoLayout.gap(2);

        FlowLayout topRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        topRow.gap(4);
        topRow.verticalAlignment(VerticalAlignment.CENTER);

        if (com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled()) {
            LabelComponent cloudBadge = Components.label(Text.literal(data.isSynced() ? "§a☁" : "§7☁"));
            cloudBadge.tooltip(Text.translatable(data.isSynced() ? "sypass.gui.sync.badge.synced" : "sypass.gui.sync.badge.local"));
            topRow.child(cloudBadge);
        }

        String serverLabelText = (data.isFavorite() ? "§e★ " : "") + serverIp;
        topRow.child(Components.label(Text.literal(serverLabelText).formatted(Formatting.GOLD, Formatting.BOLD)).shadow(true));
        infoLayout.child(topRow);

        FlowLayout userPassRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        userPassRow.gap(8);
        userPassRow.verticalAlignment(VerticalAlignment.CENTER);

        if (isActiveAccount) {
            LabelComponent userLabel = Components.label(Text.literal(username).formatted(Formatting.GREEN, Formatting.BOLD)).shadow(true);
            userLabel.tooltip(Text.translatable("sypass.gui.account.active.tooltip"));
            userPassRow.child(userLabel);
        } else {
            userPassRow.child(Components.label(Text.literal(username).formatted(Formatting.YELLOW)).shadow(true));
        }

        boolean isRevealed = revealedPasswords.contains(key);
        LabelComponent passLabel = Components.label(Text.literal(isRevealed ? data.password() : "••••••••"));
        passLabel.color(Color.ofRgb(isRevealed ? 0x55FFFF : 0x888888));
        passLabel.shadow(true);
        userPassRow.child(passLabel);

        infoLayout.child(userPassRow);
        infoLayout.child(Components.label(Text.literal(data.command()).formatted(Formatting.DARK_GRAY)).shadow(true));

        card.child(infoLayout);

        FlowLayout actionsLayout = Containers.horizontalFlow(Sizing.fixed(actionsWidth), Sizing.fixed(20));
        actionsLayout.gap(3);
        actionsLayout.horizontalAlignment(HorizontalAlignment.RIGHT);

        ButtonComponent favBtn = Components.button(Text.literal(data.isFavorite() ? "§e★" : "§7☆"), b -> {
            PasswordManager.toggleFavorite(serverIp, username);
            refreshPasswordList();
        });
        favBtn.horizontalSizing(Sizing.fixed(20));
        favBtn.tooltip(Text.translatable(data.isFavorite() ? "sypass.gui.button.unfavorite.tooltip" : "sypass.gui.button.favorite.tooltip"));

        ButtonComponent copyBtn = Components.button(Text.literal("📋"), b -> {
            if (this.client != null && this.client.keyboard != null) {
                PasswordManager.updateLastUsed(serverIp, username);
                this.client.keyboard.setClipboard(data.password());
                setStatusMessage(Text.translatable("sypass.gui.status.copied", username).getString());
            }
        });
        copyBtn.horizontalSizing(Sizing.fixed(20));
        copyBtn.tooltip(Text.translatable("sypass.gui.button.copy.tooltip"));

        ButtonComponent toggleEyeBtn = Components.button(Text.literal(isRevealed ? "§a●" : "§7○"), b -> {
            if (revealedPasswords.contains(key)) {
                revealedPasswords.remove(key);
                passLabel.text(Text.literal("••••••••"));
                passLabel.color(Color.ofRgb(0x888888));
                b.setMessage(Text.literal("§7○"));
                b.tooltip(Text.translatable("sypass.gui.button.show"));
            } else {
                revealedPasswords.add(key);
                passLabel.text(Text.literal(data.password()));
                passLabel.color(Color.ofRgb(0x55FFFF));
                b.setMessage(Text.literal("§a●"));
                b.tooltip(Text.translatable("sypass.gui.button.hide"));
            }
        });
        toggleEyeBtn.horizontalSizing(Sizing.fixed(20));
        toggleEyeBtn.tooltip(Text.translatable(isRevealed ? "sypass.gui.button.hide" : "sypass.gui.button.show"));

        ButtonComponent editBtn = Components.button(Text.literal("✎"), b -> {
            if (this.client != null) {
                this.client.setScreen(new EditPasswordScreen(this, serverIp, username, data.password(), data.command(), data.isSynced()));
            }
        });
        editBtn.horizontalSizing(Sizing.fixed(20));
        editBtn.tooltip(Text.translatable("sypass.gui.button.edit.tooltip"));

        actionsLayout.child(favBtn);
        actionsLayout.child(copyBtn);
        actionsLayout.child(toggleEyeBtn);
        actionsLayout.child(editBtn);

        // Кнопка видалення пароля безпосередньо з Bitwarden
        if (canDeleteFromBw) {
            boolean isPendingBw = key.equals(pendingBwDeleteKey);
            ButtonComponent deleteBwBtn = Components.button(Text.literal(isPendingBw ? "§4✔?" : "§c☁-"), null);
            deleteBwBtn.onPress(b -> {
                if (key.equals(pendingBwDeleteKey)) {
                    pendingBwDeleteKey = null;
                    handleDeleteFromBitwarden(serverIp, username, data);
                } else {
                    pendingBwDeleteKey = key;
                    pendingDeleteKey = null;
                    b.setMessage(Text.literal("§4✔?"));
                    b.tooltip(Text.translatable("sypass.gui.button.delete_bw.confirm"));
                }
            });
            deleteBwBtn.horizontalSizing(Sizing.fixed(23));
            deleteBwBtn.tooltip(Text.translatable(isPendingBw ? "sypass.gui.button.delete_bw.confirm" : "sypass.gui.button.delete_bw.tooltip"));
            actionsLayout.child(deleteBwBtn);
        }

        ButtonComponent deleteBtn = Components.button(Text.literal(key.equals(pendingDeleteKey) ? "§4✔?" : "§c✖"), null);
        deleteBtn.onPress(b -> {
            if (key.equals(pendingDeleteKey)) {
                pendingDeleteKey = null;
                pendingBwDeleteKey = null;
                String remoteId = data.remoteId();
                boolean wasSynced = data.isSynced();
                PasswordManager.removePassword(serverIp, username);
                if (wasSynced && com.syntren.sypass.config.SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
                    BitwardenManager.deleteSingleItemAsync(serverIp, username, remoteId);
                }
                this.statusMessage = Text.translatable("sypass.gui.status.deleted", username, serverIp).getString();
                refreshPasswordList();
            } else {
                pendingDeleteKey = key;
                pendingBwDeleteKey = null;
                b.setMessage(Text.literal("§4✔?"));
                b.tooltip(Text.translatable("sypass.gui.button.delete.confirm"));
            }
        });
        deleteBtn.horizontalSizing(Sizing.fixed(20));
        deleteBtn.tooltip(Text.translatable(key.equals(pendingDeleteKey) ? "sypass.gui.button.delete.confirm" : "sypass.gui.button.delete.tooltip"));
        actionsLayout.child(deleteBtn);

        card.child(actionsLayout);
        return card;
    }

    // ==========================================
    // Вкладка Bitwarden
    // ==========================================
    private void buildBitwardenTab(FlowLayout root) {
        if (BitwardenManager.hasActiveSession()) {
            if (bwStage != BwStage.LOGGED_IN
                    && bwStage != BwStage.CONFIRM_LOGOUT && bwStage != BwStage.CONFIRM_DELETE_CLI) {
                bwStage = BwStage.LOGGED_IN;
            }
        } else {
            if (bwStage == BwStage.LOGGED_IN || bwStage == BwStage.CONFIRM_LOGOUT) {
                bwStage = BwStage.LOGIN;
            }
        }

        boolean cliReady = BitwardenManager.isCliInstalled();

        if (!cliReady && bwStage != BwStage.CHECKING_STATUS && bwStage != BwStage.CONFIRM_DELETE_CLI) {
            bwStage = BwStage.CLI_NOT_FOUND;
        } else if (cliReady && bwStage == BwStage.CLI_NOT_FOUND) {
            bwStage = BitwardenManager.hasActiveSession() ? BwStage.LOGGED_IN : BwStage.LOGIN;
        }

        int cardWidth = Math.min(360, this.width - 30);
        FlowLayout mainCard = Containers.verticalFlow(Sizing.fixed(cardWidth), Sizing.content());
        mainCard.gap(4);
        mainCard.horizontalAlignment(HorizontalAlignment.CENTER);
        mainCard.surface(Surface.PANEL);
        mainCard.padding(Insets.of(8));
        mainCard.margins(Insets.top(4));

        if (isProcessing) {
            String initialMsg = (statusMessage != null && !statusMessage.isBlank()) ? statusMessage : Text.translatable("sypass.gui.status.syncing").getString();
            this.processingStatusLabel = Components.label(Text.literal("§b⏳ " + initialMsg));
            this.processingStatusLabel.shadow(true).margins(Insets.vertical(6));
            mainCard.child(this.processingStatusLabel);

            FlowLayout outerBar = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(6));
            outerBar.surface(Surface.flat(0xFF222222));
            outerBar.padding(Insets.of(1));

            this.processingProgressBar = Containers.horizontalFlow(Sizing.fill(25), Sizing.fill(100));
            this.processingProgressBar.surface(Surface.flat(0xFF55FFFF));
            outerBar.child(this.processingProgressBar);
            mainCard.child(outerBar);

            root.child(mainCard);
            return;
        }

        switch (bwStage) {
            case CHECKING_STATUS -> {
                this.checkingStatusLabel = Components.label(Text.literal("§b⟳ " + Text.translatable("sypass.gui.bw.checking_status").getString()));
                this.checkingStatusLabel.shadow(true).margins(Insets.vertical(6));
                mainCard.child(this.checkingStatusLabel);

                FlowLayout outerBar = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(6));
                outerBar.surface(Surface.flat(0xFF222222));
                outerBar.padding(Insets.of(1));

                this.checkingProgressBar = Containers.horizontalFlow(Sizing.fill(20), Sizing.fill(100));
                this.checkingProgressBar.surface(Surface.flat(0xFFFFAA00));
                outerBar.child(this.checkingProgressBar);
                mainCard.child(outerBar);
            }
            case CLI_NOT_FOUND -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.not_found.title").formatted(Formatting.YELLOW, Formatting.BOLD)).shadow(true).margins(Insets.bottom(2)));

                LabelComponent desc1 = Components.label(Text.translatable("sypass.gui.bw.not_found.desc1").formatted(Formatting.GRAY));
                desc1.maxWidth(cardWidth - 20);
                mainCard.child(desc1);

                String desc2Key = BitwardenManager.IS_WINDOWS ? "sypass.gui.bw.not_found.desc2.windows" :
                        (BitwardenManager.IS_MAC ? "sypass.gui.bw.not_found.desc2.mac" : "sypass.gui.bw.not_found.desc2.linux");
                LabelComponent desc2 = Components.label(Text.translatable(desc2Key).formatted(Formatting.DARK_GRAY));
                desc2.maxWidth(cardWidth - 20);
                mainCard.child(desc2);

                ButtonComponent guideBtn = Components.button(Text.translatable("sypass.gui.bw.button.guide"), b -> {
                    ConfirmLinkScreen.open(this, "https://bitwarden.com/help/cli/", true);
                });
                guideBtn.horizontalSizing(Sizing.fill(100));
                guideBtn.margins(Insets.top(4));
                guideBtn.tooltip(Text.translatable("sypass.gui.bw.button.guide.tooltip"));
                mainCard.child(guideBtn);

                ButtonComponent openFolderBtn = Components.button(Text.translatable("sypass.gui.bw.button.open_folder"), b -> {
                    Util.getOperatingSystem().open(BitwardenManager.CONFIG_DIR.toFile());
                });
                openFolderBtn.horizontalSizing(Sizing.fill(100));
                openFolderBtn.tooltip(Text.translatable("sypass.gui.bw.button.open_folder.tooltip"));
                mainCard.child(openFolderBtn);

                ButtonComponent checkBtn = Components.button(Text.translatable("sypass.gui.bw.button.check_again"), b -> {
                    BitwardenManager.invalidateStatusCache();
                    this.cachedStatusInfo = null;
                    if (BitwardenManager.isCliInstalled()) {
                        updateBitwardenStatusAsync();
                    }
                    rebuildUI();
                });
                checkBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(checkBtn);
            }
            case LOGIN -> {
                FlowLayout modeRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                modeRow.gap(4);
                modeRow.horizontalAlignment(HorizontalAlignment.CENTER);

                int modeBtnWidth = (cardWidth - 24) / 3;

                ButtonComponent loginModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.master_password_tab"), b -> { bwStage = BwStage.LOGIN; rebuildUI(); });
                loginModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent apiModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.apikey_tab"), b -> { bwStage = BwStage.API_KEY; rebuildUI(); });
                apiModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent sessionModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.session_tab"), b -> { bwStage = BwStage.SESSION_KEY; rebuildUI(); });
                sessionModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                modeRow.child(loginModeBtn);
                modeRow.child(apiModeBtn);
                modeRow.child(sessionModeBtn);
                mainCard.child(modeRow);

                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.login.title")).shadow(true).margins(Insets.vertical(2)));

                TextBoxComponent emailField = Components.textBox(Sizing.fill(100));
                emailField.setMaxLength(256);
                emailField.setText(savedEmail);
                emailField.setCursor(0, false);
                emailField.setPlaceholder(Text.translatable("sypass.gui.bw.login.email_placeholder"));
                emailField.onChanged().subscribe(val -> savedEmail = val.trim());
                mainCard.child(emailField);

                FlowLayout passRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                passRow.gap(4);

                TextBoxComponent passField = Components.textBox(Sizing.fixed(cardWidth - 49));
                passField.setMaxLength(256);
                passField.setText(savedPassword);
                passField.setCursor(0, false);
                passField.setPlaceholder(Text.translatable("sypass.gui.bw.login.password_placeholder"));
                applyPasswordMask(passField, showMasterPassword);
                passField.onChanged().subscribe(val -> savedPassword = val.trim());

                ButtonComponent togglePassEye = Components.button(Text.literal(showMasterPassword ? "§a●" : "§7○"), b -> {
                    showMasterPassword = !showMasterPassword;
                    b.setMessage(Text.literal(showMasterPassword ? "§a●" : "§7○"));
                    b.tooltip(Text.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));
                    applyPasswordMask(passField, showMasterPassword);
                });
                togglePassEye.horizontalSizing(Sizing.fixed(25));
                togglePassEye.tooltip(Text.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));

                passRow.child(passField);
                passRow.child(togglePassEye);
                mainCard.child(passRow);

                ButtonComponent loginBtn = Components.button(Text.translatable("sypass.gui.bw.login.button_login"), b -> handleLogin(null, null));
                loginBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(loginBtn);

                ButtonComponent registerBtn = Components.button(Text.translatable("sypass.gui.bw.login.register"), b -> {
                    ConfirmLinkScreen.open(this, "https://vault.bitwarden.com/#/register", true);
                });
                registerBtn.horizontalSizing(Sizing.fill(100));
                registerBtn.margins(Insets.top(2));
                registerBtn.tooltip(Text.translatable("sypass.gui.bw.login.register.tooltip"));
                mainCard.child(registerBtn);

                LabelComponent secNote = Components.label(Text.translatable("sypass.gui.bw.security_note").formatted(Formatting.DARK_GRAY));
                secNote.maxWidth(cardWidth - 20);
                secNote.margins(Insets.top(2));
                mainCard.child(secNote);
            }
            case OTP -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.otp.title")).color(Color.ofRgb(0xFFAA00)).shadow(true));

                // Вибір методу 2FA
                FlowLayout methodRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                methodRow.gap(6);
                methodRow.horizontalAlignment(HorizontalAlignment.CENTER);
                methodRow.margins(Insets.vertical(2));

                int methodBtnW = (cardWidth - 26) / 2;
                boolean isAuthSelected = !"1".equals(selected2faMethod);
                ButtonComponent authBtn = Components.button(
                        Text.translatable("sypass.gui.bw.otp.method_authenticator")
                                .formatted(isAuthSelected ? Formatting.YELLOW : Formatting.GRAY),
                        b -> {
                            selected2faMethod = "0";
                            rebuildUI();
                        }
                );
                authBtn.horizontalSizing(Sizing.fixed(methodBtnW));

                ButtonComponent emailBtn = Components.button(
                        Text.translatable("sypass.gui.bw.otp.method_email")
                                .formatted(!isAuthSelected ? Formatting.YELLOW : Formatting.GRAY),
                        b -> {
                            selected2faMethod = "1";
                            rebuildUI();
                        }
                );
                emailBtn.horizontalSizing(Sizing.fixed(methodBtnW));

                methodRow.child(authBtn);
                methodRow.child(emailBtn);
                mainCard.child(methodRow);

                TextBoxComponent otpField = Components.textBox(Sizing.fill(100));
                otpField.setMaxLength(32);
                otpField.setPlaceholder(Text.translatable("sypass.gui.bw.otp.placeholder"));
                mainCard.child(otpField);

                ButtonComponent confirmOtpBtn = Components.button(Text.translatable("sypass.gui.bw.otp.confirm"), b -> {
                    String otp = otpField.getText().trim();
                    if (!otp.isEmpty()) handleLogin(otp, selected2faMethod);
                });
                confirmOtpBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(confirmOtpBtn);

                if ("1".equals(selected2faMethod)) {
                    ButtonComponent sendEmailBtn = Components.button(Text.translatable("sypass.gui.bw.otp.send_email"), b -> handleSendEmail2fa());
                    sendEmailBtn.horizontalSizing(Sizing.fill(100));
                    sendEmailBtn.tooltip(Text.translatable("sypass.gui.bw.otp.send_email_tooltip"));
                    sendEmailBtn.margins(Insets.top(2));
                    mainCard.child(sendEmailBtn);
                }

                ButtonComponent backBtn = Components.button(Text.translatable("sypass.gui.bw.otp.back"), b -> {
                    bwStage = BwStage.LOGIN;
                    statusMessage = "";
                    rebuildUI();
                });
                backBtn.horizontalSizing(Sizing.fill(100));
                backBtn.margins(Insets.top(2));
                mainCard.child(backBtn);
            }
            case API_KEY -> {
                FlowLayout modeRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                modeRow.gap(4);
                modeRow.horizontalAlignment(HorizontalAlignment.CENTER);

                int modeBtnWidth = (cardWidth - 24) / 3;

                ButtonComponent loginModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.master_password_tab"), b -> { bwStage = BwStage.LOGIN; rebuildUI(); });
                loginModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent apiModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.apikey_tab"), b -> { bwStage = BwStage.API_KEY; rebuildUI(); });
                apiModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent sessionModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.session_tab"), b -> { bwStage = BwStage.SESSION_KEY; rebuildUI(); });
                sessionModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                modeRow.child(loginModeBtn);
                modeRow.child(apiModeBtn);
                modeRow.child(sessionModeBtn);
                mainCard.child(modeRow);

                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.apikey.title")).shadow(true).margins(Insets.vertical(2)));

                TextBoxComponent clientIdField = Components.textBox(Sizing.fill(100));
                clientIdField.setMaxLength(256);
                clientIdField.setPlaceholder(Text.translatable("sypass.gui.bw.apikey.client_id"));
                mainCard.child(clientIdField);

                TextBoxComponent clientSecretField = Components.textBox(Sizing.fill(100));
                clientSecretField.setMaxLength(256);
                clientSecretField.setPlaceholder(Text.translatable("sypass.gui.bw.apikey.client_secret"));
                mainCard.child(clientSecretField);

                FlowLayout passRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                passRow.gap(4);

                TextBoxComponent masterPassField = Components.textBox(Sizing.fixed(cardWidth - 49));
                masterPassField.setMaxLength(256);
                masterPassField.setPlaceholder(Text.translatable("sypass.gui.bw.login.password_placeholder"));
                applyPasswordMask(masterPassField, showMasterPassword);

                ButtonComponent togglePassEye = Components.button(Text.literal(showMasterPassword ? "§a●" : "§7○"), b -> {
                    showMasterPassword = !showMasterPassword;
                    b.setMessage(Text.literal(showMasterPassword ? "§a●" : "§7○"));
                    b.tooltip(Text.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));
                    applyPasswordMask(masterPassField, showMasterPassword);
                });
                togglePassEye.horizontalSizing(Sizing.fixed(25));
                togglePassEye.tooltip(Text.translatable(showMasterPassword ? "sypass.gui.button.hide" : "sypass.gui.button.show"));

                passRow.child(masterPassField);
                passRow.child(togglePassEye);
                mainCard.child(passRow);

                ButtonComponent loginApiBtn = Components.button(Text.translatable("sypass.gui.bw.apikey.login_btn"), b -> {
                    handleApiKeyLogin(clientIdField.getText().trim(), clientSecretField.getText().trim(), masterPassField.getText().trim());
                });
                loginApiBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(loginApiBtn);

                LabelComponent secNote = Components.label(Text.translatable("sypass.gui.bw.security_note").formatted(Formatting.DARK_GRAY));
                secNote.maxWidth(cardWidth - 20);
                secNote.margins(Insets.top(2));
                mainCard.child(secNote);
            }
            case SESSION_KEY -> {
                FlowLayout modeRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                modeRow.gap(4);
                modeRow.horizontalAlignment(HorizontalAlignment.CENTER);

                int modeBtnWidth = (cardWidth - 24) / 3;

                ButtonComponent loginModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.master_password_tab"), b -> { bwStage = BwStage.LOGIN; rebuildUI(); });
                loginModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent apiModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.apikey_tab"), b -> { bwStage = BwStage.API_KEY; rebuildUI(); });
                apiModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                ButtonComponent sessionModeBtn = Components.button(Text.translatable("sypass.gui.bw.login.session_tab"), b -> { bwStage = BwStage.SESSION_KEY; rebuildUI(); });
                sessionModeBtn.horizontalSizing(Sizing.fixed(modeBtnWidth));

                modeRow.child(loginModeBtn);
                modeRow.child(apiModeBtn);
                modeRow.child(sessionModeBtn);
                mainCard.child(modeRow);

                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.session.title")).shadow(true).margins(Insets.vertical(2)));

                String altHint = Text.translatable("sypass.gui.bw.session.or").getString();
                String unlockCmd = BitwardenManager.IS_WINDOWS
                        ? (BitwardenManager.isLocalCliInstalled() ? ".\\bw.exe unlock" : "bw unlock " + altHint + " .\\bw.exe unlock")
                        : (BitwardenManager.isLocalCliInstalled() ? "./bw unlock" : "bw unlock " + altHint + " ./bw unlock");

                String loginCmd = BitwardenManager.IS_WINDOWS
                        ? (BitwardenManager.isLocalCliInstalled() ? ".\\bw.exe login" : "bw login")
                        : (BitwardenManager.isLocalCliInstalled() ? "./bw login" : "bw login");

                LabelComponent desc1 = Components.label(Text.translatable("sypass.gui.bw.session.desc1", unlockCmd).formatted(Formatting.GRAY));
                desc1.maxWidth(cardWidth - 20);
                mainCard.child(desc1);

                LabelComponent desc2 = Components.label(Text.translatable("sypass.gui.bw.session.desc2").formatted(Formatting.DARK_GREEN));
                desc2.maxWidth(cardWidth - 20);
                mainCard.child(desc2);

                TextBoxComponent sessionKeyField = Components.textBox(Sizing.fill(100));
                sessionKeyField.setMaxLength(512);
                sessionKeyField.setPlaceholder(Text.translatable("sypass.gui.bw.session.placeholder"));
                mainCard.child(sessionKeyField);

                ButtonComponent unlockBtn = Components.button(Text.translatable("sypass.gui.bw.session.unlock_btn"), b -> {
                    handleSessionKeyLogin(sessionKeyField.getText().trim());
                });
                unlockBtn.horizontalSizing(Sizing.fill(100));
                unlockBtn.margins(Insets.top(2));
                mainCard.child(unlockBtn);

                LabelComponent note = Components.label(Text.translatable("sypass.gui.bw.session.terminal_hint", loginCmd, unlockCmd).formatted(Formatting.DARK_GRAY));
                note.maxWidth(cardWidth - 20);
                note.margins(Insets.top(2));
                mainCard.child(note);
            }
            case LOGGED_IN -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.logged.connected")).shadow(true));
                if (cachedStatusInfo != null && !cachedStatusInfo.userEmail().isEmpty()) {
                    mainCard.child(Components.label(Text.translatable("sypass.gui.bw.logged.account", cachedStatusInfo.userEmail())).shadow(true));
                }

                ButtonComponent pullBtn = Components.button(Text.translatable("sypass.gui.bw.logged.pull"), b -> handlePull());
                pullBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(pullBtn);

                ButtonComponent pushBtn = Components.button(Text.translatable("sypass.gui.bw.logged.push"), b -> handlePush());
                pushBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(pushBtn);

                ButtonComponent fullSyncBtn = Components.button(Text.translatable("sypass.gui.bw.logged.full_sync"), b -> handleFullSync());
                fullSyncBtn.horizontalSizing(Sizing.fill(100));
                mainCard.child(fullSyncBtn);

                ButtonComponent logoutBtn = Components.button(Text.translatable("sypass.gui.bw.logged.logout"), b -> {
                    bwStage = BwStage.CONFIRM_LOGOUT;
                    rebuildUI();
                });
                logoutBtn.horizontalSizing(Sizing.fill(100));
                logoutBtn.tooltip(Text.translatable("sypass.gui.bw.logged.logout"));
                mainCard.child(logoutBtn);
            }
            case CONFIRM_LOGOUT -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.confirm_logout.title").formatted(Formatting.GOLD)).shadow(true).margins(Insets.bottom(4)));

                LabelComponent desc = Components.label(Text.translatable("sypass.gui.bw.confirm_logout.desc").formatted(Formatting.GRAY));
                desc.maxWidth(cardWidth - 20);
                mainCard.child(desc);

                FlowLayout confirmButtons = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                confirmButtons.gap(8);
                confirmButtons.horizontalAlignment(HorizontalAlignment.CENTER);
                confirmButtons.margins(Insets.top(6));

                int btnW = (cardWidth - 28) / 2;

                ButtonComponent yesBtn = Components.button(Text.translatable("sypass.gui.bw.confirm_logout.yes"), b -> {
                    BitwardenManager.logout();
                    this.cachedStatusInfo = null;
                    bwStage = BwStage.LOGIN;
                    statusMessage = "§e" + Text.translatable("sypass.gui.bw.logged.logout").getString();
                    rebuildUI();
                });
                yesBtn.horizontalSizing(Sizing.fixed(btnW));

                ButtonComponent noBtn = Components.button(Text.translatable("sypass.gui.bw.confirm_logout.no"), b -> {
                    bwStage = BwStage.LOGGED_IN;
                    rebuildUI();
                });
                noBtn.horizontalSizing(Sizing.fixed(btnW));

                confirmButtons.child(yesBtn);
                confirmButtons.child(noBtn);
                mainCard.child(confirmButtons);
            }
            case CONFIRM_DELETE_CLI -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.bw.confirm_delete_cli.title").formatted(Formatting.RED)).shadow(true).margins(Insets.bottom(4)));

                LabelComponent desc = Components.label(Text.translatable("sypass.gui.bw.confirm_delete_cli.desc").formatted(Formatting.GRAY));
                desc.maxWidth(cardWidth - 20);
                mainCard.child(desc);

                FlowLayout confirmButtons = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                confirmButtons.gap(8);
                confirmButtons.horizontalAlignment(HorizontalAlignment.CENTER);
                confirmButtons.margins(Insets.top(6));

                int btnW = (cardWidth - 28) / 2;

                ButtonComponent yesBtn = Components.button(Text.translatable("sypass.gui.bw.confirm_delete_cli.yes"), b -> {
                    boolean deleted = BitwardenManager.deleteLocalCli();
                    this.cachedStatusInfo = null;
                    this.bwStage = BwStage.CLI_NOT_FOUND;
                    statusMessage = deleted ? "§e" + Text.translatable("sypass.gui.bw.delete_local_cli").getString() : "§c" + Text.translatable("sypass.gui.bw.error.generic", "Delete failed").getString();
                    rebuildUI();
                });
                yesBtn.horizontalSizing(Sizing.fixed(btnW));

                ButtonComponent noBtn = Components.button(Text.translatable("sypass.gui.bw.confirm_delete_cli.no"), b -> {
                    bwStage = (preConfirmStage != null && preConfirmStage != BwStage.CONFIRM_DELETE_CLI) ? preConfirmStage : BwStage.LOGIN;
                    rebuildUI();
                });
                noBtn.horizontalSizing(Sizing.fixed(btnW));

                confirmButtons.child(yesBtn);
                confirmButtons.child(noBtn);
                mainCard.child(confirmButtons);
            }
        }

        if (BitwardenManager.isLocalCliInstalled() && bwStage != BwStage.CLI_NOT_FOUND
                && bwStage != BwStage.CONFIRM_LOGOUT && bwStage != BwStage.CONFIRM_DELETE_CLI) {
            ButtonComponent deleteCliBtn = Components.button(
                    Text.translatable("sypass.gui.bw.delete_local_cli"),
                    b -> {
                        preConfirmStage = bwStage;
                        bwStage = BwStage.CONFIRM_DELETE_CLI;
                        rebuildUI();
                    }
            );
            deleteCliBtn.horizontalSizing(Sizing.fill(100));
            deleteCliBtn.margins(Insets.top(2));
            deleteCliBtn.tooltip(Text.translatable("sypass.gui.bw.delete_local_cli"));
            mainCard.child(deleteCliBtn);
        }

        root.child(mainCard);

        if (!statusMessage.isEmpty()) {
            root.child(Components.label(Text.literal(statusMessage)).shadow(true).margins(Insets.top(3)));
        }

        ButtonComponent closeBtn = Components.button(Text.translatable("sypass.gui.button.close"), b -> this.close());
        closeBtn.horizontalSizing(Sizing.fixed(160));
        closeBtn.margins(Insets.top(4));
        root.child(closeBtn);
    }

    private void applyPasswordMask(TextBoxComponent field, boolean showPassword) {
        if (showPassword) {
            field.setRenderTextProvider((text, index) -> OrderedText.styledForwardsVisitedString(text, Style.EMPTY));
        } else {
            field.setRenderTextProvider((text, index) -> OrderedText.styledForwardsVisitedString("•".repeat(text.length()), Style.EMPTY));
        }
    }

    private void updateBitwardenStatusAsync() {
        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwStatusInfo info = BitwardenManager.getStatusInfo();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.cachedStatusInfo = info;
                    if (!info.isInstalled()) {
                        this.bwStage = BwStage.CLI_NOT_FOUND;
                    } else if (info.isUnlocked() && BitwardenManager.hasActiveSession()) {
                        if (this.bwStage != BwStage.CONFIRM_LOGOUT && this.bwStage != BwStage.CONFIRM_DELETE_CLI) {
                            this.bwStage = BwStage.LOGGED_IN;
                        }
                    } else {
                        if (this.bwStage == BwStage.CHECKING_STATUS || this.bwStage == BwStage.LOGGED_IN || this.bwStage == BwStage.CONFIRM_LOGOUT) {
                            this.bwStage = BwStage.LOGIN;
                        }
                    }
                    rebuildUI();
                });
            }
        });
    }

    private void handleSendEmail2fa() {
        if (savedEmail.isEmpty() || savedPassword.isEmpty()) return;
        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.sendEmail2faCode(savedEmail, savedPassword);
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = "§a" + Text.translatable("sypass.gui.bw.otp.email_sent").getString();
                    rebuildUI();
                });
            }
        });
    }

    private void handleDeleteFromBitwarden(String serverIp, String username, PasswordManager.AccountData data) {
        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.deleteFromBitwardenOnlyAsync(serverIp, username, data.remoteId()).thenAccept(success -> {
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    if (success) {
                        this.statusMessage = Text.translatable("sypass.gui.status.deleted_bw", username, serverIp).getString();
                    } else {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.status.deleted_bw_failed", username).getString();
                    }
                    refreshPasswordList();
                    rebuildUI();
                });
            }
        });
    }

    private void handleLogin(String otp, String method) {
        if (savedEmail.isEmpty() || savedPassword.isEmpty()) {
            this.statusMessage = "§c" + Text.translatable("sypass.gui.edit.error.pass").getString();
            rebuildUI();
            return;
        }

        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwLoginResponse response = BitwardenManager.login(savedEmail, savedPassword, otp, method);
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
                            if (response.message().toLowerCase().contains("email")) {
                                this.selected2faMethod = "1";
                            }
                            this.statusMessage = "§6" + response.message();
                        }
                        case INVALID_OTP -> {
                            this.bwStage = BwStage.OTP;
                            this.statusMessage = "§c" + response.message();
                        }
                        default -> {
                            if (otp != null && !otp.isBlank()) {
                                this.bwStage = BwStage.OTP;
                            } else {
                                this.bwStage = BwStage.LOGIN;
                            }
                            this.statusMessage = "§c" + response.message();
                        }
                    }
                    rebuildUI();
                });
            }
        });
    }

    private void handleApiKeyLogin(String clientId, String clientSecret, String masterPass) {
        if (clientId.isEmpty() || clientSecret.isEmpty() || masterPass.isEmpty()) {
            this.statusMessage = "§c" + Text.translatable("sypass.gui.edit.error.pass").getString();
            rebuildUI();
            return;
        }

        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
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
                    rebuildUI();
                });
            }
        });
    }

    private void handleSessionKeyLogin(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            this.statusMessage = "§c" + Text.translatable("sypass.gui.bw.error.empty_session").getString();
            rebuildUI();
            return;
        }

        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwLoginResponse response = BitwardenManager.loginWithSessionKey(sessionKey);
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
                    rebuildUI();
                });
            }
        });
    }

    private void handlePull() {
        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwSyncResult result = BitwardenManager.pullFromBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = result.success() ? "§a" + result.message() : "§c" + result.message();
                    if (!BitwardenManager.hasActiveSession()) {
                        this.bwStage = BwStage.LOGIN;
                    }
                    refreshPasswordList();
                    rebuildUI();
                });
            }
        });
    }

    private void handlePush() {
        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwSyncResult result = BitwardenManager.pushToBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    this.statusMessage = result.success() ? "§a" + result.message() : "§c" + result.message();
                    if (!BitwardenManager.hasActiveSession()) {
                        this.bwStage = BwStage.LOGIN;
                    }
                    rebuildUI();
                });
            }
        });
    }

    private void handleFullSync() {
        if (!com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled()) {
            return;
        }

        // Перевірка: чи залогінений користувач
        if (!BitwardenManager.hasActiveSession()) {
            this.bwStage = BwStage.LOGIN;
            this.statusMessage = "§c" + Text.translatable("sypass.gui.status.not_logged_in").getString();
            refreshPasswordList();
            rebuildUI();
            return;
        }

        this.isProcessing = true;
        this.statusMessage = Text.translatable("sypass.gui.status.syncing").getString();
        rebuildUI();

        BitwardenManager.getExecutor().execute(() -> {
            BitwardenManager.BwSyncResult pullRes = BitwardenManager.pullFromBitwarden();
            if (!pullRes.success()) {
                if (this.client != null) {
                    this.client.execute(() -> {
                        this.isProcessing = false;
                        this.statusMessage = "§c" + pullRes.message();
                        if (!BitwardenManager.hasActiveSession()) {
                            this.bwStage = BwStage.LOGIN;
                        }
                        refreshPasswordList();
                        rebuildUI();
                    });
                }
                return;
            }

            BitwardenManager.BwSyncResult pushRes = BitwardenManager.pushToBitwarden();
            if (this.client != null) {
                this.client.execute(() -> {
                    this.isProcessing = false;
                    if (pushRes.success()) {
                        int total = pullRes.count() + pushRes.count();
                        this.statusMessage = "§a" + Text.translatable("sypass.gui.status.sync_success", pullRes.count(), pushRes.count()).getString();
                        SYPassToast.show(
                                Text.translatable("sypass.toast.sync.title"),
                                Text.translatable("sypass.toast.sync.success", total)
                        );
                    } else {
                        this.statusMessage = "§c" + pushRes.message();
                        if (!BitwardenManager.hasActiveSession()) {
                            this.bwStage = BwStage.LOGIN;
                        }
                    }
                    refreshPasswordList();
                    rebuildUI();
                });
            }
        });
    }

    private void buildSettingsTab(FlowLayout root) {
        int cardWidth = Math.min(420, this.width - 24);
        int colWidth = (cardWidth - 28) / 2;

        FlowLayout mainCard = Containers.verticalFlow(Sizing.fixed(cardWidth), Sizing.content());
        mainCard.gap(5);
        mainCard.horizontalAlignment(HorizontalAlignment.CENTER);
        mainCard.surface(Surface.PANEL);
        mainCard.padding(Insets.of(8));
        mainCard.margins(Insets.top(4));

        switch (settingsStage) {
            case MAIN -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.settings.title").formatted(Formatting.GOLD, Formatting.BOLD)).shadow(true).margins(Insets.bottom(2)));

                // Рядок 1: Авто-вхід (ліворуч) та Сповіщення (праворуч)
                FlowLayout row1 = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                row1.gap(8);
                row1.horizontalAlignment(HorizontalAlignment.CENTER);

                boolean autoLogin = com.syntren.sypass.config.SYPassConfig.isAutoLoginEnabled();
                ButtonComponent autoLoginToggle = Components.button(
                        Text.translatable("sypass.gui.settings.autologin", autoLogin ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isAutoLoginEnabled();
                            com.syntren.sypass.config.SYPassConfig.setAutoLoginEnabled(newVal);
                            b.setMessage(Text.translatable("sypass.gui.settings.autologin", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                        }
                );
                autoLoginToggle.horizontalSizing(Sizing.fixed(colWidth));
                autoLoginToggle.tooltip(Text.translatable("sypass.gui.settings.autologin.tooltip"));

                boolean toasts = com.syntren.sypass.config.SYPassConfig.isToastsEnabled();
                ButtonComponent toastsToggle = Components.button(
                        Text.translatable("sypass.gui.settings.toasts", toasts ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isToastsEnabled();
                            com.syntren.sypass.config.SYPassConfig.setToastsEnabled(newVal);
                            b.setMessage(Text.translatable("sypass.gui.settings.toasts", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                        }
                );
                toastsToggle.horizontalSizing(Sizing.fixed(colWidth));
                toastsToggle.tooltip(Text.translatable("sypass.gui.settings.toasts.tooltip"));

                row1.child(autoLoginToggle);
                row1.child(toastsToggle);
                mainCard.child(row1);

                // Рядок 2: Розумний авто-вхід (ліворуч) та Розумна авто-реєстрація (праворуч)
                FlowLayout row2 = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                row2.gap(8);
                row2.horizontalAlignment(HorizontalAlignment.CENTER);

                boolean smartLogin = com.syntren.sypass.config.SYPassConfig.isSmartAutoLoginEnabled();
                ButtonComponent smartLoginToggle = Components.button(
                        Text.translatable("sypass.gui.settings.smart_login", smartLogin ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isSmartAutoLoginEnabled();
                            com.syntren.sypass.config.SYPassConfig.setSmartAutoLoginEnabled(newVal);
                            b.setMessage(Text.translatable("sypass.gui.settings.smart_login", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                        }
                );
                smartLoginToggle.horizontalSizing(Sizing.fixed(colWidth));
                smartLoginToggle.tooltip(Text.translatable("sypass.gui.settings.smart_login.tooltip"));

                boolean smartRegister = com.syntren.sypass.config.SYPassConfig.isSmartAutoRegisterEnabled();
                ButtonComponent smartRegisterToggle = Components.button(
                        Text.translatable("sypass.gui.settings.smart_register", smartRegister ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isSmartAutoRegisterEnabled();
                            com.syntren.sypass.config.SYPassConfig.setSmartAutoRegisterEnabled(newVal);
                            b.setMessage(Text.translatable("sypass.gui.settings.smart_register", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                        }
                );
                smartRegisterToggle.horizontalSizing(Sizing.fixed(colWidth));
                smartRegisterToggle.tooltip(Text.translatable("sypass.gui.settings.smart_register.tooltip"));

                row2.child(smartLoginToggle);
                row2.child(smartRegisterToggle);
                mainCard.child(row2);

                // Рядок 3: Захист перезапису (ліворуч) та Захист чату (праворуч)
                FlowLayout row3 = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                row3.gap(8);
                row3.horizontalAlignment(HorizontalAlignment.CENTER);

                boolean protectOverwrite = com.syntren.sypass.config.SYPassConfig.isPreventRegisterOverwriteEnabled();
                ButtonComponent protectOverwriteToggle = Components.button(
                        Text.translatable("sypass.gui.settings.prevent_overwrite", protectOverwrite ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isPreventRegisterOverwriteEnabled();
                            com.syntren.sypass.config.SYPassConfig.setPreventRegisterOverwriteEnabled(newVal);
                            b.setMessage(Text.translatable("sypass.gui.settings.prevent_overwrite", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                        }
                );
                protectOverwriteToggle.horizontalSizing(Sizing.fixed(colWidth));
                protectOverwriteToggle.tooltip(Text.translatable("sypass.gui.settings.prevent_overwrite.tooltip"));

                boolean chatProtect = com.syntren.sypass.config.SYPassConfig.isChatLeakProtectionEnabled();
                ButtonComponent chatProtectToggle = Components.button(
                        Text.translatable("sypass.gui.settings.chat_protect", chatProtect ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isChatLeakProtectionEnabled();
                            com.syntren.sypass.config.SYPassConfig.setChatLeakProtectionEnabled(newVal);
                            rebuildUI();
                        }
                );
                chatProtectToggle.horizontalSizing(Sizing.fixed(colWidth));
                chatProtectToggle.tooltip(Text.translatable("sypass.gui.settings.chat_protect.tooltip"));

                row3.child(protectOverwriteToggle);
                row3.child(chatProtectToggle);
                mainCard.child(row3);

                // Підналаштування області валідації (відображається, коли захист увімкнено)
                if (chatProtect) {
                    FlowLayout scopeRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                    scopeRow.horizontalAlignment(HorizontalAlignment.CENTER);

                    SYPassConfig.ChatProtectionScope scope = com.syntren.sypass.config.SYPassConfig.getChatProtectionScope();
                    ButtonComponent scopeToggle = Components.button(
                            Text.translatable("sypass.gui.settings.chat_scope", Text.translatable(scope.getTranslationKey())),
                            b -> {
                                SYPassConfig.ChatProtectionScope newScope = (scope == SYPassConfig.ChatProtectionScope.ALL_SERVERS)
                                        ? SYPassConfig.ChatProtectionScope.CURRENT_SERVER
                                        : SYPassConfig.ChatProtectionScope.ALL_SERVERS;
                                com.syntren.sypass.config.SYPassConfig.setChatProtectionScope(newScope);
                                rebuildUI();
                            }
                    );
                    scopeToggle.horizontalSizing(Sizing.fill(100));
                    scopeToggle.tooltip(Text.translatable("sypass.gui.settings.chat_scope.tooltip"));
                    scopeRow.child(scopeToggle);
                    mainCard.child(scopeRow);
                }

                // Рядок 4: Затримка авто-входу (ліворуч) та Швидкі утиліти (праворуч)
                FlowLayout row4 = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
                row4.gap(8);
                row4.horizontalAlignment(HorizontalAlignment.CENTER);
                row4.verticalAlignment(VerticalAlignment.CENTER);

                // Колонка затримки
                FlowLayout delayCol = Containers.verticalFlow(Sizing.fixed(colWidth), Sizing.content());
                delayCol.gap(2);

                int delayTicks = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                float delaySec = delayTicks / 20.0f;
                LabelComponent delayLabel = Components.label(Text.translatable("sypass.gui.settings.delay", delayTicks, String.format("%.1f", delaySec)));
                delayLabel.shadow(true);
                delayCol.child(delayLabel);

                FlowLayout delayButtons = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                delayButtons.gap(2);
                int dBtnW = (colWidth - 6) / 4;

                ButtonComponent minus10 = Components.button(Text.literal("-10t"), b -> {
                    int cur = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    com.syntren.sypass.config.SYPassConfig.setAutoLoginDelayTicks(cur - 10);
                    int updated = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    delayLabel.text(Text.translatable("sypass.gui.settings.delay", updated, String.format("%.1f", updated / 20.0f)));
                });
                minus10.horizontalSizing(Sizing.fixed(dBtnW));

                ButtonComponent minus5 = Components.button(Text.literal("-5t"), b -> {
                    int cur = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    com.syntren.sypass.config.SYPassConfig.setAutoLoginDelayTicks(cur - 5);
                    int updated = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    delayLabel.text(Text.translatable("sypass.gui.settings.delay", updated, String.format("%.1f", updated / 20.0f)));
                });
                minus5.horizontalSizing(Sizing.fixed(dBtnW));

                ButtonComponent plus5 = Components.button(Text.literal("+5t"), b -> {
                    int cur = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    com.syntren.sypass.config.SYPassConfig.setAutoLoginDelayTicks(cur + 5);
                    int updated = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    delayLabel.text(Text.translatable("sypass.gui.settings.delay", updated, String.format("%.1f", updated / 20.0f)));
                });
                plus5.horizontalSizing(Sizing.fixed(dBtnW));

                ButtonComponent plus10 = Components.button(Text.literal("+10t"), b -> {
                    int cur = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    com.syntren.sypass.config.SYPassConfig.setAutoLoginDelayTicks(cur + 10);
                    int updated = com.syntren.sypass.config.SYPassConfig.getAutoLoginDelayTicks();
                    delayLabel.text(Text.translatable("sypass.gui.settings.delay", updated, String.format("%.1f", updated / 20.0f)));
                });
                plus10.horizontalSizing(Sizing.fixed(dBtnW));

                delayButtons.child(minus10);
                delayButtons.child(minus5);
                delayButtons.child(plus5);
                delayButtons.child(plus10);
                delayCol.child(delayButtons);

                // Колонка довжини пароля
                FlowLayout passLenCol = Containers.verticalFlow(Sizing.fixed(colWidth), Sizing.content());
                passLenCol.gap(2);

                int curLen = com.syntren.sypass.config.SYPassConfig.getDefaultPasswordLength();
                LabelComponent passLenLabel = Components.label(Text.translatable("sypass.gui.settings.pass_len", curLen));
                passLenLabel.shadow(true);
                passLenCol.child(passLenLabel);

                FlowLayout lenButtons = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                lenButtons.gap(2);
                int lBtnW = (colWidth - 6) / 4;

                ButtonComponent minus4 = Components.button(Text.literal("-4"), b -> {
                    int updated = Math.max(6, com.syntren.sypass.config.SYPassConfig.getDefaultPasswordLength() - 4);
                    com.syntren.sypass.config.SYPassConfig.setDefaultPasswordLength(updated);
                    passLenLabel.text(Text.translatable("sypass.gui.settings.pass_len", updated));
                });
                minus4.horizontalSizing(Sizing.fixed(lBtnW));

                ButtonComponent minus1 = Components.button(Text.literal("-1"), b -> {
                    int updated = Math.max(6, com.syntren.sypass.config.SYPassConfig.getDefaultPasswordLength() - 1);
                    com.syntren.sypass.config.SYPassConfig.setDefaultPasswordLength(updated);
                    passLenLabel.text(Text.translatable("sypass.gui.settings.pass_len", updated));
                });
                minus1.horizontalSizing(Sizing.fixed(lBtnW));

                ButtonComponent plus1 = Components.button(Text.literal("+1"), b -> {
                    int updated = Math.min(64, com.syntren.sypass.config.SYPassConfig.getDefaultPasswordLength() + 1);
                    com.syntren.sypass.config.SYPassConfig.setDefaultPasswordLength(updated);
                    passLenLabel.text(Text.translatable("sypass.gui.settings.pass_len", updated));
                });
                plus1.horizontalSizing(Sizing.fixed(lBtnW));

                ButtonComponent plus4 = Components.button(Text.literal("+4"), b -> {
                    int updated = Math.min(64, com.syntren.sypass.config.SYPassConfig.getDefaultPasswordLength() + 4);
                    com.syntren.sypass.config.SYPassConfig.setDefaultPasswordLength(updated);
                    passLenLabel.text(Text.translatable("sypass.gui.settings.pass_len", updated));
                });
                plus4.horizontalSizing(Sizing.fixed(lBtnW));

                lenButtons.child(minus4);
                lenButtons.child(minus1);
                lenButtons.child(plus1);
                lenButtons.child(plus4);
                passLenCol.child(lenButtons);

                row4.child(delayCol);
                row4.child(passLenCol);
                mainCard.child(row4);

                // Рядок 5: Підменю (Резервні копії ліворуч, Bitwarden праворуч)
                FlowLayout row5 = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                row5.gap(8);
                row5.horizontalAlignment(HorizontalAlignment.CENTER);
                row5.margins(Insets.top(2));

                ButtonComponent backupMenuBtn = Components.button(
                        Text.translatable("sypass.gui.settings.backup.menu_btn"),
                        b -> {
                            settingsStage = SettingsStage.BACKUP;
                            rebuildUI();
                        }
                );
                backupMenuBtn.horizontalSizing(Sizing.fixed(colWidth));
                backupMenuBtn.tooltip(Text.translatable("sypass.gui.settings.backup.menu_btn.tooltip"));

                boolean bwEnabled = com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled();
                Text bwStatus = bwEnabled
                        ? Text.translatable("sypass.gui.settings.on").formatted(Formatting.GREEN)
                        : Text.translatable("sypass.gui.settings.off").formatted(Formatting.GRAY);
                ButtonComponent bwMenuBtn = Components.button(
                        Text.translatable("sypass.gui.settings.bw.menu_btn", bwStatus),
                        b -> {
                            settingsStage = SettingsStage.BITWARDEN;
                            rebuildUI();
                        }
                );
                bwMenuBtn.horizontalSizing(Sizing.fixed(colWidth));
                bwMenuBtn.tooltip(Text.translatable("sypass.gui.settings.bw.menu_btn.tooltip"));

                row5.child(backupMenuBtn);
                row5.child(bwMenuBtn);
                mainCard.child(row5);

                // Відкрити папку config/sypass
                ButtonComponent openFolderBtn = Components.button(Text.translatable("sypass.gui.bw.button.open_folder"), b -> {
                    Util.getOperatingSystem().open(BitwardenManager.CONFIG_DIR.toFile());
                });
                openFolderBtn.horizontalSizing(Sizing.fill(100));
                openFolderBtn.tooltip(Text.translatable("sypass.gui.bw.button.open_folder.tooltip"));
                openFolderBtn.margins(Insets.top(2));
                mainCard.child(openFolderBtn);
            }
            case BITWARDEN -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.settings.bw.title").formatted(Formatting.GOLD, Formatting.BOLD)).shadow(true).margins(Insets.bottom(2)));

                LabelComponent desc = Components.label(Text.translatable("sypass.gui.settings.bw.desc").formatted(Formatting.GRAY));
                desc.maxWidth(cardWidth - 20);
                mainCard.child(desc);

                boolean bwEnabled = com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled();
                ButtonComponent enableBwToggle = Components.button(
                        Text.translatable("sypass.gui.settings.enable_bw", bwEnabled ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                        b -> {
                            boolean newVal = !com.syntren.sypass.config.SYPassConfig.isBitwardenEnabled();
                            com.syntren.sypass.config.SYPassConfig.setBitwardenEnabled(newVal);
                            if (!newVal && activeTab == Tab.BITWARDEN) {
                                activeTab = Tab.LOCAL_PASSWORDS;
                            }
                            rebuildUI();
                        }
                );
                enableBwToggle.horizontalSizing(Sizing.fill(100));
                enableBwToggle.tooltip(Text.translatable("sypass.gui.settings.enable_bw.tooltip"));
                mainCard.child(enableBwToggle);

                if (bwEnabled) {
                    boolean autoSync = com.syntren.sypass.config.SYPassConfig.isAutoSyncEnabled();
                    ButtonComponent syncToggle = Components.button(
                            Text.translatable("sypass.gui.settings.autosync", autoSync ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()),
                            b -> {
                                boolean newVal = !com.syntren.sypass.config.SYPassConfig.isAutoSyncEnabled();
                                com.syntren.sypass.config.SYPassConfig.setAutoSyncEnabled(newVal);
                                b.setMessage(Text.translatable("sypass.gui.settings.autosync", newVal ? "§a" + Text.translatable("sypass.gui.settings.on").getString() : "§c" + Text.translatable("sypass.gui.settings.off").getString()));
                            }
                    );
                    syncToggle.horizontalSizing(Sizing.fill(100));
                    syncToggle.tooltip(Text.translatable("sypass.gui.settings.autosync.tooltip"));
                    mainCard.child(syncToggle);

                    LabelComponent serverUrlLabel = Components.label(Text.translatable("sypass.gui.settings.server_url"));
                    serverUrlLabel.shadow(true).margins(Insets.top(2));
                    mainCard.child(serverUrlLabel);

                    FlowLayout serverRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                    serverRow.gap(4);

                    TextBoxComponent serverUrlField = Components.textBox(Sizing.fixed(cardWidth - 44));
                    serverUrlField.setMaxLength(256);
                    serverUrlField.setText(com.syntren.sypass.config.SYPassConfig.getCustomServerUrl());
                    serverUrlField.setPlaceholder(Text.translatable("sypass.gui.settings.server_url.placeholder"));
                    serverUrlField.tooltip(Text.translatable("sypass.gui.settings.server_url.tooltip"));

                    ButtonComponent saveServerBtn = Components.button(Text.literal("✔"), b -> {
                        String url = serverUrlField.getText().trim();
                        com.syntren.sypass.config.SYPassConfig.setCustomServerUrl(url);
                        BitwardenManager.configureServer(url);
                        this.statusMessage = "§a" + Text.translatable("sypass.gui.settings.server_url.saved").getString();
                        rebuildUI();
                    });
                    saveServerBtn.horizontalSizing(Sizing.fixed(24));
                    saveServerBtn.tooltip(Text.translatable("sypass.gui.settings.server_url.save"));

                    serverRow.child(serverUrlField);
                    serverRow.child(saveServerBtn);
                    mainCard.child(serverRow);
                }

                ButtonComponent openFolderBwBtn = Components.button(Text.translatable("sypass.gui.bw.button.open_folder"), b -> {
                    Util.getOperatingSystem().open(BitwardenManager.CONFIG_DIR.toFile());
                });
                openFolderBwBtn.horizontalSizing(Sizing.fill(100));
                openFolderBwBtn.margins(Insets.top(2));
                openFolderBwBtn.tooltip(Text.translatable("sypass.gui.bw.button.open_folder.tooltip"));
                mainCard.child(openFolderBwBtn);

                ButtonComponent backBtn = Components.button(Text.translatable("sypass.gui.bw.otp.back"), b -> {
                    settingsStage = SettingsStage.MAIN;
                    rebuildUI();
                });
                backBtn.horizontalSizing(Sizing.fill(100));
                backBtn.margins(Insets.top(4));
                mainCard.child(backBtn);
            }
            case BACKUP -> {
                mainCard.child(Components.label(Text.translatable("sypass.gui.settings.backup.title").formatted(Formatting.GOLD, Formatting.BOLD)).shadow(true).margins(Insets.bottom(2)));

                LabelComponent desc = Components.label(Text.translatable("sypass.gui.settings.backup.desc").formatted(Formatting.GRAY));
                desc.maxWidth(cardWidth - 20);
                mainCard.child(desc);

                TextBoxComponent backupPassField = Components.textBox(Sizing.fill(100));
                backupPassField.setMaxLength(128);
                backupPassField.setPlaceholder(Text.translatable("sypass.gui.settings.backup.pass_placeholder"));
                backupPassField.tooltip(Text.translatable("sypass.gui.settings.backup.pass_tooltip"));
                if (!this.exportAsCsv) {
                    mainCard.child(backupPassField);
                }

                FlowLayout csvRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
                csvRow.verticalAlignment(VerticalAlignment.CENTER);
                csvRow.gap(4);
                csvRow.margins(Insets.vertical(2));

                CheckboxComponent csvCheckbox = Components.checkbox(Text.translatable("sypass.gui.settings.backup.csv_checkbox"));
                csvCheckbox.checked(this.exportAsCsv);
                csvCheckbox.onChanged(checked -> {
                    this.exportAsCsv = checked;
                    rebuildUI();
                });
                csvRow.child(csvCheckbox);
                mainCard.child(csvRow);

                if (this.exportAsCsv) {
                    LabelComponent warning = Components.label(Text.translatable("sypass.gui.settings.backup.csv_warning").formatted(Formatting.YELLOW));
                    warning.maxWidth(cardWidth - 20);
                    warning.margins(Insets.bottom(2));
                    mainCard.child(warning);
                }

                FlowLayout backupButtons = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
                backupButtons.gap(4);
                backupButtons.horizontalAlignment(HorizontalAlignment.CENTER);

                int backupBtnW = (cardWidth - 28) / 3;

                ButtonComponent exportBtn = Components.button(Text.translatable("sypass.gui.settings.backup.export"), b -> {
                    if (this.exportAsCsv) {
                        String name = PasswordManager.exportBitwardenCsv();
                        if (name != null) {
                            this.statusMessage = "§e" + Text.translatable("sypass.gui.settings.backup.exported_csv", name).getString();
                        } else {
                            this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.export_failed").getString();
                        }
                    } else {
                        String pass = backupPassField.getText().trim();
                        String name = PasswordManager.exportBackup(pass);
                        if (name != null) {
                            if (!pass.isEmpty()) {
                                this.statusMessage = "§a" + Text.translatable("sypass.gui.settings.backup.exported_with_pass", name).getString();
                            } else {
                                this.statusMessage = "§e" + Text.translatable("sypass.gui.settings.backup.exported", name).getString();
                            }
                        } else {
                            this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.export_failed").getString();
                        }
                    }
                    rebuildUI();
                });
                exportBtn.horizontalSizing(Sizing.fixed(backupBtnW));

                ButtonComponent importBtn = Components.button(Text.translatable("sypass.gui.settings.backup.import"), b -> {
                    int res;
                    if (this.exportAsCsv) {
                        res = PasswordManager.importLatestCsvBackup();
                    } else {
                        String pass = backupPassField.getText().trim();
                        res = PasswordManager.importLatestBackup(pass);
                    }
                    if (res >= 0) {
                        this.statusMessage = "§a" + Text.translatable("sypass.gui.settings.backup.imported", res).getString();
                        refreshPasswordList();
                    } else if (res == -1) {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.not_found").getString();
                    } else if (res == -3) {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.pass_required").getString();
                    } else if (res == -4) {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.wrong_pass").getString();
                    } else if (res == -5) {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.key_mismatch").getString();
                    } else {
                        this.statusMessage = "§c" + Text.translatable("sypass.gui.settings.backup.import_failed").getString();
                    }
                    rebuildUI();
                });
                importBtn.horizontalSizing(Sizing.fixed(backupBtnW));

                ButtonComponent openBackupsBtn = Components.button(Text.translatable("sypass.gui.settings.backup.open_folder"), b -> {
                    Path bDir = BitwardenManager.CONFIG_DIR.resolve("backups");
                    try {
                        if (!Files.exists(bDir)) Files.createDirectories(bDir);
                    } catch (Exception ignored) {}
                    Util.getOperatingSystem().open(bDir.toFile());
                });
                openBackupsBtn.horizontalSizing(Sizing.fixed(backupBtnW));

                backupButtons.child(exportBtn);
                backupButtons.child(importBtn);
                backupButtons.child(openBackupsBtn);
                mainCard.child(backupButtons);

                ButtonComponent backBtn = Components.button(Text.translatable("sypass.gui.bw.otp.back"), b -> {
                    settingsStage = SettingsStage.MAIN;
                    rebuildUI();
                });
                backBtn.horizontalSizing(Sizing.fill(100));
                backBtn.margins(Insets.top(4));
                mainCard.child(backBtn);
            }
        }

        root.child(mainCard);

        if (!statusMessage.isEmpty()) {
            root.child(Components.label(Text.literal(statusMessage)).shadow(true).margins(Insets.top(3)));
        }

        ButtonComponent closeBtn = Components.button(Text.translatable("sypass.gui.button.close"), b -> this.close());
        closeBtn.horizontalSizing(Sizing.fixed(160));
        closeBtn.margins(Insets.top(4));
        root.child(closeBtn);
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        int dotCount = (int) ((now / 350) % 4);

        if (dotCount != lastDotCount) {
            lastDotCount = dotCount;
            String dots = ".".repeat(dotCount);

            if (bwStage == BwStage.CHECKING_STATUS && checkingStatusLabel != null) {
                checkingStatusLabel.text(Text.literal("§b⟳ " + Text.translatable("sypass.gui.bw.checking_status").getString() + dots));
            }

            if (isProcessing && processingStatusLabel != null) {
                if (!java.util.Objects.equals(lastRawStatusMessage, statusMessage)) {
                    lastRawStatusMessage = statusMessage;
                    String raw = (statusMessage != null && !statusMessage.isBlank())
                            ? statusMessage
                            : Text.translatable("sypass.gui.status.syncing").getString();
                    cachedBaseStatus = STATUS_SUFFIX_DOTS_PATTERN.matcher(STATUS_PREFIX_PATTERN.matcher(raw).replaceAll("")).replaceAll("").trim();
                }
                processingStatusLabel.text(Text.literal("§b⏳ " + cachedBaseStatus + dots));
            }
        }

        if (bwStage == BwStage.CHECKING_STATUS && checkingProgressBar != null) {
            int progress = (int) (15 + 85 * Math.abs(Math.sin((now % 1600) / 1600.0 * Math.PI)));
            checkingProgressBar.horizontalSizing(Sizing.fill(Math.max(5, Math.min(100, progress))));
        }

        if (isProcessing && processingProgressBar != null) {
            int progress = (int) (20 + 80 * Math.abs(Math.sin(((now + 400) % 1500) / 1500.0 * Math.PI)));
            processingProgressBar.horizontalSizing(Sizing.fill(Math.max(5, Math.min(100, progress))));
        }
    }

    @Override
    public void close() {
        this.savedPassword = "";
        this.savedEmail = "";
        this.revealedPasswords.clear();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}