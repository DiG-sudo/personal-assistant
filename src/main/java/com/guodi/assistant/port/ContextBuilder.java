package com.guodi.assistant.port;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import com.guodi.assistant.domain.ConversationTurn;

/** Builds a model-only view without changing stored history; keep entire tool exchanges together. */
public interface ContextBuilder {
    List<Message> build(List<ConversationTurn> history, List<String> memories, String userMessage);
}
