package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigFieldAccessTest {

    @Test
    void testReadsAndWritesFieldValues() throws Exception {
        ConfigFieldAccess access = new ConfigFieldAccess(TestFixtures.SCOPE);
        TestFixtures.SimpleConfig config = new TestFixtures.SimpleConfig();
        Field field = TestFixtures.SimpleConfig.class.getDeclaredField("value");

        access.write(field, config, 42);

        assertEquals(42, access.read(field, config));
    }

    @Test
    void testWrapsIllegalFieldAccess() throws Exception {
        ConfigFieldAccess access = new ConfigFieldAccess(TestFixtures.SCOPE);
        Field field = String.class.getDeclaredField("value");
        assertThrows(RuntimeException.class, () -> access.read(field, new Object()));
        assertThrows(RuntimeException.class, () -> access.write(field, new Object(), null));
    }

}
