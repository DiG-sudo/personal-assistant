package com.guodi.assistant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolExecutor;
import com.guodi.assistant.component.tool.ToolRegistry;

import static org.assertj.core.api.Assertions.*;

class ToolExecutorTest {
    private final ToolContext context = new ToolContext(Map.of());

    @Test
    void registryControlsExposureAndExecution() {
        var callback = ToolCallbacks.from(new SampleTool())[0];
        var registry = TestSupport.toolRegistry(callback);
        var executor = new ToolExecutor(registry, 1000);
        assertThat(registry.callbacks()).containsExactly(callback);

        var missing = executor.execute(new ToolCall("one", "function", "missing", "{}"), context);
        assertThat(missing.success()).isFalse();
        assertThat(missing.responseData()).contains("未启用");

        var result = executor.execute(new ToolCall("two", "function", "sample", "{}"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.toolResponse().id()).isEqualTo("two");
        assertThat(result.responseData()).isEqualTo("\"sample result\"");
    }

    @Test
    void duplicateRegistrationFailsEarlyAndToolResultsAreBounded() {
        var callback = ToolCallbacks.from(new SampleTool())[0];
        var binding = new ToolBinding(ToolBinding.Kind.READ, callback);
        assertThatThrownBy(() -> new ToolRegistry(List.of(binding, binding))).hasMessageContaining("Duplicate");
        var executor = new ToolExecutor(new ToolRegistry(List.of(binding)), 5);
        var result = executor.execute(new ToolCall("c", "function", "sample", "{}"), context);
        assertThat(result.responseData()).isEqualTo("\"samp\n[工具结果已截断]");
    }

    @Test
    void reportsOrdinaryFailuresButPropagatesCancellation() {
        var callbacks = ToolCallbacks.from(new Object() {
            @Tool(name = "reject", description = "reject")
            public String reject() {
                throw new IllegalArgumentException("invalid input");
            }

            @Tool(name = "cancel", description = "cancel")
            public String cancel() {
                throw new CancellationException("stopped");
            }
        });
        var executor = new ToolExecutor(TestSupport.toolRegistry(callbacks), 1000);

        var failure = executor.execute(new ToolCall("a", "function", "reject", "{}"), context);
        assertThat(failure.success()).isFalse();
        assertThat(failure.responseData()).contains("工具执行失败");
        assertThatThrownBy(() -> executor.execute(new ToolCall("b", "function", "cancel", "{}"), context))
                .isInstanceOf(CancellationException.class);
    }

    static final class SampleTool {
        @Tool(name = "sample", description = "sample")
        public String sample() {
            return "sample result";
        }
    }
}
