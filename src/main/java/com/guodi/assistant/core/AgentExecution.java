package com.guodi.assistant.core;

import java.util.List;

import com.guodi.assistant.domain.ConversationTurn;

/** One run only. Harness owns snapshots/commits; Runtime owns the actual model-call count. */
public final class AgentExecution {
    private final String conversationId;
    private ConversationTurn turn;
    private List<ConversationTurn> history = List.of();
    private List<String> memories = List.of();
    private int modelCalls;

    public AgentExecution(String conversationId, ConversationTurn turn) {
        this.conversationId = conversationId;
        this.turn = turn;
    }

    public String conversationId() { return conversationId; }
    public ConversationTurn turn() { return turn; }
    public List<ConversationTurn> history() { return history; }
    public List<String> memories() { return memories; }
    public int modelCalls() { return modelCalls; }

    void restored(List<ConversationTurn> history) { this.history = List.copyOf(history); }
    void recalled(List<String> memories) { this.memories = List.copyOf(memories); }
    void committed(ConversationTurn turn) { this.turn = turn; }

    void modelCallStarted(int limit) {
        // This check also governs additional real calls if a wrapper later implements retries.
        if (modelCalls >= limit) {
            throw new AgentException("模型调用预算已耗尽。");
        }
        modelCalls++;
    }
}
