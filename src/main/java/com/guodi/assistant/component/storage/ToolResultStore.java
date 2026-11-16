package com.guodi.assistant.component.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

/** Publishes immutable result files. The caller chooses identity and preview policy. */
public final class ToolResultStore {
    public Path save(Path projectPath, String identity, String content) {
        if (!identity.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Result identity must be a SHA-256 hex digest");
        }
        try {
            Path directory = resultDirectory(projectPath);
            Path target = directory.resolve(identity + ".txt");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            publish(target, bytes);
            verifyContent(target, bytes);
            return Path.of(".agent", "tool-results", target.getFileName().toString());
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save tool result", error);
        }
    }

    private Path resultDirectory(Path projectPath) throws IOException {
        Path root = projectPath.toRealPath();
        if (!Files.isDirectory(root) || root.getParent() == null) {
            throw new IllegalArgumentException("A project directory is required");
        }
        Path directory = root;
        for (String segment : new String[]{".agent", "tool-results"}) {
            directory = directory.resolve(segment);
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException existing) {
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                    throw new IOException("Result directory must not be a symbolic link: " + directory);
                }
            }
        }
        return directory;
    }

    private void publish(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, NOFOLLOW_LINKS)) return;
        Path temporary = Files.createTempFile(target.getParent(), ".result-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException concurrent) {
                // Another conversation may have published the same immutable content.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void verifyContent(Path target, byte[] bytes) throws IOException {
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            throw new IOException("Result must be a regular file: " + target.getFileName());
        }
        try (var input = Files.newInputStream(target, READ, NOFOLLOW_LINKS)) {
            if (!Arrays.equals(input.readAllBytes(), bytes)) {
                throw new IOException("Saved tool result was modified: " + target.getFileName());
            }
        }
    }
}
