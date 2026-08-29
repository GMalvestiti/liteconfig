package com.gmalvestiti.minecraft.liteconfig.network.packet;

import com.gmalvestiti.minecraft.liteconfig.network.ConfigBytes;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigPayloads;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.SharedConstants;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSyncS2CPacketTest {

    @BeforeAll
    static void beforeAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void testRoundTripsConfigPayloadThroughVanillaCodec() {
        ConfigSyncS2CPacket original =
            new ConfigSyncS2CPacket(
                42, true, Map.of("example.rules", ConfigBytes.of(new byte[] {1, 2, 3})));
        var buffer = Unpooled.buffer();

        ConfigSyncS2CPacket.STREAM_CODEC.encode(buffer, original);
        ConfigSyncS2CPacket decoded = ConfigSyncS2CPacket.STREAM_CODEC.decode(buffer);

        assertEquals(original.transactionId(), decoded.transactionId());
        assertEquals(original.last(), decoded.last());
        assertEquals(original.configs().keySet(), decoded.configs().keySet());
        assertEquals(original.configs().get("example.rules"), decoded.configs().get("example.rules"));
    }

    @Test
    void testRoundTripsHashHandshakeThroughVanillaCodec() {
        ConfigSyncHandshakeS2CPacket original = new ConfigSyncHandshakeS2CPacket(Map.of(
            "example.rules", hash(1),
            "example.client", hash(2)));
        var buffer = Unpooled.buffer();

        ConfigSyncHandshakeS2CPacket.STREAM_CODEC.encode(buffer, original);
        ConfigSyncHandshakeS2CPacket decoded = ConfigSyncHandshakeS2CPacket.STREAM_CODEC.decode(buffer);

        assertEquals(original.hashes().keySet(), decoded.hashes().keySet());
        assertEquals(original.hashes(), decoded.hashes());
    }

    @Test
    void testRoundTripsConfigRequestThroughVanillaCodec() {
        ConfigSyncRequestC2SPacket original =
            new ConfigSyncRequestC2SPacket(Set.of("example.rules", "example.client"));
        var buffer = Unpooled.buffer();

        ConfigSyncRequestC2SPacket.STREAM_CODEC.encode(buffer, original);
        ConfigSyncRequestC2SPacket decoded = ConfigSyncRequestC2SPacket.STREAM_CODEC.decode(buffer);

        assertEquals(original.configIds(), decoded.configIds());
    }

    @Test
    void testPacketCreationDefensivelyCopiesEntries() {
        Map<String, ConfigBytes> configs = new LinkedHashMap<>();
        configs.put("example.rules", ConfigBytes.of(new byte[] {1}));

        ConfigSyncS2CPacket packet = new ConfigSyncS2CPacket(true, configs);
        configs.clear();

        assertNotSame(configs, packet.configs());
        assertEquals(Set.of("example.rules"), packet.configs().keySet());
        assertThrows(
            UnsupportedOperationException.class,
            () -> packet.configs().clear());
    }

    @Test
    void testConfigBytesDefensivelyCopiesInputAndOutput() {
        byte[] source = {1, 2, 3};
        ConfigBytes value = ConfigBytes.of(source);
        source[0] = 9;
        byte[] returned = value.bytes();
        returned[1] = 9;

        assertEquals(ConfigBytes.of(new byte[] {1, 2, 3}), value);
    }

    @Test
    void testRejectsAProtocolVersionMismatch() {
        var buffer = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(buffer, ConfigSyncProtocol.VERSION + 1);

        assertThrows(
            DecoderException.class,
            () -> ConfigSyncS2CPacket.STREAM_CODEC.decode(buffer));
    }

    @Test
    void testRejectsHashesThatAreNotSha256Sized() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConfigSyncHandshakeS2CPacket(
                Map.of("example.rules", ConfigBytes.of(new byte[31]))));
    }

    @Test
    void testRejectsTooManyRequestIdsBeforeAllocatingThem() {
        var buffer = Unpooled.buffer();
        ConfigSyncProtocol.encodeVersion(buffer);
        ByteBufCodecs.VAR_INT.encode(buffer, ENTRIES_PER_PACKET + 1);

        assertThrows(
            DecoderException.class,
            () -> ConfigSyncRequestC2SPacket.STREAM_CODEC.decode(buffer));
    }

    @Test
    void testSplitsEntriesIntoPacketsOfSixtyFour() {
        Map<String, ConfigBytes> configs = configs(ENTRIES_PER_PACKET * 2 + 1);

        var batches = ConfigPayloads.batches(configs);

        assertEquals(3, batches.size());
        assertEquals(List.of(64, 64, 1), batches.stream().map(Map::size).toList());
    }

    @Test
    void testDoesNotCapPacketsPerTransaction() {
        int packetCount = 65;
        Map<String, ConfigBytes> configs = configs(ENTRIES_PER_PACKET * packetCount);

        var batches = ConfigPayloads.batches(configs);

        assertEquals(packetCount, batches.size());
    }

    private static Map<String, ConfigBytes> configs(int count) {
        Map<String, ConfigBytes> configs = new LinkedHashMap<>(count);
        ConfigBytes value = ConfigBytes.of(new byte[] {1});
        for (int index = 0; index < count; index++) {
            configs.put("example.config" + index, value);
        }
        return configs;
    }

    private static ConfigBytes hash(int marker) {
        byte[] hash = new byte[ConfigSyncProtocol.HASH_BYTES];
        hash[0] = (byte) marker;
        return ConfigBytes.of(hash);
    }
}
