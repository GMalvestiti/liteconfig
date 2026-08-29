package com.gmalvestiti.minecraft.liteconfig.platform;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} elif neoforge {
/*import net.neoforged.fml.loading.FMLPaths;
*///?}

import java.nio.file.Path;

public final class Platform {

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
