package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;

public record ClientSyncResult(
    boolean completed,
    boolean restartRequired,
    TranslationKey disconnectReason
) {

    static ClientSyncResult pending() {
        return new ClientSyncResult(false, false, null);
    }

    static ClientSyncResult completed(boolean restartRequired, TranslationKey disconnectReason) {
        return new ClientSyncResult(
            true,
            restartRequired,
            restartRequired ? disconnectReason : null);
    }

    static ClientSyncResult failed() {
        return new ClientSyncResult(true, false, TranslationKey.SYNC_FAILED);
    }
}
