package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigStateTest {

    @Test
    void testIsolatesThePublishedValueFromTheCanonicalState() {
        ConfigState<TestFixtures.SimpleConfig> state =
            state(new TestFixtures.SimpleConfig());

        TestFixtures.SimpleConfig next = new TestFixtures.SimpleConfig();
        next.value = 42;
        ConfigState.Transition<TestFixtures.SimpleConfig> transition = state.replace(next);

        assertEquals(1, transition.before().value);
        assertSame(next, transition.after());
        assertSame(transition.published(), state.published());
        assertSame(next, state.canonical());
        assertNotSame(next, state.published());
        assertEquals(42, state.published().value);
        assertNotSame(next, state.copyOfCanonical());
        assertEquals(42, state.copyOfCanonical().value);
    }

    @Test
    void testKeepsTheCanonicalStateSafeWhenAReaderMutatesPublishedState() {
        TestFixtures.SimpleConfig initial = new TestFixtures.SimpleConfig();
        initial.value = 7;
        ConfigState<TestFixtures.SimpleConfig> state = state(initial);

        state.published().value = -1;

        assertEquals(7, state.canonical().value);
    }

    @Test
    void testDoesNotAliasTheInitialValue() {
        TestFixtures.SimpleConfig initial = new TestFixtures.SimpleConfig();
        ConfigState<TestFixtures.SimpleConfig> state = state(initial);

        assertSame(initial, state.canonical());
        assertNotSame(initial, state.published());
    }

    @Test
    void testReturnsOneConsistentSnapshotPair() {
        ConfigState<TestFixtures.SimpleConfig> state =
            state(new TestFixtures.SimpleConfig());

        ConfigState.View<TestFixtures.SimpleConfig> view = state.current();

        assertSame(state.canonical(), view.canonical());
        assertSame(state.published(), view.published());
        assertNotSame(view.canonical(), view.published());
    }

    private static TestFixtures.SimpleConfig copy(TestFixtures.SimpleConfig source) {
        TestFixtures.SimpleConfig copy = new TestFixtures.SimpleConfig();
        copy.value = source.value;
        copy.text = source.text;
        return copy;
    }

    @Test
    void testRejectsClonersThatAliasCanonicalState() {
        TestFixtures.SimpleConfig initial = new TestFixtures.SimpleConfig();

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> new ConfigState<>(source -> source, new ConfigScope("mod"), initial));

        assertEquals(ConfigError.INVALID_STATE_COPY, failure.error());
    }

    private static ConfigState<TestFixtures.SimpleConfig> state(TestFixtures.SimpleConfig initial) {
        return new ConfigState<>(ConfigStateTest::copy, new ConfigScope("mod"), initial);
    }
}
