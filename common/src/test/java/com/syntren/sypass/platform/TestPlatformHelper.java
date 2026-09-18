package com.syntren.sypass.platform;

import java.nio.file.Path;

public class TestPlatformHelper implements PlatformHelper {
    private static final Path TEST_CONFIG_DIR = Path.of(System.getProperty("java.io.tmpdir"), "sypass-tests-" + System.currentTimeMillis());

    @Override
    public String getPlatformName() {
        return "test";
    }

    @Override
    public Path getConfigDir() {
        return TEST_CONFIG_DIR;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public void copyToClipboard(String text) {}
}
