package com.guodi.assistant;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.tool.RegisteredToolExecutor;
import com.guodi.assistant.component.tool.VideoSearchLinksTool;

import static org.assertj.core.api.Assertions.*;

class RegisteredToolExecutorTest {
    @Test
    void enabledSetControlsExposureAndExecutionAndPreservesCallOrder() {
        var callback = ToolCallbacks.from(new SampleTool())[0];
        var executor = new RegisteredToolExecutor(List.of(callback), 1000);
        assertThat(executor.callbacks()).containsExactly(callback);
        var alpha = ToolCallbacks.from(new Object() {
            @Tool(name = "alpha", description = "alpha")
            public String alpha() { return "alpha"; }
        })[0];
        assertThat(new RegisteredToolExecutor(List.of(callback, alpha), 1000).callbacks())
                .extracting(tool -> tool.getToolDefinition().name()).containsExactly("alpha", "sample");
        var results = executor.execute(List.of(
                new ToolCall("one", "function", "missing", "{}"),
                new ToolCall("two", "function", "sample", "{}")), new ToolContext(Map.of()));
        assertThat(results).extracting(result -> result.id()).containsExactly("one", "two");
        assertThat(results.getFirst().responseData()).contains("未启用");
        assertThat(results.getLast().responseData()).contains("sample result");
        var disabled = new RegisteredToolExecutor(List.of(), 1000);
        assertThat(disabled.callbacks()).isEmpty();
        assertThat(disabled.execute(List.of(new ToolCall("c", "function", "sample", "{}")),
                new ToolContext(Map.of())).getFirst().responseData()).contains("未启用");
    }

    @Test
    void duplicateRegistrationFailsEarlyAndToolResultsAreBounded() {
        var callback = ToolCallbacks.from(new SampleTool())[0];
        assertThatThrownBy(() -> new RegisteredToolExecutor(List.of(callback, callback), 100))
                .hasMessageContaining("Duplicate");
        var executor = new RegisteredToolExecutor(List.of(callback), 5);
        assertThat(executor.execute(List.of(new ToolCall("c", "function", "sample", "{}")),
                new ToolContext(Map.of())).getFirst().responseData()).endsWith("[工具结果已截断]");
    }

    @Test
    void toolParameterErrorsBecomeResultsSoTheModelCanCorrectThem() {
        var executor = new RegisteredToolExecutor(List.of(ToolCallbacks.from(new VideoSearchLinksTool())), 1000);
        var result = executor.execute(List.of(new ToolCall("c", "function", "video_search_links",
                "{\"query\":\"\"}")), new ToolContext(Map.of())).getFirst();
        assertThat(result.responseData()).contains("工具执行失败");
    }

    @Test
    void newExecutorPropagatesCancellationWhileTheReleasedVersionKeepsItsBehavior() {
        var callback = ToolCallbacks.from(new Object() {
            @Tool(name = "cancel", description = "cancel")
            public String cancel() { throw new java.util.concurrent.CancellationException("stopped"); }
        })[0];
        var calls = List.of(new ToolCall("c", "function", "cancel", "{}"));
        var context = new ToolContext(Map.of());
        assertThat(new RegisteredToolExecutor(List.of(callback), 1000).execute(calls, context)
                .getFirst().responseData()).contains("工具执行失败");
        var next = new com.guodi.assistant.component.tool.CancellableToolExecutor(List.of(callback), 1000);
        assertThatThrownBy(() -> next.execute(calls, context)).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void videoExampleProducesEncodedSearchEntriesOnly() {
        var result = new VideoSearchLinksTool().search("Java & 并发");
        assertThat(result.get("youtube")).contains("Java+%26+%E5%B9%B6%E5%8F%91");
        assertThat(result).containsOnlyKeys("youtube", "bilibili");
    }

    static final class SampleTool {
        @Tool(name = "sample", description = "sample")
        public String sample() { return "sample result"; }
    }
}
