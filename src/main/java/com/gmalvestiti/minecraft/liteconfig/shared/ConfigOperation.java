package com.gmalvestiti.minecraft.liteconfig.shared;

public enum ConfigOperation {

    LOAD("load"),
    SAVE("save"),
    READ("read"),
    WRITE("write"),
    UPDATE("update");

    private final String displayName;

    ConfigOperation(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
