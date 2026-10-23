package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.EntryNormalization;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataTraversalTest {

    @Test
    void testEmptyMetadataHasNoPropertiesOrSyncedValues() {
        ConfigMetadata metadata = new ConfigMetadata(Object.class, List.of(), 0, List.of());

        assertEquals(List.of(), metadata.flatten().toList());
        assertEquals(List.of(), metadata.synced());
        assertTrue(metadata.property(null).isEmpty());
        assertTrue(metadata.property("missing").isEmpty());
    }

    @Test
    void testTraversalPreservesDepthFirstOrderAndOriginalProperties() {
        ConfigProperty nested = property("section.nested", true, List.of());
        ConfigProperty section = property("section", true, List.of(nested));
        ConfigProperty other = property("other", false, List.of());
        ConfigMetadata metadata = new ConfigMetadata(Object.class, List.of(), 0, List.of(section, other));

        assertEquals(List.of(section, nested, other), metadata.flatten().toList());
        assertEquals(List.of(section, nested), section.flatten().toList());
        assertEquals(List.of(nested), metadata.synced());
        assertSame(nested, metadata.property("section.nested").orElseThrow());
    }

    @Test
    void testGarbageCollectionDoesNotDiscardIndexesOfLiveMetadata() {
        ConfigProperty nested = property("retained.nested", true, List.of());
        ConfigProperty section = property("retained", false, List.of(nested));
        ConfigMetadata metadata = new ConfigMetadata(Object.class, List.of(), 0, List.of(section));
        List<ConfigProperty> synced = metadata.synced();
        List<ConfigProperty> nestedSynced = MetadataTraversal.synced(section.children());

        System.gc();

        assertSame(synced, metadata.synced());
        assertSame(nestedSynced, MetadataTraversal.synced(section.children()));
        assertSame(nested, metadata.property("retained.nested").orElseThrow());
        assertEquals(List.of(section, nested), section.flatten().toList());
        Reference.reachabilityFence(metadata);
        Reference.reachabilityFence(section);
    }

    @Test
    void testEqualTreesKeepTheirOwnPropertyInstances() {
        ConfigProperty first = property("identity", true, List.of());
        ConfigProperty second = property("identity", true, List.of());
        ConfigMetadata firstMetadata = new ConfigMetadata(Object.class, List.of(), 0, List.of(first));
        ConfigMetadata secondMetadata = new ConfigMetadata(Object.class, List.of(), 0, List.of(second));

        assertEquals(first, second);
        assertNotSame(first, second);
        assertSame(first, firstMetadata.property("identity").orElseThrow());
        assertSame(second, secondMetadata.property("identity").orElseThrow());
        assertSame(first, firstMetadata.synced().get(0));
        assertSame(second, secondMetadata.synced().get(0));
    }

    @Test
    void testConcurrentCacheMissesPublishCompleteIndexes() throws InterruptedException {
        ConfigProperty nested = property("concurrent.nested", true, List.of());
        ConfigProperty section = property("concurrent", true, List.of(nested));
        ConfigMetadata metadata = new ConfigMetadata(Object.class, List.of(), 0, List.of(section));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<ConfigProperty>> publishedSynced = new AtomicReference<>();

        for (int reader = 0; reader < 4; reader++) {
            new Thread(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int read = 0; read < 100; read++) {
                        assertEquals(List.of(section, nested), metadata.flatten().toList());
                        List<ConfigProperty> synced = metadata.synced();
                        assertEquals(List.of(nested), synced);
                        publishedSynced.compareAndSet(null, synced);
                        assertSame(publishedSynced.get(), synced);
                        assertSame(nested, metadata.property("concurrent.nested").orElseThrow());
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    done.countDown();
                }
            }, "metadata-reader").start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("Concurrent metadata traversal failed", failure.get());
        }
    }

    private static ConfigProperty property(String path, boolean synced, List<ConfigProperty> children) {
        ConfigConstraints constraints = new ConfigConstraints(
            OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty(),
            OptionalInt.empty(), OptionalInt.empty(), List.of(),
            false, false, false, false, false, EntryNormalization.NONE, false);
        return new ConfigProperty(
            path, path.substring(path.lastIndexOf('.') + 1), "field", Object.class, null,
            List.of(), Optional.empty(), false, synced, constraints, children);
    }
}
