package com.guodi.assistant.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.guodi.assistant.component.context.ContextView;
import com.guodi.assistant.component.context.ContextView.Entry;
import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.domain.ConversationTurn;

/** One run only. Harness owns snapshots/commits; Runtime owns the actual model-call count. */
public final class AgentExecution {
    private final String conversationId;
    private final Path projectPath;
    private ConversationTurn turn;
    private ContextView view = ContextView.empty();
    private final List<Entry> appendedMessages = new ArrayList<>();
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
    public ContextView view() { return view; }
    public List<Entry> appendedMessages() { return List.copyOf(appendedMessages); }
    public int modelCalls() { return modelCalls; }
    public List<ConversationService.SessionMessage> steers() { return steers; }

    void restored(ContextView view, List<Entry> unprocessed) {
        this.view = view;
        appendedMessages.addAll(unprocessed);
        turn.messages().forEach(message -> appendedMessages.add(new Entry(turn.id(), message)));
    }

    void committed(ConversationTurn next) {
        next.messages().subList(turn.messages().size(), next.messages().size())
                .forEach(message -> appendedMessages.add(new Entry(next.id(), message)));
        turn = next;
    }

    void prepared(ContextView view) {
        this.view = view;
        appendedMessages.clear();
    }

    void emit(AgentEvent event) { events.accept(event); }

    void modelCallStarted(int limit) {
        // This check also governs additional real calls if a wrapper later implements retries.
        if (modelCalls >= limit) {
            throw new AgentException("模型调用预算已耗尽。");
        }
        modelCalls++;
    }
}
