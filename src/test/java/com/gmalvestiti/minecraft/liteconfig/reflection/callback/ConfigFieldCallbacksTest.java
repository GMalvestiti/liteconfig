package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigFieldCallbacksTest {

    @Test
    void testRecoversAndReleasesQueuedStatesAfterAnError() {
        ErrorConfig.events.clear();
        ErrorConfig.fail = true;
        ConfigFieldCallbacks<ErrorConfig> callbacks = ConfigFieldCallbacks.resolve(
            ErrorConfig.class,
            ConfigFieldPlan.of(ErrorConfig.class),
            TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));

        ErrorConfig first = state(1);
        ErrorConfig second = state(2);
        assertThrows(AssertionError.class, callbacks.enqueueChanged(first, second, false)::run);

        ErrorConfig.fail = false;
        callbacks.enqueueChanged(second, state(3), false).run();

        assertEquals(List.of(3), ErrorConfig.events);
    }

    @Test
    void testComparesArrayValuesWithoutSerializingTheRoot() {
        ArrayConfig.events.clear();
        ConfigFieldCallbacks<ArrayConfig> callbacks = ConfigFieldCallbacks.resolve(
            ArrayConfig.class,
            ConfigFieldPlan.of(ArrayConfig.class),
            TestFixtures.SCOPE,
            new ConfigFieldAccess(TestFixtures.SCOPE));

        ArrayConfig first = new ArrayConfig();
        ArrayConfig equal = new ArrayConfig();
        callbacks.enqueueChanged(first, equal, false).run();

        equal.values[1] = 3;
        callbacks.enqueueChanged(first, equal, false).run();

        assertEquals(List.of(3), ArrayConfig.events);
    }

    private static ErrorConfig state(int value) {
        ErrorConfig state = new ErrorConfig();
        state.value = value;
        return state;
    }

    static class ErrorConfig {

        static final List<Integer> events = new ArrayList<>();
        static boolean fail;

        @Entry(callback = "changed")
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

        @Entry(callback = "changed")
        int[] values = {1, 2};

        private void changed(int[] oldValue, int[] newValue, boolean fromSync) {
            events.add(newValue[1]);
        }
    }
}
