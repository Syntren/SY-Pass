package com.syntren.sypass.gui;

import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldIcon;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public class ServerIconManager {
    public static final Identifier UNKNOWN_SERVER = Identifier.ofVanilla("textures/misc/unknown_server.png");

    private static final Map<String, WorldIcon> ICON_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NO_ICON_SET = ConcurrentHashMap.newKeySet();
    private static ServerList serverList = null;
    private static long lastServerListLoadTime = 0L;

    public static synchronized void reloadServerList() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        if (serverList == null) {
            serverList = new ServerList(client);
        }
        try {
            serverList.loadFile();
            lastServerListLoadTime = System.currentTimeMillis();
            NO_ICON_SET.clear();
        } catch (Exception ignored) {}
    }

    public static Identifier getServerIcon(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return UNKNOWN_SERVER;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getTextureManager() == null) {
            return UNKNOWN_SERVER;
        }

        String norm = PasswordManager.normalizeServerAddress(serverAddress);

        WorldIcon cachedIcon = ICON_CACHE.get(norm);
        if (cachedIcon != null) {
            return cachedIcon.getTextureId();
        }

        if (NO_ICON_SET.contains(norm)) {
            return UNKNOWN_SERVER;
        }

        // 1. Check if currently playing on this server
        try {
            ServerInfo current = client.getCurrentServerEntry();
            if (current != null && current.address != null) {
                String normCurrent = PasswordManager.normalizeServerAddress(current.address);
                if (normCurrent.equalsIgnoreCase(norm) || current.address.equalsIgnoreCase(serverAddress)) {
                    byte[] fav = current.getFavicon();
                    if (fav != null && fav.length > 0) {
                        Identifier id = registerIcon(client, norm, fav);
                        if (id != null) return id;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Check saved multiplayer server list
        if (serverList == null || (System.currentTimeMillis() - lastServerListLoadTime > 15000)) {
            reloadServerList();
        }

        if (serverList != null) {
            try {
                int count = serverList.size();
                for (int i = 0; i < count; i++) {
                    ServerInfo info = serverList.get(i);
                    if (info != null && info.address != null) {
                        String infoNorm = PasswordManager.normalizeServerAddress(info.address);
                        if (infoNorm.equalsIgnoreCase(norm) || info.address.equalsIgnoreCase(serverAddress)) {
                            byte[] fav = info.getFavicon();
                            if (fav != null && fav.length > 0) {
                                Identifier id = registerIcon(client, norm, fav);
                                if (id != null) return id;
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        NO_ICON_SET.add(norm);
        return UNKNOWN_SERVER;
    }

    private static Identifier registerIcon(MinecraftClient client, String normAddress, byte[] faviconBytes) {
        try {
            NativeImage image = NativeImage.read(faviconBytes);
            if (image != null) {
                if (image.getWidth() == 64 && image.getHeight() == 64) {
                    WorldIcon icon = WorldIcon.forServer(client.getTextureManager(), normAddress);
                    icon.load(image);
                    ICON_CACHE.put(normAddress, icon);
                    return icon.getTextureId();
                } else {
                    image.close();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static synchronized void clearCache() {
        for (WorldIcon icon : ICON_CACHE.values()) {
            try {
                icon.close();
            } catch (Exception ignored) {}
        }
        ICON_CACHE.clear();
        NO_ICON_SET.clear();
    }
}
