package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
//? if >=1.21.11 {
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
//?} else {
/^import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.function.Consumer;
^///?}
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Minecraft;

@Mod(value = LiteConfigCommon.MOD_ID, dist = Dist.CLIENT)
public class NeoForgeClientEntrypoint {

    //? if <1.21.11 {
    /^private static final Consumer<ClientTickEvent.Post> CLIENT_START =
        NeoForgeClientEntrypoint::onClientStarted;
    ^///?}

    public NeoForgeClientEntrypoint(IEventBus modEventBus) {
        //? if >=1.21.11 {
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onClientStarted);
        //?} else {
        /^NeoForge.EVENT_BUS.addListener(CLIENT_START);
        ^///?}
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onLoggingOut);
    }

    //? if >=1.21.11 {
    private static void onClientStarted(ClientStartedEvent event) {
        initializeClient(event.getClient());
    }

    //?} else {
    /^private static void onClientStarted(ClientTickEvent.Post event) {
        NeoForge.EVENT_BUS.unregister(CLIENT_START);
        initializeClient(Minecraft.getInstance());
    }
    ^///?}

    private static void initializeClient(Minecraft client) {
        ClientConfigSync.bind(new NeoForgeClientPlatform(client));
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientConfigSync.resetClientConnection();
    }
}
*///?}
