package com.guodi.assistant.component.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.assistant.component.context.ContextView;

/** Stores derived context and the independent position in the original transcript. */
public final class FileContextStore {
    private final Path root;
    private final ObjectMapper json;
    private final JsonFiles files;

    public FileContextStore(Path root, ObjectMapper json) {
        this.root = root;
        this.json = json;
        this.files = new JsonFiles(json);
    }

    public Checkpoint load(String conversationId) {
        Path path = path(conversationId);
        if (!Files.exists(path)) return new Checkpoint(ContextView.empty(), null, 0);
        try {
            var stored = json.readTree(Files.readAllBytes(path));
            if (stored == null || !stored.isObject()) throw new IOException("Expected a context checkpoint object");
            if (stored.path("view").isArray() || stored.path("view").path("summary").isTextual()) {
                // Legacy lists or text summaries are rebuilt from the authoritative transcript.
                return new Checkpoint(ContextView.empty(), null, 0);
            }
            return json.treeToValue(stored, Checkpoint.class);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read context checkpoint: " + path, error);
        }
    }

    public void save(String conversationId, Checkpoint checkpoint) {
        files.write(path(conversationId), checkpoint);
    }

    private Path path(String conversationId) {
        return JsonFiles.conversationDirectory(root, conversationId).resolve("view.json");
    }

    public record Checkpoint(ContextView view, UUID sourceTurnId, int sourceMessageCount) {
        public Checkpoint {
            java.util.Objects.requireNonNull(view);
        }
    }
}
