package com.guodi.assistant.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.assistant.component.context.ContextView.Entry;
import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.context.WindowContextBuilder.ModelContext;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.FileContextStore;
import com.guodi.assistant.component.storage.FileContextStore.Checkpoint;
import com.guodi.assistant.component.tool.ToolOutcome;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.component.tool.ToolRoundExecutor;
import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.core.event.RequestFailed;
import com.guodi.assistant.core.event.ToolsFinished;
import com.guodi.assistant.core.event.ToolsStarted;
import com.guodi.assistant.core.event.TurnCompleted;
import com.guodi.assistant.core.event.TurnStarted;
import com.guodi.assistant.core.event.TurnSteer;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.ConversationTurn.Status;
import com.guodi.assistant.domain.TranscriptMessage;

/** 会话恢复、上下文、工具执行和持久化；具体循环由 Runtime 传入。 */
public class AssistantHarness {
    private static final Logger log = LoggerFactory.getLogger(AssistantHarness.class);
    private final FileConversationStore conversations;
    private final FileContextStore contextStore;
    private final String systemPrompt;
    private final WindowContextBuilder context;
    private final ToolRegistry toolRegistry;
    private final ToolRoundExecutor toolRounds;

    public AssistantHarness(FileConversationStore conversations, FileContextStore contextStore, String systemPrompt,
            WindowContextBuilder context, ToolRegistry toolRegistry, ToolRoundExecutor toolRounds) {
        this.conversations = conversations;
        this.contextStore = contextStore;
        this.systemPrompt = systemPrompt;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.toolRounds = toolRounds;
    }

    public Reply aroundRun(String conversationId, String message, Path projectPath, List<ConversationService.SessionMessage> steers,
            Consumer<AgentEvent> events, Consumer<AgentExecution> loop) {
        try {
            return executeTurn(conversationId, message, projectPath, steers, events, loop);
        } catch (RuntimeException | Error error) {
            events.accept(new RequestFailed(failureReason(error)));
            throw error;
        }
    }

    public List<ConversationTurn> history(String conversationId) {
        return conversations.load(conversationId);
    }

    private Reply executeTurn(String conversationId, String message, Path projectPath, List<ConversationService.SessionMessage> steers,
            Consumer<AgentEvent> events, Consumer<AgentExecution> loop) {
        AgentExecution execution = new AgentExecution(conversationId, ConversationTurn.start(message), projectPath, steers, events);
        log.info("turnId={} stage=run event=start", execution.turn().id());

        restoreHistory(execution);
        commit(execution, execution.turn());

        try {
            execution.emit(new TurnStarted(execution.turn().id()));
            loop.accept(execution);
            commit(execution, execution.turn().complete());
        } catch (RuntimeException | Error error) {
            recordFailure(execution, error);
            throw error;
        }

        ConversationTurn completed = execution.turn();
        log.info("turnId={} stage=run event=complete modelCalls={}", completed.id(), execution.modelCalls());
        Reply reply = new Reply(conversationId, completed.id(), completed.messages().getLast().text(),
                execution.modelCalls());
        execution.emit(new TurnCompleted(reply));
        return reply;
    }

    private void restoreHistory(AgentExecution execution) {
        List<ConversationTurn> history = new ArrayList<>(conversations.load(execution.conversationId()));
        for (int index = 0; index < history.size(); index++) {
            ConversationTurn previous = history.get(index);
            if (previous.status() == Status.RUNNING) {
                // 中断时外部工具的结果可能未知，保留记录，不自动重放。
                ConversationTurn failed = previous.fail("上次运行中断；未自动重放工具调用。");
                conversations.save(execution.conversationId(), failed);
                history.set(index, failed);
            }
        }
        Checkpoint checkpoint = contextStore.load(execution.conversationId());
        var unprocessed = new ArrayList<Entry>();
        boolean found = checkpoint.sourceTurnId() == null;
        for (var turn : history) {
            if (turn.id().equals(checkpoint.sourceTurnId())) {
                if (checkpoint.sourceMessageCount() < 0 || checkpoint.sourceMessageCount() > turn.messages().size()) {
                    throw new IllegalStateException("Context checkpoint exceeds the saved transcript");
                }
                turn.messages().subList(checkpoint.sourceMessageCount(), turn.messages().size())
                        .forEach(message -> unprocessed.add(new Entry(turn.id(), message)));
                found = true;
            } else if (found) {
                turn.messages().forEach(message -> unprocessed.add(new Entry(turn.id(), message)));
            }
        }
        if (!found) {
            throw new IllegalStateException("Context checkpoint refers to a missing transcript turn");
        }
        execution.restored(checkpoint.view(), unprocessed);
        log.info("turnId={} stage=restore viewMessages={} appendedMessages={}",
                execution.turn().id(), checkpoint.view().messages().size(), unprocessed.size() + 1);
    }

