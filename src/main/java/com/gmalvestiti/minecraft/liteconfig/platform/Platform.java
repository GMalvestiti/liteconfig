package com.gmalvestiti.minecraft.liteconfig.platform;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
//?} elif neoforge {
/*import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
*///?}

import java.nio.file.Path;

public final class Platform {

    public static boolean isClient() {
        //? if fabric {
        try {
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        } catch (IllegalStateException | NullPointerException unavailable) {
            return false;
        }
        //?} elif neoforge {
        /*try {
            //? if >=1.21.11 {
            return FMLEnvironment.getDist() == Dist.CLIENT;
            //?} else {
            /^return FMLEnvironment.dist == Dist.CLIENT;
            ^///?}
        } catch (IllegalStateException unavailable) {
            return false;
        }
        *///?}
    }

    //? if fabric {
    public static Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
    //?} elif neoforge {
    /*public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
    *///?}
}
