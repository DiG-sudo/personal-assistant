package com.guodi.assistant.component.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Local single-process JSON storage. A checkpoint replaces a complete file via atomic rename. */
public final class JsonFiles {
    private final ObjectMapper json;

    public JsonFiles(ObjectMapper json) {
        this.json = json;
    }

    public static Path conversationDirectory(Path root, String conversationId) {
        String name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8));
        return root.resolve(name);
    }

    public <T> List<T> readDirectory(Path directory, Class<T> type) {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> read(path, type)).toList();
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read data directory: " + directory, error);
        }
    }

    private <T> T read(Path path, Class<T> type) {
        try {
            return json.readValue(Files.readAllBytes(path), type);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read data file: " + path, error);
        }
    }

    public void write(Path target, Object value) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".checkpoint-", ".tmp");
            Files.write(temporary, json.writeValueAsBytes(value));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot commit data file: " + target, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    // Do not mask the original commit failure; .tmp files are never loaded as records.
                }
            }
        }
    }
}
