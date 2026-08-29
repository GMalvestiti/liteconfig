package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * The logical game side whose main thread receives a config lifecycle listener.
 * {@link #BOTH} registers the listener independently on the client and server threads.
 */
public enum ConfigSide {
    CLIENT,
    SERVER,
    BOTH
}
