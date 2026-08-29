package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConfigSubscriptions {

    private ConfigSubscriptions() {}

    public static ConfigSubscription managed(Runnable closeAction) {
        return managed(closeAction, null);
    }

    public static ConfigSubscription managed(Runnable closeAction, Object retained) {
        return new Implementation(closeAction, null, retained);
    }

    public static ConfigSubscription owned(
        ConfigSubscription delegate,
        Collection<? super ConfigSubscription> owner
    ) {
        Objects.requireNonNull(delegate, "delegate");
        return new Implementation(
            delegate::close,
            Objects.requireNonNull(owner, "owner"),
            delegate);
    }

    private static final class Implementation implements ConfigSubscription {
        private final Runnable closeAction;
        private final Collection<? super ConfigSubscription> owner;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private Object retained;

        private Implementation(
            Runnable closeAction,
            Collection<? super ConfigSubscription> owner,
            Object retained
        ) {
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
            this.owner = owner;
            this.retained = retained;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                try {
                    closeAction.run();
                } finally {
                    retained = null;
                    if (owner != null) {
                        owner.remove(this);
                    }
                }
            }
        }
    }
}
