package com.guodi.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.tool.RegisteredToolExecutor;
import com.guodi.assistant.core.AgentException;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.domain.TranscriptMessage;

import static org.assertj.core.api.Assertions.*;

class AgentRuntimeTest {
    private final CountingTool counter = new CountingTool();
    private final RegisteredToolExecutor tools = new RegisteredToolExecutor(
            List.of(ToolCallbacks.from(counter)), 1000);

    @Test
    void plainTextEndsTheTurnAndDoesNotRequireATerminalTool() {
        ChatModel model = prompt -> {
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
            return TestSupport.text("你好");
        };
        var checkpoints = new ArrayList<List<TranscriptMessage>>();
        int modelCalls = runtime(model, 3).run(context(), "chat", checkpoints::add);
        assertThat(modelCalls).isEqualTo(1);
        assertThat(checkpoints.getLast().getLast().text()).isEqualTo("你好");
        assertThat(checkpoints.getLast()).extracting(TranscriptMessage::role).containsExactly("user", "assistant");
    }

    @Test
    void journalsIntentBeforeExecutionAndKeepsToolPairsInOrder() {
        var calls = new AtomicInteger();
        var checkpoints = new ArrayList<List<TranscriptMessage>>();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 1) {
                return TestSupport.tool("call-1", "count");
            }
            assertThat(prompt.getInstructions().getLast()).isInstanceOf(ToolResponseMessage.class);
            return TestSupport.text("完成");
        };
        int modelCalls = runtime(model, 3).run(context(), "chat", transcript -> {
            checkpoints.add(transcript);
            if (transcript.size() == 2) {
                assertThat(counter.executions.get()).isZero();
            }
        });
        assertThat(modelCalls).isEqualTo(2);
        assertThat(checkpoints.getLast()).extracting(TranscriptMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(checkpoints.getLast().get(2).toolResponses().getFirst().id()).isEqualTo("call-1");
        assertThat(counter.executions.get()).isEqualTo(1);
        assertThat(checkpoints.getFirst().getLast().toolCalls()).hasSize(1);
    }

    @Test
    void budgetStopsBeforeExecutingToolsWithoutRoomForAFinalAnswer() {
        var calls = new AtomicInteger();
        ChatModel model = prompt -> TestSupport.tool("call-" + calls.incrementAndGet(), "count");
        assertThatThrownBy(() -> runtime(model, 2).run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(counter.executions.get()).isEqualTo(1);
    }

    @Test
    void checkpointFailurePreventsExternalToolExecution() {
        assertThatThrownBy(() -> runtime(prompt -> TestSupport.tool("c", "count"), 3)
                .run(context(), "chat", ignored -> { throw new IllegalStateException("disk failure"); }))
                .hasMessageContaining("disk failure");
        assertThat(counter.executions.get()).isZero();
    }

    @Test
    void rejectsEmptyProviderResponsesAndOversizedContextBeforeTools() {
        assertThatThrownBy(() -> runtime(prompt -> null, 3).run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> runtime(prompt -> TestSupport.text(" "), 3).run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class);
        var modelCalls = new AtomicInteger();
        var runtime = new AgentRuntime(prompt -> {
            modelCalls.incrementAndGet();
            return TestSupport.text("unused");
        }, tools, 3, 1, 4);
        assertThatThrownBy(() -> runtime.run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("上下文");
        assertThat(modelCalls.get()).isZero();
    }

    @Test
    void rejectsDuplicateToolIdsAndTooManyCallsBeforeExecution() {
        var call = new AssistantMessage.ToolCall("duplicate", "function", "count", "{}");
        ChatModel malformed = prompt -> new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(AssistantMessage.builder()
                        .content("").toolCalls(List.of(call, call)).build())));
        assertThatThrownBy(() -> runtime(malformed, 3).run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("重复");
        var limited = new AgentRuntime(malformed, tools, 3, 10000, 1);
        assertThatThrownBy(() -> limited.run(context(), "chat", ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("数量");
        assertThat(counter.executions.get()).isZero();
    }

    private AgentRuntime runtime(ChatModel model, int maxCalls) {
        return new AgentRuntime(model, tools, maxCalls, 10000, 4);
    }

    private List<Message> context() {
        return List.of(new UserMessage("你好"));
    }

    static final class CountingTool {
        final AtomicInteger executions = new AtomicInteger();
        @Tool(name = "count", description = "Count test executions")
        public String count() {
            return Integer.toString(executions.incrementAndGet());
        }
    }
}
