package com.gmalvestiti.minecraft.liteconfig.platform;

import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(ConfigRegistryIsolation.class)
class PlatformStrategyTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void disconnectReasonsStayTranslatableAndUseNativeColors() {
        Minecraft client = mock(Minecraft.class);
        ClientPacketListener listener = mock(ClientPacketListener.class);
        Connection connection = mock(Connection.class);
        when(client.getConnection()).thenReturn(listener);
        when(listener.getConnection()).thenReturn(connection);
        CommonClientPlatform platform = new CommonClientPlatform(client) {
            @Override
            public void request(ConfigSyncRequestC2SPacket packet) {
            }
        };

        platform.disconnect(TranslationKey.RESTART_REQUIRED_SERVER);
        platform.disconnect(TranslationKey.SYNC_FAILED);

        ArgumentCaptor<Component> reasons = ArgumentCaptor.forClass(Component.class);
        verify(connection, times(2)).disconnect(reasons.capture());
        List<Component> messages = reasons.getAllValues();
        assertEquals(TranslationKey.RESTART_REQUIRED_SERVER.key(),
            ((TranslatableContents) messages.getFirst().getContents()).getKey());
        assertEquals(ChatFormatting.YELLOW.getColor(), messages.getFirst().getStyle().getColor().getValue());
        assertEquals(TranslationKey.SYNC_FAILED.key(),
            ((TranslatableContents) messages.getLast().getContents()).getKey());
        assertEquals(ChatFormatting.RED.getColor(), messages.getLast().getStyle().getColor().getValue());

        when(client.getConnection()).thenReturn(null);
        platform.disconnect(TranslationKey.RESTART_REQUIRED_SERVER);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void localizedDisconnectMessagesSeparateExplanationAndRecovery() throws Exception {
        for (String language : List.of("en_us", "pt_br", "es_es")) {
            try (var stream = getClass().getResourceAsStream("/assets/liteconfig/lang/" + language + ".json")) {
                assertNotNull(stream, language);
                var messages = com.google.gson.JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (TranslationKey reason : TranslationKey.values()) {
                    String message = messages.get(reason.key()).getAsString();
                    String[] paragraphs = message.split("\n\n", -1);
                    assertEquals(2, paragraphs.length, language + ": " + reason);
                    assertFalse(paragraphs[0].isBlank());
                    assertFalse(paragraphs[1].isBlank());
                    assertFalse(message.contains("\u00a7"), "Colors belong in component styles");
                }
            }
        }
    }

