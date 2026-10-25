package com.guodi.assistant.port;

import java.util.List;
import com.guodi.assistant.domain.ConversationTurn;

/** Implementations atomically replace one turn, preserving its id and start time. */
public interface ConversationStore {
    List<ConversationTurn> load(String conversationId);
    void save(String conversationId, ConversationTurn turn);
}
