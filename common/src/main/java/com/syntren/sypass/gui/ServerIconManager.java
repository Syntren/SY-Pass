package com.syntren.sypass.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerIconManager {
    public static final ResourceLocation UNKNOWN_SERVER = ResourceLocation.withDefaultNamespace("textures/misc/unknown_server.png");

    private static final Map<String, FaviconTexture> ICON_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NO_ICON_SET = ConcurrentHashMap.newKeySet();
    private static final Map<String, byte[]> FAVICON_INDEX = new ConcurrentHashMap<>();
    private static ServerList serverList = null;
    private static long lastServerListLoadTime = 0L;

    public static synchronized void reloadServerList() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        if (serverList == null) {
            serverList = new ServerList(client);
        }
        try {
            serverList.load();
            lastServerListLoadTime = System.currentTimeMillis();
            FAVICON_INDEX.clear();
            NO_ICON_SET.clear();
            int count = serverList.size();
            for (int i = 0; i < count; i++) {
                ServerData info = serverList.get(i);
                if (info != null && info.ip != null) {
                    byte[] fav = info.getIconBytes();
                    if (fav != null && fav.length > 0) {
                        String norm = PasswordManager.normalizeServerAddress(info.ip);
                        FAVICON_INDEX.put(norm, fav);
                        FAVICON_INDEX.put(info.ip.toLowerCase(java.util.Locale.ROOT), fav);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static ResourceLocation getServerIcon(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return UNKNOWN_SERVER;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getTextureManager() == null) {
            return UNKNOWN_SERVER;
        }

        String norm = PasswordManager.normalizeServerAddress(serverAddress);

        FaviconTexture cachedIcon = ICON_CACHE.get(norm);
        if (cachedIcon != null) {
            return cachedIcon.textureLocation();
        }

        if (NO_ICON_SET.contains(norm)) {
            return UNKNOWN_SERVER;
        }

        try {
            ServerData current = client.getCurrentServer();
            if (current != null && current.ip != null) {
                String normCurrent = PasswordManager.normalizeServerAddress(current.ip);
                if (normCurrent.equalsIgnoreCase(norm) || current.ip.equalsIgnoreCase(serverAddress)) {
                    byte[] fav = current.getIconBytes();
                    if (fav != null && fav.length > 0) {
                        ResourceLocation id = registerIcon(client, norm, fav);
                        if (id != null) return id;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (serverList == null || (System.currentTimeMillis() - lastServerListLoadTime > 15000)) {
            reloadServerList();
        }

        byte[] fav = FAVICON_INDEX.get(norm);
        if (fav == null) {
            fav = FAVICON_INDEX.get(serverAddress.toLowerCase(java.util.Locale.ROOT));
        }

        if (fav != null && fav.length > 0) {
            ResourceLocation id = registerIcon(client, norm, fav);
            if (id != null) return id;
        }

        NO_ICON_SET.add(norm);
        return UNKNOWN_SERVER;
    }

    private static ResourceLocation registerIcon(Minecraft client, String normAddress, byte[] faviconBytes) {
        try {
            NativeImage image = NativeImage.read(faviconBytes);
            if (image != null) {
                if (image.getWidth() == 64 && image.getHeight() == 64) {
                    FaviconTexture icon = FaviconTexture.forServer(client.getTextureManager(), normAddress);
                    icon.upload(image);
                    ICON_CACHE.put(normAddress, icon);
                    return icon.textureLocation();
                } else {
                    image.close();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static synchronized void clearCache() {
        for (FaviconTexture icon : ICON_CACHE.values()) {
            try {
                icon.close();
            } catch (Exception ignored) {}
        }
        ICON_CACHE.clear();
        NO_ICON_SET.clear();
    }
}
