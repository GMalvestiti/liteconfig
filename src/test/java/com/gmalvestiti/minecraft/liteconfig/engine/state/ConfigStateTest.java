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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigStateTest {

    @Test
    void testWritingUsesTheSharedOperationMonitor() {
        ConfigState<TestFixtures.SimpleConfig> state = state(new TestFixtures.SimpleConfig());
        Object lock = state.operationLock();

        state.writing(() -> {
            assertTrue(Thread.holdsLock(lock));
            assertSame(lock, state.operationLock());
            return null;
        });
    }

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

    @Test
    void testRemoteSnapshotPreservesLocalStateAndIsolatesPendingValues() {
        ConfigState<TestFixtures.SimpleConfig> state = state(new TestFixtures.SimpleConfig());
        TestFixtures.SimpleConfig active = new TestFixtures.SimpleConfig();
        active.value = 9;
        TestFixtures.SimpleConfig pending = new TestFixtures.SimpleConfig();
        pending.value = 12;

        state.replaceRemote(active, pending, null);
        state.pending().orElseThrow().value = -1;
        state.published().value = -2;

        assertTrue(state.remoteActive());
        assertEquals(9, state.canonical().value);
        assertEquals(12, state.pending().orElseThrow().value);
        assertEquals(1, state.copyOfLocal().value);

        state.clearRemote();

        assertFalse(state.remoteActive());
        assertEquals(12, state.pending().orElseThrow().value);
        assertEquals(1, state.published().value);
        state.replace(new TestFixtures.SimpleConfig());
        assertEquals(12, state.pending().orElseThrow().value);
    }

    @Test
    void testRollbackRestoresPendingAndRemoteState() {
        ConfigState<TestFixtures.SimpleConfig> state = state(new TestFixtures.SimpleConfig());
        TestFixtures.SimpleConfig pending = new TestFixtures.SimpleConfig();
        pending.value = 12;
        state.replaceRemote(new TestFixtures.SimpleConfig(), pending, null);
        ConfigState.Saved<TestFixtures.SimpleConfig> saved = state.checkpoint();
        TestFixtures.SimpleConfig replacement = new TestFixtures.SimpleConfig();
        replacement.value = 4;

        state.replace(replacement);
        state.restore(saved);

        assertTrue(state.remoteActive());
        assertEquals(12, state.pending().orElseThrow().value);
        assertEquals(1, state.published().value);
    }

    private static ConfigState<TestFixtures.SimpleConfig> state(TestFixtures.SimpleConfig initial) {
        return new ConfigState<>(ConfigStateTest::copy, new ConfigScope("mod"), initial);
    }

    @Test
    void localReplacementResetsRemoteStateAndCanClearPending() {
        ConfigState<TestFixtures.SimpleConfig> state = state(new TestFixtures.SimpleConfig());
        TestFixtures.SimpleConfig pending = new TestFixtures.SimpleConfig();
        pending.value = 12;
        state.replaceRemote(new TestFixtures.SimpleConfig(), pending, null);
        TestFixtures.SimpleConfig active = new TestFixtures.SimpleConfig();
        active.value = 4;
        state.replaceLocal(active, pending, null);
        state.pending().orElseThrow().value = -1;
        assertFalse(state.remoteActive());
        assertEquals(4, state.copyOfLocal().value);
        assertNotSame(active, state.published());
        assertEquals(12, state.pending().orElseThrow().value);

        state.replaceLocal(new TestFixtures.SimpleConfig(), null, null);
        assertTrue(state.pending().isEmpty());
        assertEquals(1, state.copyOfLocal().value);
    }

    @Test
    void failedLocalPublicationLeavesTheEntireSnapshotUntouched() {
        ConfigState<TestFixtures.SimpleConfig> state = new ConfigState<>(source -> {
            if (source.value == 99) {
                throw new IllegalStateException("copy failed");
            }
            return copy(source);
        }, new ConfigScope("mod"), new TestFixtures.SimpleConfig());
        TestFixtures.SimpleConfig pending = new TestFixtures.SimpleConfig();
        pending.value = 12;
        state.replaceRemote(new TestFixtures.SimpleConfig(), pending, null);
        TestFixtures.SimpleConfig published = state.published();
        TestFixtures.SimpleConfig rejected = new TestFixtures.SimpleConfig();
        rejected.value = 99;

        assertThrows(RuntimeException.class, () -> state.replaceLocal(rejected, null, null));

        assertSame(published, state.published());
        assertTrue(state.remoteActive());
        assertEquals(12, state.pending().orElseThrow().value);
        assertEquals(1, state.copyOfLocal().value);
    }
}
