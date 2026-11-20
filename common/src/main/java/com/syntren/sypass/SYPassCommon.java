package com.syntren.sypass;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SYPassCommon {
    public static final String MOD_ID = "sypass";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SYPassCommon() {}

    public static void init(PlatformHelper platform) {
        PlatformHelper.init(platform);
        init();
    }

    public static void init() {
        LOGGER.info("[SY-Pass] Initializing common module (platform={})...", PlatformHelper.get().getPlatformName());
        SYPassConfig.load();
        PasswordManager.init();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
