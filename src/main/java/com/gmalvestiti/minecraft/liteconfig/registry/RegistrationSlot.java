package com.gmalvestiti.minecraft.liteconfig.registry;

import com.google.common.util.concurrent.Uninterruptibles;

import java.util.concurrent.CountDownLatch;

final class RegistrationSlot {

    private final CountDownLatch completion = new CountDownLatch(1);
    private Thread creatingThread;
    private RegisteredConfig<?> value;
    private Throwable failure;

    RegistrationSlot(Thread creatingThread) {
        this.creatingThread = creatingThread;
    }

    Thread creatingThread() {
        return creatingThread;
    }

    RegisteredConfig<?> value() {
        return value;
    }

    void complete(RegisteredConfig<?> registration) {
        value = registration;
        creatingThread = null;
        completion.countDown();
    }

    void fail(Throwable failure) {
        this.failure = failure;
        creatingThread = null;
        completion.countDown();
    }

    RegisteredConfig<?> awaitCreation() {
        Uninterruptibles.awaitUninterruptibly(completion);
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return value;
    }
}
