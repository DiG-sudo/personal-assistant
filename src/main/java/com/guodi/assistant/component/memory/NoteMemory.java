package com.guodi.assistant.component.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.MemoryModule;

/** Deliberately simple: persist only explicit "记住：" notes, scoped to the conversation. */
@com.guodi.assistant.versioning.ComponentRevision(id = "notes", version = "1.0.0")
public final class NoteMemory implements MemoryModule {
    private final Path root;
    private final JsonFiles files;

    public NoteMemory(Path root, JsonFiles files) {
        this.root = root;
        this.files = files;
    }

    @Override
    public List<String> recall(String conversationId, String query) {
        // ponytail: latest 20 explicit notes; replace this component when semantic retrieval is needed.
        return files.readDirectory(JsonFiles.conversationDirectory(root, conversationId), Note.class).stream()
                .sorted(Comparator.comparing(Note::createdAt).reversed())
                .limit(20).map(Note::text).toList();
    }

    @Override
    public void observe(String conversationId, ConversationTurn turn) {
        String message = turn.messages().getFirst().text().strip();
        if (!message.startsWith("记住：") && !message.startsWith("记住:")) {
            return;
        }
        String note = message.substring(3).strip();
        if (!note.isEmpty()) {
            files.write(JsonFiles.conversationDirectory(root, conversationId).resolve(turn.id() + ".json"),
                    new Note(turn.id(), turn.startedAt(), note));
        }
    }

    public record Note(UUID sourceTurnId, Instant createdAt, String text) {}
}
