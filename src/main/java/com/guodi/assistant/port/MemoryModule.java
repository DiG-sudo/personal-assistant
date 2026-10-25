package com.guodi.assistant.port;

import java.util.List;
import com.guodi.assistant.domain.ConversationTurn;

/** Conversation-scoped memory. observe must be idempotent by completedTurn.id(). */
public interface MemoryModule {
    List<String> recall(String conversationId, String query);
    void observe(String conversationId, ConversationTurn completedTurn);
}
