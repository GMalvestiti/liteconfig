package com.gmalvestiti.minecraft.liteconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiteConfigCommon {

    public static final String MOD_ID = /*$ mod_id*/ "liteconfig";
    private static final Logger LOGGER = LoggerFactory.getLogger(LiteConfigCommon.MOD_ID);

    public LiteConfigCommon() {}

    public static void info(String message) {
        LOGGER.info("[{}] {}", LiteConfigCommon.MOD_ID, message);
    }

    public static void error(String message, Exception exception) {
        LOGGER.error("[{}] {}", LiteConfigCommon.MOD_ID, message, exception);
    }
}
