package com.syntren.sypass;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.PasswordManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SYPassCommon {
    public static final String MOD_ID = "sypass";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("[SY-Pass] Initializing common module for Minecraft 1.21.1...");
        SYPassConfig.load();
        PasswordManager.init();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
