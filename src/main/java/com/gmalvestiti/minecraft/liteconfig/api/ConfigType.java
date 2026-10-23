package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * Defines who owns a config and where its local backing file lives.
 * Synchronization remains opt-in through the existing sync annotations.
 */
public enum ConfigType {

    /**
     * Client-only preferences, never overridden by a server and inactive on dedicated servers.
     * Use synced {@link #SERVER} or {@link #WORLD} settings when a server must control a value
     * that client code also reads.
     * Local edits to restart-only fields remain pending until the next game startup.
     */
    CLIENT,

    /**
     * Settings owned by the current world, loaded from that world's save directory.
     * Restart-only edits remain pending until the world is loaded again. Remote clients apply
     * the world's initial synced settings on joining; later restart-only changes wait for
     * another world session. Unbound holders expose defaults.
     */
    WORLD,

    /**
     * Server settings, with local fallback values on clients.
     * Opt-in non-restart sync overlays those client values only for the connection. Synced restart
     * selections are saved in the normal local file for ordinary loading at the next startup.
     * Local restart-only edits remain pending until the next game startup.
     */
    SERVER
}
