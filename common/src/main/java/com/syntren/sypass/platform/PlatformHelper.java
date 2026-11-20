package com.syntren.sypass.platform;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;

public interface PlatformHelper {

    /**
     * Returns the active loader identifier ("fabric", "neoforge", or "test").
     */
    String getPlatformName();

    /**
     * Returns the absolute path to the game's config directory (e.g. .minecraft/config).
     */
    Path getConfigDir();

    /**
     * Checks whether a mod with the given modId is loaded.
     */
    boolean isModLoaded(String modId);

    /**
     * Copies text to the client's system clipboard.
     */
    void copyToClipboard(String text);

    default void copyToClipboard(char[] chars) {
        if (chars == null || chars.length == 0) return;
        copyToClipboard(new String(chars));
    }

    /**
     * Registers the loader-specific PlatformHelper explicitly.
     * Avoids ServiceLoader collisions when Fabric and NeoForge are merged into a single Forgix JAR.
     */
    static void init(PlatformHelper helper) {
        Holder.set(Objects.requireNonNull(helper, "PlatformHelper must not be null"));
    }

    static PlatformHelper get() {
        return Holder.get();
    }

    final class Holder {
        private static volatile PlatformHelper instance;

        private Holder() {}

        static void set(PlatformHelper helper) {
            instance = helper;
        }

        static PlatformHelper get() {
            PlatformHelper current = instance;
            if (current != null) {
                return current;
            }
            synchronized (Holder.class) {
                if (instance == null) {
                    instance = ServiceLoader.load(PlatformHelper.class)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "[SY-Pass] No PlatformHelper initialized or found via ServiceLoader!"));
                }
                return instance;
            }
        }
    }
}
