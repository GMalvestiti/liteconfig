package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;

/**
 * Chooses whether a config or field participates in server-to-client synchronization.
 *
 * <p>{@link Entry#sync()} defaults to {@link #INHERIT}, so adding a comment, listener, or other
 * entry option does not change synchronization. Enable individual fields in a local-by-default
 * config, or disable local exceptions in a synced config:
 *
 * <pre>{@code
 * @Config(name = "rules", sync = SyncMode.ENABLED)
 * public final class Rules {
 *     public int maxTeamSize = 4; // inherits config-wide sync
 *
 *     @Entry(sync = SyncMode.DISABLED)
 *     public String auditLogPath = "logs/audit";
 * }
 * }</pre>
 *
 * <p>{@link Config#sync()} defaults to {@link #DISABLED} and accepts only {@link #ENABLED}
 * or {@link #DISABLED}. A root has no parent, so {@link #INHERIT} is rejected during model creation.
 *
 * <p>Modes affect only network selection. They do not change ownership, file persistence,
 * copying, or validation. {@link ConfigType#CLIENT} configs remain local regardless of mode.
 */
public enum SyncMode {
    /**
     * Follows the parent section or {@link Config#sync()} at the root.
     * Without an enabled parent, the field stays local. Invalid on {@link Config#sync()}.
     */
    INHERIT,

    /**
     * Synchronizes this config or field and the persisted leaves beneath it, except disabled descendants.
     * An enclosing {@link #DISABLED} section still excludes the entire subtree.
     */
    ENABLED,

    /**
     * Excludes a field and its entire subtree, including explicitly enabled descendants.
     * At a config root, disables config-wide sync while allowing individual fields to opt in.
     * Clients retain their local values or defaults for excluded fields.
     */
    DISABLED
}
