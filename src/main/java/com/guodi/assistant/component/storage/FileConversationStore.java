package com.guodi.assistant.component.storage;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.ConversationStore;

@com.guodi.assistant.versioning.ComponentRevision(id = "json-files", version = "1.0.0")
public final class FileConversationStore implements ConversationStore {
    private final Path root;
    private final JsonFiles files;

    public FileConversationStore(Path root, JsonFiles files) {
        this.root = root;
        this.files = files;
    }

    @Override
    public List<ConversationTurn> load(String conversationId) {
        var turns = files.readDirectory(JsonFiles.conversationDirectory(root, conversationId), ConversationTurn.class);
        for (var turn : turns) {
            if ((turn.schemaVersion() != 1 && turn.schemaVersion() != 2)
                    || (turn.schemaVersion() == 2 && turn.manifest() == null)) {
                throw new IllegalStateException("Unsupported transcript schema: " + turn.schemaVersion());
            }
        }
        return turns.stream().sorted(Comparator.comparing(ConversationTurn::startedAt)
                .thenComparing(ConversationTurn::id)).toList();
    }

    @Override
    public void save(String conversationId, ConversationTurn turn) {
        files.write(JsonFiles.conversationDirectory(root, conversationId).resolve(turn.id() + ".json"), turn);
    }
}
