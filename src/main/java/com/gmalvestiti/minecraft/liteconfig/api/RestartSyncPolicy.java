package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;

import java.util.function.Consumer;

/**
 * Chooses what happens when a client receives changed restart-only values from a server.
 *
 * <p>This policy only affects server sync. It does not affect local edits made through
 * {@link ConfigHolder#update(Consumer)} or
 * {@link ConfigHolder#updateAndSave(Consumer)}. Those edits stage restart-only
 * changes in {@link ConfigHolder#pending()} for every config type, keeping the current active
 * values unchanged. CLIENT and SERVER changes take effect after a game restart; WORLD changes
 * take effect when that world is loaded again.
 *
 * <p>For synced SERVER configs, received restart-only values are saved to the client's config file
 * for the next game startup under either policy. Reconnecting or reloading the holder does not
 * activate them. For WORLD configs, initial synced values apply when joining; later restart-only
 * changes remain pending until another world session.
 *
 * @see Config#restartSyncPolicy()
 */
public enum RestartSyncPolicy {
    /** Keep playing with the active values and expose the desired settings through {@code pending()}. */
    DEFER,
    /**
     * Disconnect after the transaction commits. SERVER settings request a game restart;
     * WORLD settings request a reconnect and apply when joining the world again.
     */
    DISCONNECT
}
