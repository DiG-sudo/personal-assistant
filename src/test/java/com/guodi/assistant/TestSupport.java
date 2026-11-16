package com.guodi.assistant;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import com.guodi.assistant.config.AssistantProperties.Budget;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.assistant.component.context.ContextView;
import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.storage.ToolResultStore;
import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolExecutor;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.component.tool.ToolRoundExecutor;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.JsonFiles;

final class TestSupport {
    static com.guodi.assistant.core.ConversationService.SessionMessage message(String text) {
        return message(text, Path.of("").toAbsolutePath().normalize());
    }

    static com.guodi.assistant.core.ConversationService.SessionMessage message(String text, Path projectPath) {
        return new com.guodi.assistant.core.ConversationService.SessionMessage(java.util.UUID.randomUUID(), text, projectPath);
    }

    static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static ChatResponse tool(String id, String name) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}"))).build())));
    }

    static ConversationTurn completed(String question, String answer) {
        return ConversationTurn.start(question).checkpoint(List.of(
                TranscriptMessage.from(new UserMessage(question)),
                TranscriptMessage.from(new AssistantMessage(answer)))).complete();
    }

    static ContextView.Summary summary(String goal) {
        return new ContextView.Summary(goal, List.of(),
                new ContextView.Progress(List.of(), List.of(), List.of()),
                List.of(), List.of(), List.of());
    }

    static String summaryJson(String goal) {
        try {
            return new ObjectMapper().writeValueAsString(summary(goal));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    static Budget budget(int windowTokens) {
        return new Budget(windowTokens, 0.8, 0.15, Math.min(128, windowTokens / 4),
                Math.min(256, windowTokens / 4), 8192, 4096, 100, 8);
    }

    static WindowContextBuilder contextBuilder() {
        return contextBuilder(new ToolResultStore(), null);
    }

    static WindowContextBuilder contextBuilder(ToolResultStore results, Integer previewTokens) {
        var budget = budget(262144);
        if (previewTokens != null) {
            budget = new Budget(budget.windowTokens(), budget.compactRatio(), budget.keepRecentRatio(),
                    budget.maxOutputTokens(), budget.summaryOutputTokens(), previewTokens * 2, previewTokens,
                    budget.maxModelCalls(), budget.maxToolsPerRound());
        }
        return new WindowContextBuilder(results, new ObjectMapper(), budget);
    }

    static ChatResponse unexpectedModelCall(Prompt prompt) {
        throw new AssertionError("Unexpected model request");
    }

    static ToolRegistry toolRegistry(ToolCallback... callbacks) {
        return new ToolRegistry(Arrays.stream(callbacks)
                .map(callback -> new ToolBinding(ToolBinding.Kind.WRITE, callback))
                .toList());
    }

    static ToolRoundExecutor toolRounds(ToolRegistry registry) {
        return new ToolRoundExecutor(registry, new ToolExecutor(registry),
                org.mockito.Mockito.mock(java.util.concurrent.ExecutorService.class));
    }

    static com.guodi.assistant.component.storage.FileContextStore contextStore(Path root) {
        return new com.guodi.assistant.component.storage.FileContextStore(root.resolve("contexts"),
                new ObjectMapper().findAndRegisterModules());
    }

    static FileConversationStore store(Path root) {
        return new FileConversationStore(root, new JsonFiles(new ObjectMapper().findAndRegisterModules()));
    }

    static com.guodi.assistant.core.ConversationService conversations(Path directory,
            com.guodi.assistant.core.AgentRuntime runtime) {
        var sessions = new com.guodi.assistant.component.storage.FileSessionStore(directory.resolve("sessions"),
                new JsonFiles(new ObjectMapper().findAndRegisterModules()));
        return new com.guodi.assistant.core.ConversationService(runtime, sessions, store(directory));
    }
}
