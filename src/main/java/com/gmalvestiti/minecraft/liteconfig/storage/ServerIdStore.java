package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.shared.ConfigConstant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class ServerIdStore {

    private ServerIdStore() {}

    public static UUID load(Path worldRoot) {
        if (worldRoot == null) {
            return UUID.randomUUID();
        }

        Path file = worldRoot.resolve(LiteConfigCommon.MOD_ID).resolve(ConfigConstant.SERVER_ID_FILE);
        try {
            Files.createDirectories(file.getParent());
            UUID id = UUID.randomUUID();

            try {
                Files.writeString(file, id.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return id;
            } catch (FileAlreadyExistsException existing) {
                return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).strip());
            }
        } catch (IOException | IllegalArgumentException failure) {
            LiteConfigCommon.error("Failed to load the server id; restart-sync caching is limited to this session", failure);
            return UUID.randomUUID();
        }
    }
}
