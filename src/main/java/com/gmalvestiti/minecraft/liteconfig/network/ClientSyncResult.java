package com.gmalvestiti.minecraft.liteconfig.network;

import static com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync.RESTART_REQUIRED;
import static com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync.SYNC_FAILED;

public record ClientSyncResult(
    boolean completed,
    boolean restartRequired,
    String disconnectReason
) {

    static ClientSyncResult pending() {
        return new ClientSyncResult(false, false, null);
    }

    static ClientSyncResult completed(boolean restartRequired) {
        return new ClientSyncResult(
            true,
            restartRequired,
            restartRequired ? RESTART_REQUIRED : null);
    }

    static ClientSyncResult failed() {
        return new ClientSyncResult(true, false, SYNC_FAILED);
    }
}
