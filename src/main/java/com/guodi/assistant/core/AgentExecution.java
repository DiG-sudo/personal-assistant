package com.guodi.assistant.core;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.domain.ConversationTurn;

/** One run only. Harness owns snapshots/commits; Runtime owns the actual model-call count. */
public final class AgentExecution {
    private final String conversationId;
    private final Path projectPath;
    private ConversationTurn turn;
    private List<ConversationTurn> history = List.of();
    private List<String> memories = List.of();
    private int modelCalls;
    private final List<ConversationService.SessionMessage> steers;
    private final Consumer<AgentEvent> events;

    public AgentExecution(String conversationId, ConversationTurn turn, Path projectPath,
            List<ConversationService.SessionMessage> steers, Consumer<AgentEvent> events) {
        this.conversationId = conversationId;
        this.projectPath = projectPath;
        this.turn = turn;
        this.steers = steers;
        this.events = events;
    }

    public String conversationId() { return conversationId; }
    public Path projectPath() { return projectPath; }
    public ConversationTurn turn() { return turn; }
    public List<ConversationTurn> history() { return history; }
    public List<String> memories() { return memories; }
    public int modelCalls() { return modelCalls; }
    public List<ConversationService.SessionMessage> steers() { return steers; }

    void restored(List<ConversationTurn> history) { this.history = List.copyOf(history); }
    void recalled(List<String> memories) { this.memories = List.copyOf(memories); }
    void committed(ConversationTurn turn) { this.turn = turn; }
    void emit(AgentEvent event) { events.accept(event); }

    void modelCallStarted(int limit) {
        // This check also governs additional real calls if a wrapper later implements retries.
        if (modelCalls >= limit) {
            throw new AgentException("模型调用预算已耗尽。");
        }
        modelCalls++;
    }
}