    @Test
    void stoppingBeforeRuntimeBindingStillDeactivatesTheWorld(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("world"));
        try (var holder = LiteConfig.holder(LifecycleWorldConfig.class)
            .modId("mod").baseDir(directory.resolve("config")).create()) {
            assertFalse(holder.isActive());
            CommonServerPlatform.starting(serverPlatform(server));
            assertTrue(holder.isActive());
            assertNull(CommonServerPlatform.current());
            assertTrue(CommonServerPlatform.stopped(server));
            assertFalse(holder.isActive());
            assertTrue(holder.path().isEmpty());
        }
    }

    @Test
    void staleStopPreservesTheCurrentWorldAndRuntime(@TempDir Path directory) {
        MinecraftServer previous = mock(MinecraftServer.class);
        MinecraftServer current = mock(MinecraftServer.class);
        when(previous.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("previous"));
        when(current.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("current"));
        CommonServerPlatform runtime = serverPlatform(current);
        try (var holder = LiteConfig.holder(LifecycleWorldConfig.class)
            .modId("mod").baseDir(directory.resolve("config")).create()) {
            CommonServerPlatform.starting(serverPlatform(previous));
            CommonServerPlatform.starting(runtime);
            CommonServerPlatform.started(current);

            assertFalse(CommonServerPlatform.stopped(previous));
            assertTrue(holder.isActive());
            assertTrue(holder.path().orElseThrow().startsWith(directory.resolve("current")));
            assertTrue(ServerConfigSync.isCurrent(runtime));
            assertTrue(CommonServerPlatform.stopped(current));
            assertFalse(holder.isActive());
            assertFalse(ServerConfigSync.isCurrent(runtime));
        }
    }

    @Test
    void syncAndServerSchedulingWaitForTheCurrentServersStartedEvent(@TempDir Path directory)
        throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        MinecraftServer previous = mock(MinecraftServer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        when(previous.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("previous"));
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("current"));
        CommonServerPlatform platform = serverPlatform(server);
        try (var holder = LiteConfig.holder(LifecycleWorldConfig.class)
            .modId("mod").baseDir(directory.resolve("config")).create()) {
            CommonServerPlatform.starting(serverPlatform(previous));
            CommonServerPlatform.starting(platform);
            CommonServerPlatform.started(previous);

            assertTrue(holder.isActive());
            assertNull(CommonServerPlatform.current());
            assertFalse(ServerConfigSync.isCurrentServer(server));
            assertTrue(ServerConfigSync.beginHandshake().isEmpty());
            platform.execute(() -> fail("Work cannot run before server startup"));
            verify(server, never()).execute(any(Runnable.class));

            CommonServerPlatform.started(server);

            assertSame(platform, CommonServerPlatform.current());
            assertSame(server, platform.server);
            assertTrue(ServerConfigSync.isCurrentServer(server));
            assertFalse(ServerConfigSync.beginHandshake().isEmpty());
            CommonServerPlatform.stopped(server);
            assertNull(CommonServerPlatform.current());
            assertFalse(holder.isActive());
        }
    }

    @Test
    void failedWorldInitializationRetainsOwnershipForShutdownCleanup(@TempDir Path directory)
        throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory.resolve("world"));
        try (var holder = LiteConfig.holder(BrokenLifecycleWorldConfig.class)
            .modId("mod").baseDir(directory.resolve("config")).create()) {
            assertThrows(LiteConfigException.class,
                () -> CommonServerPlatform.starting(serverPlatform(server)));
            assertNull(CommonServerPlatform.current());
            assertTrue(CommonServerPlatform.stopped(server));
            assertFalse(holder.isActive());
            assertTrue(holder.path().isEmpty());
            assertFalse(CommonServerPlatform.stopped(server));
        }
    }

    @Test
    void queuedPayloadFromOldConnectionCannotRunAfterReconnect() {
        Minecraft client = mock(Minecraft.class);
        ClientPacketListener listener = mock(ClientPacketListener.class);
        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        when(client.getConnection()).thenReturn(listener);
        when(listener.getConnection()).thenReturn(first);
        when(first.getRemoteAddress()).thenReturn(new InetSocketAddress("192.0.2.12", 49152));
        when(second.getRemoteAddress()).thenReturn(new InetSocketAddress("192.0.2.12", 49152));
        CommonClientPlatform platform = new CommonClientPlatform(client) {
            @Override
            public void request(ConfigSyncRequestC2SPacket packet) {
            }
        };
        ClientConfigSync.bind(platform);
        AtomicInteger applied = new AtomicInteger();
        Object captured = platform.connectionToken();
        Runnable queuedPayload = () -> {
            if (ClientConfigSync.isCurrentConnection(captured)) {
                applied.incrementAndGet();
            }
        };
        assertTrue(ClientConfigSync.isCurrentConnection(captured));
        when(client.getConnection()).thenReturn(null);
        queuedPayload.run();
        when(client.getConnection()).thenReturn(listener);
        when(listener.getConnection()).thenReturn(second);
        queuedPayload.run();
        assertEquals(0, applied.get());
        assertTrue(ClientConfigSync.isCurrentConnection(second));
        assertFalse(ClientConfigSync.isCurrentConnection(first));
        when(client.isLocalServer()).thenReturn(true);
        assertFalse(ClientConfigSync.isCurrentConnection(second));
    }

    @Test
    void disconnectedIntegratedHostAndLanGuestHaveDistinctConnectionStates() {
        Minecraft client = mock(Minecraft.class);
        CommonClientPlatform platform = new CommonClientPlatform(client) {
            @Override
            public void request(ConfigSyncRequestC2SPacket packet) {
            }
        };
        ClientConfigSync.bind(platform);
        assertEquals(ConnectionType.DISCONNECTED, platform.connectionType());
        assertFalse(ClientConfigSync.hasRemoteConnection());

        ClientPacketListener listener = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(listener);
        when(client.isLocalServer()).thenReturn(true);
        assertEquals(ConnectionType.INTEGRATED_HOST, platform.connectionType());
        assertFalse(ClientConfigSync.hasRemoteConnection());

        Connection connection = mock(Connection.class);
        when(listener.getConnection()).thenReturn(connection);
        when(connection.getRemoteAddress()).thenReturn(new InetSocketAddress("192.0.2.12", 49152));
        when(client.isLocalServer()).thenReturn(false);
        assertTrue(ClientConfigSync.hasRemoteConnection());
        assertEquals(ConnectionType.REMOTE, platform.connectionType());
        assertTrue(platform.connectionIdentity().contains("192.0.2.12"));
        assertTrue(platform.connectionIdentity().contains("49152"));

        ServerData data = mock(ServerData.class);
        data.ip = "lan-host.example:49152";
        when(client.getCurrentServer()).thenReturn(data);
        assertEquals("lan-host.example:49152", platform.connectionIdentity());
    }

    @Test
    void stoppedServerWorkCannotRunOnReplacementOrReboundServer() {
        MinecraftServer server = mock(MinecraftServer.class);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        doAnswer(call -> {
            queue.addLast(call.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        CommonServerPlatform first = serverPlatform(server);
        CommonServerPlatform.bind(first);
        assertSame(server, first.server);
        assertTrue(ServerConfigSync.isCurrentServer(server));
        AtomicInteger calls = new AtomicInteger();
        first.execute(calls::incrementAndGet);
        assertEquals(1, queue.size());

        assertTrue(CommonServerPlatform.unbind(server));
        assertFalse(ServerConfigSync.isCurrentServer(server));
        CommonServerPlatform replacement = serverPlatform(server);
        CommonServerPlatform.bind(replacement);
        assertFalse(ServerConfigSync.isCurrent(first));
        queue.removeFirst().run();
        first.execute(calls::incrementAndGet);
        assertEquals(0, calls.get());
        assertTrue(queue.isEmpty());

        replacement.execute(calls::incrementAndGet);
        queue.removeFirst().run();
        assertEquals(1, calls.get());
    }

    @Test
    void oldServerStopCannotClearNewServerBinding() {
        CommonServerPlatform previous = fakeServer(new Object());
        CommonServerPlatform current = fakeServer(new Object());
        CommonServerPlatform.bind(previous);
        CommonServerPlatform.bind(current);
        assertFalse(CommonServerPlatform.unbind(previous.identity()));
        assertTrue(ServerConfigSync.isCurrent(current));
        assertTrue(CommonServerPlatform.unbind(current.identity()));
        assertFalse(ServerConfigSync.isCurrentServer(current.identity()));
    }

    private static CommonServerPlatform serverPlatform(MinecraftServer server) {
        return new CommonServerPlatform(server) {
            @Override
            protected boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
                return false;
            }

            @Override
            protected void send(ServerPlayer player, CustomPacketPayload packet) {
            }
        };
    }

    private static CommonServerPlatform fakeServer(Object identity) {
        CommonServerPlatform platform = mock(CommonServerPlatform.class);
        when(platform.identity()).thenReturn(identity);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(platform).execute(any(Runnable.class));
        return platform;
    }

    @Config(name = "lifecycle-world", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class LifecycleWorldConfig {
        public int value = 1;
    }

    @Config(name = "broken-lifecycle-world", type = ConfigType.WORLD, readFailurePolicy = FailurePolicy.STRICT)
    public static class BrokenLifecycleWorldConfig implements ConfigExtension {
        public int value = 1;

        @Override
        public void afterLoad() {
            throw new IllegalStateException("World initialization failed");
        }
    }
}
