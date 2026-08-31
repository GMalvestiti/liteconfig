package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * The logical game side whose main thread receives a config lifecycle listener.
 * {@link #BOTH} registers the listener independently on the client and server threads.
 */
public enum ConfigSide {
    /** The physical client's main thread. */
    CLIENT,

    /** The logical server's main thread. */
    SERVER,

    /** Both logical sides, registered as two independent listeners. */
    BOTH
}
