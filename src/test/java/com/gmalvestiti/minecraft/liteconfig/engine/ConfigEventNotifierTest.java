package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ConfigEventNotifierTest {

    @Test
    void eventsRunInRegistrationOrderOnTheInitiatingCaller() throws Exception {
        ConfigEventNotifier<Object> notifier = new ConfigEventNotifier<>(new ConfigScope("mod"), new Object());
        Object published = new Object();
        List<String> events = new ArrayList<>();
        AtomicReference<Thread> caller = new AtomicReference<>();
        Consumer<Object> event = state -> {
            assertSame(published, state);
            assertSame(caller.get(), Thread.currentThread());
            events.add("update");
        };
        try (ConfigSubscription update = notifier.addUpdateEvent(event);
             ConfigSubscription second = notifier.addUpdateEvent(state -> events.add("second"));
             ConfigSubscription load = notifier.addLoadEvent(state -> events.add("load"));
             ConfigSubscription save = notifier.addSaveEvent(state -> events.add("save"))) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    caller.set(Thread.currentThread());
                    notifier.notifyUpdated(published);
                    assertEquals(List.of("update", "second"), events);
                    notifier.notifyLoaded(published);
                    notifier.notifySaved(published);
                } catch (Throwable raised) {
                    failure.set(raised);
                }
            });
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(List.of("update", "second", "load", "save"), events);
        }
    }

    @Test
    void failingEventIsLoggedWithoutStoppingTheOperationOrLaterEvents() {
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigEventNotifier<Integer> notifier = new ConfigEventNotifier<>(scope, new Object());
        IllegalStateException failure = new IllegalStateException("broken event");
        List<Integer> seen = new ArrayList<>();
        try (ConfigSubscription broken = notifier.addUpdateEvent(state -> { throw failure; });
             ConfigSubscription next = notifier.addUpdateEvent(seen::add)) {
            assertDoesNotThrow(() -> notifier.notifyUpdated(4));
            assertEquals(List.of(4), seen);
            verify(scope).logError(contains("broken event"), org.mockito.ArgumentMatchers.same(failure));
        }
    }

    @Test
    void subscriptionsCloseIndependentlyAndNotifierCloseDropsEveryEvent() {
        ConfigEventNotifier<Integer> notifier = new ConfigEventNotifier<>(new ConfigScope("mod"), new Object());
        List<Integer> seen = new ArrayList<>();
        ConfigSubscription first = notifier.addUpdateEvent(seen::add);
        ConfigSubscription second = notifier.addUpdateEvent(state -> seen.add(state * 10));
        ConfigSubscription load = notifier.addLoadEvent(seen::add);
        ConfigSubscription save = notifier.addSaveEvent(seen::add);
        notifier.notifyUpdated(2);
        first.close();
        first.close();
        notifier.notifyUpdated(3);
        notifier.close();
        notifier.close();
        notifier.notifyUpdated(4);
        notifier.notifyLoaded(4);
        notifier.notifySaved(4);
        assertEquals(List.of(2, 20, 30), seen);
        assertSame(ConfigSubscription.NONE, notifier.addUpdateEvent(seen::add));
        assertSame(ConfigSubscription.NONE, notifier.addLoadEvent(seen::add));
        assertSame(ConfigSubscription.NONE, notifier.addSaveEvent(seen::add));
        second.close();
        load.close();
        save.close();
    }

    @Test
    void closedTokensReleaseTheirListenerAndNotifierReferences() throws Exception {
        ConfigEventNotifier<Integer> notifier = new ConfigEventNotifier<>(new ConfigScope("mod"), new Object());
        List<Integer> seen = new ArrayList<>();
        try (ConfigSubscription subscription = notifier.addUpdateEvent(seen::add)) {
            List<?> events = (List<?>) field(notifier, "updateEvents");
            subscription.close();
            assertNull(field(subscription, "listener"));
            assertNull(field(subscription, "notifier"));
            assertNull(field(subscription, "listeners"));
            notifier.notifyUpdated(5);
            assertTrue(seen.isEmpty());
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void closingDuringDispatchSkipsEventsRemovedFromTheSnapshot() {
        ConfigEventNotifier<Integer> notifier = new ConfigEventNotifier<>(new ConfigScope("mod"), new Object());
        List<Integer> seen = new ArrayList<>();
        try (ConfigSubscription first = notifier.addUpdateEvent(state -> {
            seen.add(state);
            notifier.close();
        }); ConfigSubscription second = notifier.addUpdateEvent(state -> fail("closed event"))) {
            notifier.notifyUpdated(2);
            assertEquals(List.of(2), seen);
        }
    }

    @Test
    void unsubscribingALaterEventDuringDispatchSkipsItImmediately() {
        ConfigEventNotifier<Integer> notifier = new ConfigEventNotifier<>(new ConfigScope("mod"), new Object());
        AtomicReference<ConfigSubscription> later = new AtomicReference<>();
        List<Integer> seen = new ArrayList<>();
        try (ConfigSubscription first = notifier.addUpdateEvent(state -> later.get().close());
             ConfigSubscription removed = notifier.addUpdateEvent(state -> fail("unsubscribed event"));
             ConfigSubscription last = notifier.addUpdateEvent(seen::add)) {
            later.set(removed);
            notifier.notifyUpdated(3);
            notifier.notifyUpdated(4);
            assertEquals(List.of(3, 4), seen);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
