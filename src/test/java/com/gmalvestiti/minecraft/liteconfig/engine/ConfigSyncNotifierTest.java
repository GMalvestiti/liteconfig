package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSyncNotifierTest {

    @Test
    void replacementAndRemovalRespectListenerOwnership() {
        ConfigSyncNotifier<Object> notifier = new ConfigSyncNotifier<>(new ConfigScope("mod"), state -> {});
        Object owner = new Object();
        Object other = new Object();
        AtomicInteger calls = new AtomicInteger();

        notifier.setLocalChangeListener(owner, () -> calls.addAndGet(1));
        notifier.setLocalChangeListener(owner, () -> calls.addAndGet(2));
        assertThrows(IllegalStateException.class,
            () -> notifier.setLocalChangeListener(other, () -> calls.addAndGet(4)));
        notifier.clearLocalChangeListener(other);
        notifier.notifyLocalChange();
        assertEquals(2, calls.get());

        notifier.clearLocalChangeListener(owner);
        notifier.notifyLocalChange();
        assertEquals(2, calls.get());

        notifier.setLocalChangeListener(other, () -> calls.addAndGet(4));
        notifier.notifyLocalChange();
        assertEquals(6, calls.get());
    }
}
