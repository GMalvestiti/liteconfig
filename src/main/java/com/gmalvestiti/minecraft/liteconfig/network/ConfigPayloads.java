package com.gmalvestiti.minecraft.liteconfig.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;

public final class ConfigPayloads {

    private ConfigPayloads() {}

    public static List<Map<String, ConfigBytes>> batches(Map<String, ConfigBytes> source) {
        if (source.isEmpty()) {
            return List.of(Map.of());
        }

        int packetCount = (source.size() - 1) / ENTRIES_PER_PACKET + 1;

        List<Map<String, ConfigBytes>> batches = new ArrayList<>(packetCount);
        Map<String, ConfigBytes> batch = LinkedHashMap.newLinkedHashMap(ENTRIES_PER_PACKET);

        int batchBytes = 0;
        for (Map.Entry<String, ConfigBytes> entry : source.entrySet()) {

            validateEntry(entry.getKey(), entry.getValue());
            int entryBytes = ConfigSyncProtocol.entryBytes(entry.getKey(), entry.getValue());

            if (!batch.isEmpty() && (batch.size() == ENTRIES_PER_PACKET
                    || batchBytes + entryBytes > ConfigSyncProtocol.MAX_PACKET_BYTES)) {

                batches.add(batch);
                batch = LinkedHashMap.newLinkedHashMap(ENTRIES_PER_PACKET);
                batchBytes = 0;
            }

            batch.put(entry.getKey(), entry.getValue());
            batchBytes += entryBytes;
        }

        batches.add(batch);

        return batches;
    }

    public static void encodeEntries(ByteBuf buffer, Map<String, ConfigBytes> entries) {
        ByteBufCodecs.VAR_INT.encode(buffer, entries.size());

        for (Map.Entry<String, ConfigBytes> entry : entries.entrySet()) {
            validateEntry(entry.getKey(), entry.getValue());

            ByteBufCodecs.STRING_UTF8.encode(buffer, entry.getKey());
            ByteBufCodecs.VAR_INT.encode(buffer, entry.getValue().size());

            entry.getValue().writeTo(buffer);
        }
    }

    public static Map<String, ConfigBytes> decodeEntries(ByteBuf buffer) {
        return decodeEntries(buffer, ConfigSyncProtocol.MAX_CONFIG_BYTES, "config payload");
    }

    public static Map<String, ConfigBytes> decodeHashes(ByteBuf buffer) {
        Map<String, ConfigBytes> hashes = decodeEntries(buffer, ConfigSyncProtocol.HASH_BYTES, "config hash");

        hashes.forEach((id, hash) -> {
            if (hash.size() != ConfigSyncProtocol.HASH_BYTES) {
                throw new DecoderException("Config sync hash for " + id + " must contain exactly " + ConfigSyncProtocol.HASH_BYTES + " bytes");
            }
        });

        return hashes;
    }

    private static Map<String, ConfigBytes> decodeEntries(
        ByteBuf buffer,
        int maximumValueBytes,
        String valueLabel
    ) {
        int start = buffer.readerIndex();
        int count = ConfigSyncProtocol.readSize(buffer, ENTRIES_PER_PACKET, "packet entry count");

        Map<String, ConfigBytes> entries = LinkedHashMap.newLinkedHashMap(count);

        for (int index = 0; index < count; index++) {
            String id = ByteBufCodecs.STRING_UTF8.decode(buffer);

            try {
                ConfigSyncProtocol.validateId(id);
            } catch (IllegalArgumentException failure) {
                throw new DecoderException(failure.getMessage(), failure);
            }

            int length = ConfigSyncProtocol.readSize(buffer, maximumValueBytes, valueLabel);
            ConfigSyncProtocol.requireReadable(buffer, length, valueLabel);

            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);

            if (entries.put(id, ConfigBytes.trusted(bytes)) != null) {
                throw new DecoderException("Duplicate config sync id " + id);
            }

            if (buffer.readerIndex() - start > ConfigSyncProtocol.MAX_PACKET_BYTES) {
                throw new DecoderException("Config sync packet exceeds " + ConfigSyncProtocol.MAX_PACKET_BYTES + " bytes");
            }
        }

        return entries;
    }

    public static Map<String, ConfigBytes> immutableEntries(Map<String, ConfigBytes> entries) {
        return immutableEntries(entries, ConfigSyncProtocol.MAX_CONFIG_BYTES, "config sync payload");
    }

    public static Map<String, ConfigBytes> immutableHashes(Map<String, ConfigBytes> hashes) {
        Map<String, ConfigBytes> copy = immutableEntries(hashes, ConfigSyncProtocol.HASH_BYTES, "config sync hash");

        copy.forEach((id, hash) -> {
            if (hash.size() != ConfigSyncProtocol.HASH_BYTES) {
                throw new IllegalArgumentException("Config sync hash for " + id + " must contain exactly " + ConfigSyncProtocol.HASH_BYTES + " bytes");
            }
        });

        return copy;
    }

    private static Map<String, ConfigBytes> immutableEntries(
        Map<String, ConfigBytes> entries,
        int maximumValueBytes,
        String valueLabel
    ) {
        if (entries.size() > ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("A config sync packet may contain at most " + ENTRIES_PER_PACKET + " entries");
        }

        Map<String, ConfigBytes> copy = LinkedHashMap.newLinkedHashMap(entries.size());

        entries.forEach((id, value) -> {
            validateEntry(id, value, maximumValueBytes, valueLabel);

            if (copy.put(id, value) != null) {
                throw new IllegalArgumentException("Duplicate config sync id " + id);
            }
        });

        int packetBytes = 0;
        for (Map.Entry<String, ConfigBytes> entry : copy.entrySet()) {

            packetBytes += ConfigSyncProtocol.entryBytes(entry.getKey(), entry.getValue());

            if (packetBytes > ConfigSyncProtocol.MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Config sync packet exceeds " + ConfigSyncProtocol.MAX_PACKET_BYTES + " bytes");
            }
        }

        return Collections.unmodifiableMap(copy);
    }

    public static Set<String> immutableIds(Set<String> ids) {
        if (ids.size() > ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("A config sync request may contain at most " + ENTRIES_PER_PACKET + " ids");
        }

        Set<String> copy = new LinkedHashSet<>(ids.size());

        ids.forEach(id -> {
            ConfigSyncProtocol.validateId(id);

            if (!copy.add(id)) {
                throw new IllegalArgumentException("Duplicate config sync id " + id);
            }
        });

        return Collections.unmodifiableSet(copy);
    }

    private static void validateEntry(String id, ConfigBytes value) {
        validateEntry(id, value, ConfigSyncProtocol.MAX_CONFIG_BYTES, "config sync payload");
    }

    private static void validateEntry(
        String id,
        ConfigBytes value,
        int maximumValueBytes,
        String valueLabel
    ) {
        ConfigSyncProtocol.validateId(id);

        if (value == null) {
            throw new NullPointerException("Config sync bytes must not be null");
        }

        if (value.size() > maximumValueBytes) {
            throw new IllegalArgumentException(valueLabel + " exceeds " + maximumValueBytes + " bytes");
        }
    }
}
