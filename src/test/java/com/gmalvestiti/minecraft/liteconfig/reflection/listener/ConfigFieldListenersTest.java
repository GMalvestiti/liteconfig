package com.gmalvestiti.minecraft.liteconfig.reflection.listener;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFieldListenersTest {

    @Test
    void testRecoversAfterAnErrorFromADirectListener() {
        ErrorConfig.events.clear();
        ErrorConfig.fail = true;
        ConfigFieldListeners<ErrorConfig> listeners = ConfigFieldListeners.resolve(
            ErrorConfig.class,
            ConfigFieldPlan.of(ErrorConfig.class),
            TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));

        ErrorConfig first = state(1);
        ErrorConfig second = state(2);
        assertThrows(AssertionError.class, listeners.enqueueChanged(first, second, false)::run);

        ErrorConfig.fail = false;
        listeners.enqueueChanged(second, state(3), false).run();

        assertEquals(List.of(3), ErrorConfig.events);
    }

    @Test
    void testSyncListenerErrorPropagatesBeforeTheAfterAction() {
        ErrorConfig.events.clear();
        ErrorConfig.fail = true;
        ConfigFieldListeners<ErrorConfig> listeners = resolve(ErrorConfig.class);
        List<String> after = new ArrayList<>();

        try {
            assertThrows(AssertionError.class,
                listeners.enqueueSynced(state(1), state(2), () -> after.add("after"))::run);
            assertEquals(List.of(), after);
        } finally {
            ErrorConfig.fail = false;
        }

        listeners.enqueueSynced(state(2), state(3), () -> after.add("after")).run();
        assertEquals(List.of(3), ErrorConfig.events);
        assertEquals(List.of("after"), after);
    }

    @Test
    void testRunsListenersDirectlyOnTheCallingThread() {
        DirectConfig.events.clear();
        DirectConfig.gameThread = Thread.currentThread();
        ConfigFieldListeners<DirectConfig> listeners = ConfigFieldListeners.resolve(
            DirectConfig.class, ConfigFieldPlan.of(DirectConfig.class), TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));
        DirectConfig first = new DirectConfig();
        DirectConfig second = new DirectConfig();
        second.value = 2;

        listeners.enqueueChanged(first, second, false).run();
        listeners.enqueueSynced(second, first, () -> DirectConfig.events.add("after")).run();

        assertEquals(List.of("2:false", "1:true", "after"), DirectConfig.events);
    }

    @Test
    void testCloseSkipsPreviouslyCapturedAndLaterListeners() {
        DirectConfig.events.clear();
        ConfigFieldListeners<DirectConfig> listeners = ConfigFieldListeners.resolve(
            DirectConfig.class, ConfigFieldPlan.of(DirectConfig.class), TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));
        DirectConfig first = new DirectConfig();
        DirectConfig second = new DirectConfig();
        second.value = 2;
        Runnable notification = listeners.enqueueChanged(first, second, false);

        listeners.close();
        listeners.close();
        notification.run();
        listeners.enqueueChanged(first, second, false).run();
        listeners.enqueueSynced(first, second, () -> DirectConfig.events.add("after")).run();

        assertEquals(List.of(), DirectConfig.events);
    }

    @Test
    void testComparesArrayValuesWithoutSerializingTheRoot() {
        ArrayConfig.events.clear();
        ConfigFieldListeners<ArrayConfig> listeners = ConfigFieldListeners.resolve(
            ArrayConfig.class,
            ConfigFieldPlan.of(ArrayConfig.class),
            TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));

        ArrayConfig first = new ArrayConfig();
        ArrayConfig equal = new ArrayConfig();
        listeners.enqueueChanged(first, equal, false).run();

        equal.values[1] = 3;
        listeners.enqueueChanged(first, equal, false).run();

        assertEquals(List.of(3), ArrayConfig.events);
    }

    @Test
    void testRejectsInvalidListenerDeclarationsBeforeAnyNotification() {
        for (Class<?> type : List.of(MissingListenerConfig.class, PrimitiveListenerConfig.class,
            StaticListenerConfig.class, ReturningListenerConfig.class, BoxedFlagListenerConfig.class)) {
            LiteConfigException failure = assertThrows(LiteConfigException.class, () -> resolve(type));

            assertEquals(ConfigError.INVALID_ENTRY_LISTENER, failure.error());
            assertTrue(failure.defect());
            assertTrue(failure.rawMessage().contains("invalid listener"));
        }
    }

    @Test
    void testFindsPrivateListenerInSuperclass() {
        InheritedListenerOwner.events.clear();
        ConfigFieldListeners<InheritedFieldConfig> listeners = resolve(InheritedFieldConfig.class);
        InheritedFieldConfig first = new InheritedFieldConfig();
        InheritedFieldConfig second = new InheritedFieldConfig();
        second.value = 2;

        listeners.enqueueChanged(first, second, false).run();

        assertEquals(List.of("1:2:false"), InheritedListenerOwner.events);
    }

    @Test
    void testUsesTheNewNestedOwnerAndHandlesAbsentOwners() {
        NestedSection.events.clear();
        ConfigFieldListeners<NestedConfig> listeners = resolve(NestedConfig.class);
        NestedConfig absent = new NestedConfig();
        absent.section = null;
        NestedConfig present = new NestedConfig();

        listeners.enqueueChanged(absent, present, false).run();
        listeners.enqueueChanged(present, absent, false).run();

        assertEquals(List.of("null:1:false"), NestedSection.events);
        assertSame(present.section, NestedSection.owner);
    }

    @Test
    void testOrdinaryFailureDoesNotPreventLaterListenersOrSyncEvent() {
        FailingListenerConfig.events.clear();
        ConfigFieldListeners<FailingListenerConfig> listeners = resolve(FailingListenerConfig.class);
        FailingListenerConfig first = new FailingListenerConfig();
        FailingListenerConfig second = new FailingListenerConfig();
        second.first = 2;
        second.second = 3;

        listeners.enqueueSynced(first, second, () -> FailingListenerConfig.events.add("after")).run();

        assertEquals(List.of("first:true", "second:3:true", "after"), FailingListenerConfig.events);
        assertEquals(2, second.first);
        assertEquals(3, second.second);
    }

    @Test
    void testUsesErasedGenericTypeAndSkipsStructurallyEqualValues() {
        GenericListenerConfig.events.clear();
        ConfigFieldListeners<GenericListenerConfig> listeners = resolve(GenericListenerConfig.class);
        GenericListenerConfig first = new GenericListenerConfig();
        GenericListenerConfig second = new GenericListenerConfig();

        listeners.enqueueChanged(first, second, false).run();
        second.values.add("new");
        listeners.enqueueChanged(first, second, false).run();

        assertEquals(List.of("[]:[new]:false"), GenericListenerConfig.events);
    }

    private static <T> ConfigFieldListeners<T> resolve(Class<T> type) {
        return ConfigFieldListeners.resolve(type, ConfigFieldPlan.of(type), TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));
    }

    private static ErrorConfig state(int value) {
        ErrorConfig state = new ErrorConfig();
        state.value = value;
        return state;
    }

    static class ErrorConfig {

        static final List<Integer> events = new ArrayList<>();
        static boolean fail;

        @Entry(listener = "changed")
        int value;

        private void changed(Integer oldValue, Integer newValue, boolean fromSync) {
            if (fail) {
                throw new AssertionError("boom");
            }
            events.add(newValue);
        }
    }

    static class ArrayConfig {

        static final List<Integer> events = new ArrayList<>();

        @Entry(listener = "changed")
        int[] values = {1, 2};

        private void changed(int[] oldValue, int[] newValue, boolean fromSync) {
            events.add(newValue[1]);
        }
    }

    static class DirectConfig {

        static final List<String> events = new ArrayList<>();
        static Thread gameThread;

        @Entry(listener = "changed")
        int value = 1;

        private void changed(Integer oldValue, Integer newValue, boolean fromSync) {
            org.junit.jupiter.api.Assertions.assertSame(gameThread, Thread.currentThread());
            events.add(newValue + ":" + fromSync);
        }
    }

    static class MissingListenerConfig {
        @Entry(listener = "missing")
        int value = 1;
    }

    static class PrimitiveListenerConfig {
        @Entry(listener = "changed")
        int value = 1;

        private void changed(int oldValue, int newValue, boolean fromSync) {}
    }

    static class StaticListenerConfig {
        @Entry(listener = "changed")
        int value = 1;

        private static void changed(Integer oldValue, Integer newValue, boolean fromSync) {}
    }

    static class ReturningListenerConfig {
        @Entry(listener = "changed")
        int value = 1;

        private int changed(Integer oldValue, Integer newValue, boolean fromSync) {
            return newValue;
        }
    }

    static class BoxedFlagListenerConfig {
        @Entry(listener = "changed")
        int value = 1;

        private void changed(Integer oldValue, Integer newValue, Boolean fromSync) {}
    }

    static class InheritedListenerOwner {
        static final List<String> events = new ArrayList<>();

        private void changed(Integer oldValue, Integer newValue, boolean fromSync) {
            events.add(oldValue + ":" + newValue + ":" + fromSync);
        }
    }

    static class InheritedFieldConfig extends InheritedListenerOwner {
        @Entry(listener = "changed")
        int value = 1;
    }

    static class NestedConfig {
        NestedSection section = new NestedSection();
    }

    static class NestedSection {
        static final List<String> events = new ArrayList<>();
        static NestedSection owner;

        @Entry(listener = "changed")
        int value = 1;

        private void changed(Integer oldValue, Integer newValue, boolean fromSync) {
            owner = this;
            events.add(oldValue + ":" + newValue + ":" + fromSync);
        }
    }

    static class FailingListenerConfig {
        static final List<String> events = new ArrayList<>();

        @Entry(listener = "firstChanged")
        int first = 1;

        @Entry(listener = "secondChanged")
        int second = 1;

        private void firstChanged(Integer oldValue, Integer newValue, boolean fromSync) {
            events.add("first:" + fromSync);
            throw new IllegalStateException("boom");
        }

        private void secondChanged(Integer oldValue, Integer newValue, boolean fromSync) {
            events.add("second:" + newValue + ":" + fromSync);
        }
    }

    static class GenericListenerConfig {
        static final List<String> events = new ArrayList<>();

        @Entry(listener = "changed")
        List<String> values = new ArrayList<>();

        private void changed(List<String> oldValue, List<String> newValue, boolean fromSync) {
            events.add(oldValue + ":" + newValue + ":" + fromSync);
        }
    }
}
