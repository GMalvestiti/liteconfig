package com.gmalvestiti.minecraft.liteconfig.context;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigModelTest {

    @Test
    void testReusesImmutableStructureButNotRuntimeState() {
        ConfigModel<TestFixtures.SimpleConfig> first =
            ConfigModel.of(TestFixtures.SimpleConfig.class, new ConfigScope("first"));
        ConfigModel<TestFixtures.SimpleConfig> second =
            ConfigModel.of(TestFixtures.SimpleConfig.class, new ConfigScope("second"));

        assertSame(first.fields(), second.fields());
        assertSame(first.extensions(), second.extensions());
        assertNotSame(first.metadata(), second.metadata());
        assertNotSame(first.callbacks(), second.callbacks());
    }
}
