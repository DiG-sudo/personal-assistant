package com.guodi.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.tool.RegisteredToolExecutor;
import com.guodi.assistant.core.AgentException;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AgentExecution;
import com.guodi.assistant.core.RuntimeHooks;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationGate;
import com.guodi.assistant.component.memory.NoMemory;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.ConversationStore;
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
        int modelCalls = run(runtime(model, 3), checkpoints::add);
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
        int modelCalls = run(runtime(model, 3), transcript -> {
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
        assertThatThrownBy(() -> run(runtime(model, 2), ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(counter.executions.get()).isEqualTo(1);
    }

    @Test
    void checkpointFailurePreventsExternalToolExecution() {
        assertThatThrownBy(() -> run(runtime(prompt -> TestSupport.tool("c", "count"), 3),
                        ignored -> { throw new IllegalStateException("disk failure"); }))
                .hasMessageContaining("disk failure");
        assertThat(counter.executions.get()).isZero();
    }

    @Test
    void rejectsEmptyProviderResponsesAndOversizedContextBeforeTools() {
        assertThatThrownBy(() -> run(runtime(prompt -> null, 3), ignored -> {}))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> run(runtime(prompt -> TestSupport.text(" "), 3), ignored -> {}))
                .isInstanceOf(AgentException.class);
        var modelCalls = new AtomicInteger();
        var runtime = new AgentRuntime(prompt -> {
            modelCalls.incrementAndGet();
            return TestSupport.text("unused");
        }, tools, 3, 1, 4);
        assertThatThrownBy(() -> run(runtime, ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("上下文");
        assertThat(modelCalls.get()).isZero();
    }

    @Test
    void rejectsDuplicateToolIdsAndTooManyCallsBeforeExecution() {
        var call = new AssistantMessage.ToolCall("duplicate", "function", "count", "{}");
        ChatModel malformed = prompt -> new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(AssistantMessage.builder()
                        .content("").toolCalls(List.of(call, call)).build())));
        assertThatThrownBy(() -> run(runtime(malformed, 3), ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("重复");
        var limited = new AgentRuntime(malformed, tools, 3, 10000, 1);
        assertThatThrownBy(() -> run(limited, ignored -> {}))
                .isInstanceOf(AgentException.class).hasMessageContaining("数量");
        assertThat(counter.executions.get()).isZero();
    }

    @Test
    void repeatedActualCallsInsideAModelWrapperConsumeTheSameBudget() {
        var actualCalls = new AtomicInteger();
        var runtime = runtime(prompt -> {
            actualCalls.incrementAndGet();
            throw new IllegalStateException("provider failure");
        }, 1);
        var execution = new AgentExecution("chat",
                ConversationTurn.start("hello", TestSupport.manifest()));
        var boundaries = new ArrayList<String>();
        var hooks = new RuntimeHooks() {
            public ChatResponse aroundReasoning(AgentExecution run, Function<List<Message>, ChatResponse> reasoning) {
                boundaries.add("reasoning");
                return reasoning.apply(List.of(new UserMessage("hello")));
            }
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                boundaries.add("model");
                try {
                    return call.apply(request);
                } catch (IllegalStateException failure) {
                    return call.apply(request);
                }
            }
            public void checkpoint(AgentExecution run, TranscriptMessage message) {
                throw new AssertionError("No response may be committed after the provider failure");
            }
        };
        assertThatThrownBy(() -> runtime.run(execution, hooks)).isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(actualCalls.get()).isEqualTo(1);
        assertThat(execution.modelCalls()).isEqualTo(1);
        assertThat(boundaries).containsExactly("reasoning", "model");
    }

    private AgentRuntime runtime(ChatModel model, int maxCalls) {
        return new AgentRuntime(model, tools, maxCalls, 10000, 4);
    }

    private int run(AgentRuntime runtime, Consumer<List<TranscriptMessage>> checkpoint) {
        var stored = new TestSupport.Store();
        ConversationStore journal = new ConversationStore() {
            public List<ConversationTurn> load(String id) { return stored.load(id); }
            public void save(String id, ConversationTurn turn) {
                if (turn.status() == ConversationTurn.Status.RUNNING && turn.messages().size() > 1) {
                    checkpoint.accept(turn.messages());
                }
                stored.save(id, turn);
            }
        };
        return new AssistantHarness(runtime, journal, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                new ConversationGate(), TestSupport.manifest()).chat("chat", "你好").modelCalls();
    }

    static final class CountingTool {
        final AtomicInteger executions = new AtomicInteger();
        @Tool(name = "count", description = "Count test executions")
        public String count() {
            return Integer.toString(executions.incrementAndGet());
        }
    }
}
