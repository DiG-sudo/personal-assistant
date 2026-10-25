package com.guodi.assistant.component.memory;

import java.util.List;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.MemoryModule;

@com.guodi.assistant.versioning.ComponentRevision(id = "none", version = "1.0.0")
public final class NoMemory implements MemoryModule {
    @Override
    public List<String> recall(String conversationId, String query) {
        return List.of();
    }

    @Override
    public void observe(String conversationId, ConversationTurn completedTurn) {}
}
