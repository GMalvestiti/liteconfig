package com.gmalvestiti.minecraft.liteconfig.shared;

public enum TranslationKey {

    SYNC_FAILED("liteconfig.disconnect.sync_failed"),
    RESTART_REQUIRED_WORLD("liteconfig.disconnect.restart_required_world"),
    RESTART_REQUIRED_SERVER("liteconfig.disconnect.restart_required_server");

    private final String key;

    TranslationKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
