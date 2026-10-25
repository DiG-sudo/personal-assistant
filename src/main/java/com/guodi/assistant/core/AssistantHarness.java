package com.guodi.assistant.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.ConversationTurn.Status;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.ContextBuilder;
import com.guodi.assistant.port.ConversationStore;
import com.guodi.assistant.port.MemoryModule;
import com.guodi.assistant.port.ToolExecutor;
import com.guodi.assistant.versioning.ExecutionManifest;
import com.guodi.assistant.versioning.Fingerprints;

/** 会话恢复、记忆、上下文、工具执行和持久化；具体循环由 Runtime 传入。 */
public class AssistantHarness {
    private static final Logger log = LoggerFactory.getLogger(AssistantHarness.class);
    private final ConversationStore conversations;
    private final MemoryModule memory;
    private final ContextBuilder context;
    private final ToolExecutor tools;
    private final ConversationGate gate;
    private final ExecutionManifest manifest;

    public AssistantHarness(ConversationStore conversations, MemoryModule memory,
            ContextBuilder context, ToolExecutor tools, ConversationGate gate, ExecutionManifest manifest) {
        this.conversations = conversations;
        this.memory = memory;
        this.context = context;
        this.tools = tools;
        this.gate = gate;
        this.manifest = manifest;
    }

    public Reply aroundRun(String conversationId, String message, Consumer<AgentExecution> loop) {
        return gate.within(conversationId, () -> executeTurn(conversationId, message, loop));
    }

    public List<ConversationTurn> history(String conversationId) {
        return gate.within(conversationId, () -> conversations.load(conversationId));
    }

    private Reply executeTurn(String conversationId, String message, Consumer<AgentExecution> loop) {
        AgentExecution execution = new AgentExecution(conversationId, ConversationTurn.start(message, manifest));
        log.info("turnId={} stage=run event=start releaseId={} configurationId={}",
                execution.turn().id(), manifest.releaseId(), manifest.fingerprint());

        restoreHistory(execution);
        commit(execution, execution.turn());
        List<String> warnings = new ArrayList<>();
        try {
            recallMemory(execution, warnings);
            loop.accept(execution);
            commit(execution, execution.turn().complete());
        } catch (RuntimeException | Error error) {
            recordFailure(execution, error);
            throw error;
        }

        observeMemory(execution, warnings);
        ConversationTurn completed = execution.turn();
        log.info("turnId={} stage=run event=complete modelCalls={}", completed.id(), execution.modelCalls());
        return new Reply(conversationId, completed.id(), completed.messages().getLast().text(),
                execution.modelCalls(), List.copyOf(warnings), completed.manifest().releaseId(),
                completed.manifest().fingerprint());
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
        execution.restored(history);
        log.info("turnId={} stage=restore turns={}", execution.turn().id(), history.size());
    }

    private void recallMemory(AgentExecution execution, List<String> warnings) {
        try {
            String query = execution.turn().messages().getFirst().text();
            execution.recalled(memory.recall(execution.conversationId(), query));
            List<String> hashes = execution.memories().stream()
                    .map(note -> Fingerprints.sha256(note.getBytes(StandardCharsets.UTF_8))).toList();
            log.info("turnId={} stage=memory.recall recalledHashes={}", execution.turn().id(), hashes);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException error) {
            log.warn("turnId={} stage=memory.recall event=failed", execution.turn().id(), error);
            warnings.add("记忆读取失败，本轮使用对话记录继续。");
        }
    }

    private void observeMemory(AgentExecution execution, List<String> warnings) {
        try {
            memory.observe(execution.conversationId(), execution.turn());
            log.info("turnId={} stage=memory.observe event=complete", execution.turn().id());
        } catch (RuntimeException error) {
            // 回答已经提交，记忆更新失败不回滚回答，也不重跑模型。
            log.warn("turnId={} stage=memory.observe event=failed", execution.turn().id(), error);
            warnings.add("回答已保存，但本轮长期记忆更新失败。");
        }
    }

    public ChatResponse aroundReasoning(AgentExecution execution, Function<Prompt, ChatResponse> reasoning) {
        ContextBuilder.ModelContext view;
        try {
            view = context.prepare(execution.history(), execution.memories(), execution.turn().messages());
        } catch (RuntimeException | Error error) {
            log.warn("turnId={} stage=context event=failed", execution.turn().id(), error);
            throw error;
        }
        logContextSelection(execution, view);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.callbacks()).internalToolExecutionEnabled(false).build();
        Prompt prompt = new Prompt(view.messages(), options);
        return reasoning.apply(prompt);
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
        log.info("turnId={} stage=tools calls={}", execution.turn().id(),
                assistant.getToolCalls().stream().map(call -> call.id() + ":" + call.name()).toList());
        ToolContext toolContext = new ToolContext(Map.of("conversationId", execution.conversationId(),
                "turnId", execution.turn().id().toString()));
        try {
            var results = tools.execute(assistant.getToolCalls(), toolContext);
            ToolResponseMessage response = ToolResponseMessage.builder().responses(results).build();
            checkpoint(execution, TranscriptMessage.from(response));
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
        String reason = error instanceof AgentException ? error.getMessage() : "执行失败，详细原因见服务日志。";
        try {
            commit(execution, execution.turn().fail(reason));
        } catch (RuntimeException | Error persistenceError) {
            error.addSuppressed(persistenceError);
        }
        log.warn("turnId={} stage=run event=failed", execution.turn().id(), error);
    }

    private void logContextSelection(AgentExecution execution, ContextBuilder.ModelContext view) {
        try {
            if (view.selection().isEmpty()) {
                log.info("turnId={} stage=context selection=unreported messages={}", execution.turn().id(), view.messages().size());
                return;
            }
            ContextBuilder.Selection selected = view.selection().get();
            List<UUID> droppedTurns = execution.history().stream().map(ConversationTurn::id)
                    .filter(id -> !selected.turnIds().contains(id)).toList();
            List<Integer> droppedMemories = java.util.stream.IntStream.range(0, execution.memories().size())
                    .filter(index -> !selected.memoryIndexes().contains(index)).boxed().toList();
            log.info("turnId={} stage=context selectedTurns={} droppedTurns={} selectedMemories={} droppedMemories={} messages={}",
                    execution.turn().id(), selected.turnIds(), droppedTurns, selected.memoryIndexes(), droppedMemories, view.messages().size());
        } catch (RuntimeException ignored) {
            // 选择证据仅供诊断；它的计算失败不影响模型输入和权威检查点。
        }
    }

    public record Reply(String conversationId, UUID turnId, String answer, int modelCalls, List<String> warnings,
            String releaseId, String configurationId) {}
}
