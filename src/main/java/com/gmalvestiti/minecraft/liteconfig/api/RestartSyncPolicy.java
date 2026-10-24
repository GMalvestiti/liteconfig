package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;

/**
 * Chooses what happens when restart-only values differ during join synchronization.
 *
 * <p>After initialization, local edits, explicit reloads, and live server sync keep restart-only
 * changes in {@link ConfigHolder#pending()} without changing active values. This policy never
 * disconnects an already-connected player. CLIENT and SERVER changes take effect at game startup;
 * WORLD changes take effect in the next world session, though initial synced values apply on join.
 *
 * <p>Synced SERVER restart values are saved to the client's config file under either policy and do
 * not become active on reconnect or holder reload.
 *
 * @see Config#restartSyncPolicy()
 */
public enum RestartSyncPolicy {
    /** Allow joining with the active values and expose the desired settings through {@code pending()}. */
    DEFER,
    /** Save differing SERVER restart values from join sync, then disconnect with a restart message. */
    DISCONNECT
}
