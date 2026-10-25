package com.guodi.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.ConversationTurn.Status;
import com.guodi.assistant.port.ContextBuilder;
import com.guodi.assistant.port.ConversationStore;
import com.guodi.assistant.port.MemoryModule;
import com.guodi.assistant.versioning.ExecutionManifest;

/** Fixed lifecycle: serialize -> restore -> recall -> build -> run -> commit -> observe. */
public final class AssistantHarness {
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
        return gate.within(conversationId, () -> execute(conversationId, message));
    }

    public List<ConversationTurn> history(String conversationId) {
        return gate.within(conversationId, () -> conversations.load(conversationId));
    }

    private Reply execute(String conversationId, String message) {
        List<ConversationTurn> history = conversations.load(conversationId);
        // A restart can leave recorded intents with unknown external outcomes. Never replay them automatically.
        for (var previous : history) {
            if (previous.status() == Status.RUNNING) {
                conversations.save(conversationId, previous.fail("上次运行中断；未自动重放工具调用。"));
            }
        }

        var warnings = new ArrayList<String>();
        List<String> recalled;
        try {
            recalled = memory.recall(conversationId, message);
        } catch (RuntimeException error) {
            log.warn("Memory recall failed for conversation {}", conversationId, error);
            recalled = List.of();
            warnings.add("记忆读取失败，本轮使用对话记录继续。");
        }

        // The single mutable cursor always points to the latest persisted transcript.
        var current = new ConversationTurn[] { ConversationTurn.start(message, manifest) };
        conversations.save(conversationId, current[0]);
        int modelCalls;
        try {
            var modelContext = context.build(history, recalled, message);
            modelCalls = runtime.run(modelContext, conversationId, transcript -> {
                var checkpoint = current[0].checkpoint(transcript);
                conversations.save(conversationId, checkpoint);
                current[0] = checkpoint;
            });
            var completed = current[0].complete();
            conversations.save(conversationId, completed);
            current[0] = completed;
        } catch (RuntimeException error) {
            try {
                conversations.save(conversationId, current[0].fail(
                        error instanceof AgentException ? error.getMessage() : "执行失败，详细原因见服务日志。"));
            } catch (RuntimeException persistenceError) {
                error.addSuppressed(persistenceError);
            }
            log.warn("Turn {} failed in conversation {}", current[0].id(), conversationId, error);
            throw error;
        }

        try {
            memory.observe(conversationId, current[0]);
        } catch (RuntimeException error) {
            // The answer is already committed. Do not turn a memory failure into a failed chat or rerun the model.
            log.warn("Memory observe failed for turn {}", current[0].id(), error);
            warnings.add("回答已保存，但本轮长期记忆更新失败。");
        }
        return new Reply(conversationId, current[0].id(), current[0].messages().getLast().text(), modelCalls,
                List.copyOf(warnings), manifest.releaseId(), manifest.fingerprint());
    }

    public record Reply(String conversationId, UUID turnId, String answer, int modelCalls, List<String> warnings, String releaseId, String configurationId) {}
}
