package com.guodi.assistant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolBinding.Kind;
import com.guodi.assistant.component.tool.ToolExecutor;
import com.guodi.assistant.component.tool.ToolOutcome;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.component.tool.ToolRoundExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolRoundExecutorTest {
    @Test
    @Timeout(3)
    void submitsConsecutiveReadsTogetherAndPreservesWriteAndShellBarriers() {
        var tool = new WorkspaceTool();
        var callbacks = ToolCallbacks.from(tool);
        var bindings = java.util.Arrays.stream(callbacks)
                .map(callback -> new ToolBinding(switch (callback.getToolDefinition().name()) {
                    case "read" -> Kind.READ;
                    case "write" -> Kind.WRITE;
                    default -> Kind.SHELL;
                }, callback))
                .toList();
        var registry = new ToolRegistry(bindings);
        var workers = mock(ExecutorService.class);
        var pending = new java.util.ArrayList<Callable<ToolOutcome>>();
        var results = new java.util.ArrayList<CompletableFuture<ToolOutcome>>();
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<ToolOutcome>>any())).thenAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            var result = new CompletableFuture<ToolOutcome>();
            results.add(result);
            if (pending.size() == 2) {
                results.get(1).complete(pending.get(1).call());
                results.get(0).complete(pending.get(0).call());
            }
            return result;
        });

        var executor = new ToolRoundExecutor(registry, new ToolExecutor(registry, 1000), workers);
        var outcomes = executor.executeRound(List.of(
                call("1", "read", "{\"label\":\"A\"}"), call("2", "read", "{\"label\":\"B\"}"),
                call("3", "write", "{}"), call("4", "read", "{\"label\":\"C\"}"),
                call("5", "shell", "{}"), call("6", "read", "{\"label\":\"D\"}")),
                new ToolContext(Map.of()));

        verify(workers, times(2)).submit(org.mockito.ArgumentMatchers.<Callable<ToolOutcome>>any());
        assertThat(outcomes).extracting(outcome -> outcome.call().id()).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(outcomes).extracting(ToolOutcome::responseData)
                .containsExactly("\"A:initial\"", "\"B:initial\"", "\"written\"", "\"C:written\"", "\"shell\"", "\"D:shell\"");
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, "function", name, arguments);
    }

    static final class WorkspaceTool {
        private String content = "initial";

        @Tool(name = "read", description = "Read the fixture value")
        public String read(String label) {
            return label + ":" + content;
        }

        @Tool(name = "write", description = "Write the fixture value")
        public String write() {
            content = "written";
            return content;
        }

        @Tool(name = "shell", description = "Simulate a shell tool changing the fixture value")
        public String shell() {
            content = "shell";
            return content;
        }
    }
}