    public ChatResponse aroundReasoning(AgentExecution execution, Function<Prompt, ChatResponse> reasoning) {
        if (!execution.steers().isEmpty()) {
            var steer = execution.steers().getFirst();
            checkpoint(execution, TranscriptMessage.from(new UserMessage(steer.text())));
            execution.steers().removeFirst();
            log.info("turnId={} stage=steer event=applied messageId={}", execution.turn().id(), steer.messageId());
            execution.emit(new TurnSteer(execution.turn().id(), steer.messageId()));
        }
        ModelContext view;
        try {
            int appended = execution.appendedMessages().size();
            view = context.prepare(systemPrompt, toolRegistry.callbacks(), execution.view(), execution.appendedMessages(), execution.projectPath());
            contextStore.save(execution.conversationId(), new Checkpoint(view.view(),
                    execution.turn().id(), execution.turn().messages().size()));
            execution.prepared(view.view());
            log.info("turnId={} stage=context appendedMessages={} viewMessages={}",
                    execution.turn().id(), appended, view.view().messages().size());
        } catch (RuntimeException | Error error) {
            log.warn("turnId={} stage=context event=failed", execution.turn().id(), error);
            throw error;
        }
        return reasoning.apply(view.prompt());
    }

    public ChatResponse aroundModelCall(AgentExecution execution, Prompt request, Function<Prompt, ChatResponse> call) {
        long started = System.nanoTime();
        try {
            ChatResponse response = call.apply(request);
            log.info("turnId={} stage=model event=complete modelCalls={} elapsedMs={}", execution.turn().id(),
                    execution.modelCalls(), (System.nanoTime() - started) / 1_000_000);
            return response;
        } catch (RuntimeException | Error error) {
            log.warn("turnId={} stage=model event=failed modelCalls={}",
                    execution.turn().id(), execution.modelCalls(), error);
            throw error;
        }
    }

    public void aroundToolRound(AgentExecution execution, AssistantMessage assistant) {
        var calls = assistant.getToolCalls();
        log.info("turnId={} stage=tools calls={}", execution.turn().id(),
                calls.stream().map(call -> call.id() + ":" + call.name()).toList());
        ToolContext toolContext = new ToolContext(Map.of("conversationId", execution.conversationId(),
                "turnId", execution.turn().id().toString(), "projectPath", execution.projectPath()));
        try {
            execution.emit(new ToolsStarted(calls));
            long started = System.nanoTime();
            var outcomes = toolRounds.executeRound(calls, toolContext);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var results = outcomes.stream().map(ToolOutcome::toolResponse).toList();
            ToolResponseMessage response = ToolResponseMessage.builder().responses(results).build();
            checkpoint(execution, TranscriptMessage.from(response));
            execution.emit(new ToolsFinished(outcomes, elapsedMs));
        } catch (RuntimeException | Error error) {
            log.warn("turnId={} stage=tools event=failed", execution.turn().id(), error);
            throw error;
        }
    }

    public void checkpoint(AgentExecution execution, TranscriptMessage message) {
        List<TranscriptMessage> messages = new ArrayList<>(execution.turn().messages());
        messages.add(message);
        commit(execution, execution.turn().checkpoint(messages));
    }

    private void commit(AgentExecution execution, ConversationTurn next) {
        try {
            conversations.save(execution.conversationId(), next);
        } catch (RuntimeException | Error error) {
            log.warn("turnId={} stage=persistence event=failed", next.id(), error);
            throw error;
        }
        execution.committed(next);
        log.info("turnId={} stage=persistence status={} messages={}", next.id(), next.status(), next.messages().size());
    }

    private void recordFailure(AgentExecution execution, Throwable error) {
        try {
            commit(execution, execution.turn().fail(failureReason(error)));
        } catch (RuntimeException | Error persistenceError) {
            error.addSuppressed(persistenceError);
        }
        log.warn("turnId={} stage=run event=failed", execution.turn().id(), error);
    }

    private static String failureReason(Throwable error) {
        return error instanceof AgentException ? error.getMessage() : "执行失败，详细原因见服务日志。";
    }

    public record Reply(String conversationId, UUID turnId, String answer, int modelCalls) {}
}
