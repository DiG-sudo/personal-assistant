package com.guodi.assistant.component.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.guodi.assistant.domain.ConversationInfo;

/** Conversation metadata, separate from turn checkpoints. Admission serializes writes per conversation. */
public final class FileSessionStore {
    private final Path root;
    private final JsonFiles files;

    public FileSessionStore(Path root, JsonFiles files) {
        this.root = root;
        this.files = files;
    }

    public Optional<ConversationInfo> load(String conversationId) {
        Path path = path(conversationId);
        return Files.exists(path) ? Optional.of(files.read(path, ConversationInfo.class)) : Optional.empty();
    }

    public List<ConversationInfo> list() {
        return files.readDirectory(root, ConversationInfo.class);
    }

    public void save(ConversationInfo info) {
        files.write(path(info.conversationId()), info);
    }

    private Path path(String conversationId) {
        String name = JsonFiles.conversationDirectory(root, conversationId).getFileName().toString();
        return root.resolve(name + ".json");
    }
}
