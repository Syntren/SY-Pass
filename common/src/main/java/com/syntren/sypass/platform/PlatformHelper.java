package com.syntren.sypass.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;

public interface PlatformHelper {

    /**
     * Повертає назву активного завантажувача ("fabric" або "neoforge").
     */
    String getPlatformName();

    /**
     * Повертає абсолютний шлях до папки конфігурацій гри (/.minecraft/config).
     */
    Path getConfigDir();

    /**
     * Перевіряє, чи активний мод із зазначеним modId.
     */
    boolean isModLoaded(String modId);

    /**
     * Копіює текст у системний буфер обміну клієнта.
     */
    void copyToClipboard(String text);

    /**
     * Singleton інстанс через стандартний ServiceLoader.
     */
    PlatformHelper INSTANCE = ServiceLoader.load(PlatformHelper.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("[SY-Pass] No PlatformHelper implementation found via ServiceLoader!"));

    static PlatformHelper get() {
        return INSTANCE;
    }
}
