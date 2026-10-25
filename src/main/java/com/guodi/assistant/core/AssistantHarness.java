package com.guodi.assistant.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.ConversationTurn.Status;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.ContextBuilder;
import com.guodi.assistant.port.ConversationStore;
import com.guodi.assistant.port.MemoryModule;
import com.guodi.assistant.versioning.ExecutionManifest;
import com.guodi.assistant.versioning.Fingerprints;

/** Explicit lifecycle and execution boundaries. Every mutable value belongs to one AgentExecution. */
public final class AssistantHarness implements RuntimeHooks {
    private static final Logger log = LoggerFactory.getLogger(AssistantHarness.class);
    private final AgentRuntime runtime;
    private final ConversationStore conversations;
    private final MemoryModule memory;
    private final ContextBuilder context;
    private final ConversationGate gate;
    private final ExecutionManifest manifest;

    public AssistantHarness(AgentRuntime runtime, ConversationStore conversations,
            MemoryModule memory, ContextBuilder context, ConversationGate gate, ExecutionManifest manifest) {
        this.runtime = runtime;
        this.conversations = conversations;
        this.memory = memory;
        this.context = context;
        this.gate = gate;
        this.manifest = manifest;
    }

    public Reply chat(String conversationId, String message) {
        // ConversationGate releases its lock in finally, including cancellation and Error exits.
        return gate.within(conversationId, () -> aroundRun(
                new AgentExecution(conversationId, ConversationTurn.start(message, manifest))));
    }

    public List<ConversationTurn> history(String conversationId) {
        return gate.within(conversationId, () -> conversations.load(conversationId));
    }

    private Reply aroundRun(AgentExecution execution) {
        return stage(execution, "run", () -> {
            diagnostic(() -> log.info("turnId={} releaseId={} configurationId={}", execution.turn().id(),
                    manifest.releaseId(), manifest.fingerprint()));
            execution.restored(stage(execution, "restore", () -> {
                var history = new ArrayList<>(conversations.load(execution.conversationId()));
                // Recorded external outcomes may be unknown after interruption. Never replay automatically.
                for (int index = 0; index < history.size(); index++) {
                    var previous = history.get(index);
                    if (previous.status() == Status.RUNNING) {
                        var failed = previous.fail("上次运行中断；未自动重放工具调用。");
                        conversations.save(execution.conversationId(), failed);
                        history.set(index, failed);
                    }
                }
                return history;
            }));
            commit(execution, execution.turn());
            var warnings = new ArrayList<String>();
            try {
                try {
                    execution.recalled(stage(execution, "memory.recall", () -> memory.recall(
                            execution.conversationId(), execution.turn().messages().getFirst().text())));
                    diagnostic(() -> log.info("turnId={} stage=memory.recall recalledHashes={}",
                            execution.turn().id(), execution.memories().stream().map(note ->
                                    Fingerprints.sha256(note.getBytes(StandardCharsets.UTF_8))).toList()));
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException error) {
                    warnings.add("记忆读取失败，本轮使用对话记录继续。");
                }

                runtime.run(execution, this);
                commit(execution, execution.turn().complete());
            } catch (RuntimeException | Error error) {
                try {
                    commit(execution, execution.turn().fail(error instanceof AgentException
                            ? error.getMessage() : "执行失败，详细原因见服务日志。"));
                } catch (RuntimeException | Error persistenceError) {
                    error.addSuppressed(persistenceError);
                }
                throw error;
            }

            try {
                stage(execution, "memory.observe", () -> {
                    memory.observe(execution.conversationId(), execution.turn());
                    return null;
                });
            } catch (RuntimeException error) {
                // The answer is already committed. A later memory failure must not rerun the model.
                warnings.add("回答已保存，但本轮长期记忆更新失败。");
            }
            var completed = execution.turn();
            return new Reply(execution.conversationId(), completed.id(), completed.messages().getLast().text(),
                    execution.modelCalls(), List.copyOf(warnings), completed.manifest().releaseId(),
                    completed.manifest().fingerprint());
        });
    }

    @Override
    public ChatResponse aroundReasoning(AgentExecution execution, Function<List<Message>, ChatResponse> reasoning) {
        return stage(execution, "reasoning", () -> {
            var view = stage(execution, "context", () -> context.prepare(
                    execution.history(), execution.memories(), execution.turn().messages()));
            diagnostic(() -> {
                if (view.selection().isPresent()) {
                    var selected = view.selection().get();
                    var droppedTurns = execution.history().stream().map(ConversationTurn::id)
                            .filter(id -> !selected.turnIds().contains(id)).toList();
                    var droppedMemories = java.util.stream.IntStream.range(0, execution.memories().size())
                            .filter(index -> !selected.memoryIndexes().contains(index)).boxed().toList();
                    log.info("turnId={} stage=context selectedTurns={} droppedTurns={} selectedMemories={} droppedMemories={} messages={}",
                            execution.turn().id(), selected.turnIds(), droppedTurns, selected.memoryIndexes(),
                            droppedMemories, view.messages().size());
                } else {
                    log.info("turnId={} stage=context selection=unreported messages={}",
                            execution.turn().id(), view.messages().size());
                }
            });
            return reasoning.apply(view.messages());
        });
    }

    @Override
    public ChatResponse aroundModelCall(AgentExecution execution, Prompt request, Function<Prompt, ChatResponse> call) {
        return stage(execution, "model", () -> call.apply(request));
    }

    @Override
    public List<ToolResponse> aroundToolRound(AgentExecution execution, List<ToolCall> calls,
            Supplier<List<ToolResponse>> call) {
        diagnostic(() -> log.info("turnId={} stage=tools calls={}", execution.turn().id(),
                calls.stream().map(tool -> tool.id() + ":" + tool.name()).toList()));
        return stage(execution, "tools", call);
    }

    @Override
    public void checkpoint(AgentExecution execution, TranscriptMessage message) {
        var messages = new ArrayList<>(execution.turn().messages());
        messages.add(message);
        commit(execution, execution.turn().checkpoint(messages));
    }

    private void commit(AgentExecution execution, ConversationTurn next) {
        stage(execution, "persistence", () -> {
            conversations.save(execution.conversationId(), next);
            execution.committed(next);
            return null;
        });
    }

    private static <T> T stage(AgentExecution execution, String name, Supplier<T> work) {
        long started = System.nanoTime();
        diagnostic(() -> log.info("turnId={} stage={} event=start modelCalls={}",
                execution.turn().id(), name, execution.modelCalls()));
        try {
            T result = work.get();
            diagnostic(() -> log.info("turnId={} stage={} event=complete modelCalls={} elapsedMs={}",
                    execution.turn().id(), name, execution.modelCalls(), (System.nanoTime() - started) / 1_000_000));
            return result;
        } catch (RuntimeException | Error error) {
            diagnostic(() -> log.warn("turnId={} stage={} event=failed modelCalls={}",
                    execution.turn().id(), name, execution.modelCalls(), error));
            throw error;
        }
    }

    private static void diagnostic(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException ignored) {
            // Diagnostic sinks are best effort; authoritative checkpoints above are not.
        }
    }

    public record Reply(String conversationId, UUID turnId, String answer, int modelCalls, List<String> warnings,
            String releaseId, String configurationId) {}
}
