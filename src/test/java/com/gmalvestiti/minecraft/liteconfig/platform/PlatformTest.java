package com.gmalvestiti.minecraft.liteconfig.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlatformTest {

    @Test
    void testResolvesConfigDirectory() {
        Path configDir = Platform.getConfigDir();
        assertNotNull(configDir);
    }
}

