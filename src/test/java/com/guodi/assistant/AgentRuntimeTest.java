package com.guodi.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.memory.NoMemory;
import com.guodi.assistant.component.tool.RegisteredToolExecutor;
import com.guodi.assistant.core.AgentException;
import com.guodi.assistant.core.AgentExecution;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.core.event.ModelStarted;
import com.guodi.assistant.core.event.RequestFailed;
import com.guodi.assistant.core.event.TurnCompleted;
import com.guodi.assistant.core.event.TurnStarted;
import com.guodi.assistant.core.event.TurnSteer;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.ConversationStore;

import static org.assertj.core.api.Assertions.*;

class AgentRuntimeTest {
    private final CountingTool counter = new CountingTool();
    private final RegisteredToolExecutor tools = new RegisteredToolExecutor(List.of(ToolCallbacks.from(counter)), 1000);

    @Test
    void plainTextEndsTheTurnAndDoesNotRequireATerminalTool() {
        ChatModel model = prompt -> {
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
            return TestSupport.text("你好");
        };
        var checkpoints = new ArrayList<List<TranscriptMessage>>();
        int modelCalls = run(model, 3, checkpoints::add);
        assertThat(modelCalls).isEqualTo(1);
        assertThat(checkpoints.getLast().getLast().text()).isEqualTo("你好");
        assertThat(checkpoints.getLast()).extracting(TranscriptMessage::role).containsExactly("user", "assistant");
    }

    @Test
    void journalsIntentBeforeExecutionAndKeepsToolPairsInOrder() {
        var calls = new AtomicInteger();
        var checkpoints = new ArrayList<List<TranscriptMessage>>();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 1) return TestSupport.tool("call-1", "count");
            assertThat(prompt.getInstructions().getLast()).isInstanceOf(ToolResponseMessage.class);
            return TestSupport.text("完成");
        };
        int modelCalls = run(model, 3, transcript -> {
            checkpoints.add(transcript);
            if (transcript.size() == 2) assertThat(counter.executions.get()).isZero();
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
        assertThatThrownBy(() -> run(model, 2, ignored -> {})).isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(counter.executions.get()).isEqualTo(1);
    }

    @Test
    void checkpointFailurePreventsExternalToolExecution() {
        assertThatThrownBy(() -> run(prompt -> TestSupport.tool("c", "count"), 3,
                ignored -> { throw new IllegalStateException("disk failure"); })).hasMessageContaining("disk failure");
        assertThat(counter.executions.get()).isZero();
    }

    @Test
    void rejectsEmptyProviderResponsesAndOversizedContextBeforeTools() {
        assertThatThrownBy(() -> run(prompt -> null, 3, ignored -> {})).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> run(prompt -> TestSupport.text(" "), 3, ignored -> {})).isInstanceOf(AgentException.class);
        var modelCalls = new AtomicInteger();
        var runtime = runtime(prompt -> {
            modelCalls.incrementAndGet();
            return TestSupport.text("unused");
        }, 3, 1, 4, ignored -> {});
        assertThatThrownBy(() -> runtime.send("chat", TestSupport.message("你好"))).isInstanceOf(AgentException.class).hasMessageContaining("上下文");
        assertThat(modelCalls.get()).isZero();
    }

    @Test
    void rejectsDuplicateToolIdsAndTooManyCallsBeforeExecution() {
        var call = new AssistantMessage.ToolCall("duplicate", "function", "count", "{}");
        ChatModel malformed = prompt -> new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call, call)).build())));
        assertThatThrownBy(() -> run(malformed, 3, ignored -> {})).isInstanceOf(AgentException.class).hasMessageContaining("重复");
        var limited = runtime(malformed, 3, 10000, 1, ignored -> {});
        assertThatThrownBy(() -> limited.send("chat", TestSupport.message("你好"))).isInstanceOf(AgentException.class).hasMessageContaining("数量");
        assertThat(counter.executions.get()).isZero();
    }

    @Test
    void repeatedActualCallsInsideAModelWrapperConsumeTheSameBudget() {
        var actualCalls = new AtomicInteger();
        var execution = new AtomicReference<AgentExecution>();
        var boundaries = new ArrayList<String>();
        var harness = new AssistantHarness(new TestSupport.Store(), new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest()) {
            @Override
            public ChatResponse aroundReasoning(AgentExecution run, Function<Prompt, ChatResponse> reasoning) {
                boundaries.add("reasoning");
                return super.aroundReasoning(run, reasoning);
            }
            @Override
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                execution.set(run);
                boundaries.add("model");
                try {
                    return call.apply(request);
                } catch (IllegalStateException failure) {
                    return call.apply(request);
                }
            }
        };
        var runtime = new ConversationService(new AgentRuntime(prompt -> {
            actualCalls.incrementAndGet();
            throw new IllegalStateException("provider failure");
        }, harness, 1, 10000, 4));
        assertThatThrownBy(() -> runtime.send("chat", TestSupport.message("hello"))).isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(actualCalls.get()).isEqualTo(1);
        assertThat(execution.get().modelCalls()).isEqualTo(1);
        assertThat(boundaries).containsExactly("reasoning", "model");
    }


    @Test
    void finalAnswerRechecksSteersAndAppliesOneBeforeEachFollowingReasoningStep() {
        var stored = new TestSupport.Store();
        var calls = new AtomicInteger();
        var first = TestSupport.message("first supplement");
        var second = TestSupport.message("second supplement");
        ChatModel model = prompt -> {
            int call = calls.incrementAndGet();
            if (call == 2) assertThat(prompt.getInstructions().getLast().getText()).isEqualTo(first.text());
            if (call == 3) assertThat(prompt.getInstructions().getLast().getText()).isEqualTo(second.text());
            return TestSupport.text("answer-" + call);
        };
        var harness = new AssistantHarness(stored, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest()) {
            @Override
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                ChatResponse response = super.aroundModelCall(run, request, call);
                if (run.modelCalls() == 1) {
                    run.steers().add(first);
                    run.steers().add(second);
                }
                return response;
            }
        };
        var conversations = new ConversationService(new AgentRuntime(model, harness, 4, 10000, 4));

        var events = new ArrayList<AgentEvent>();
        var reply = conversations.send("steer-final", TestSupport.message("original"), events::add);

        assertThat(reply.answer()).isEqualTo("answer-3");
        assertThat(reply.modelCalls()).isEqualTo(3);
        var turn = stored.load("steer-final").getFirst();
        assertThat(events).containsExactly(new TurnStarted(turn.id()), new ModelStarted(1),
                new TurnSteer(turn.id(), first.messageId()), new ModelStarted(2),
                new TurnSteer(turn.id(), second.messageId()), new ModelStarted(3), new TurnCompleted(reply));
        assertThat(turn.status()).isEqualTo(ConversationTurn.Status.COMPLETED);
        assertThat(turn.messages()).extracting(TranscriptMessage::text)
                .containsExactly("original", "answer-1", first.text(), "answer-2", second.text(), "answer-3");
        assertThat(turn.manifest()).isEqualTo(TestSupport.manifest());
    }

    @Test
    void appliesSteerAfterTheCompleteToolExchange() {
        var stored = new TestSupport.Store();
        var calls = new AtomicInteger();
        var supplement = TestSupport.message("supplement after tools");
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 1) return TestSupport.tool("steer-tool", "count");
            assertThat(prompt.getInstructions().getLast().getText()).isEqualTo(supplement.text());
            assertThat(prompt.getInstructions().get(prompt.getInstructions().size() - 2))
                    .isInstanceOf(ToolResponseMessage.class);
            return TestSupport.text("done");
        };
        var harness = new AssistantHarness(stored, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest()) {
            @Override
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                ChatResponse response = super.aroundModelCall(run, request, call);
                if (run.modelCalls() == 1) run.steers().add(supplement);
                return response;
            }
        };
        var conversations = new ConversationService(new AgentRuntime(model, harness, 3, 10000, 4));

        conversations.send("steer-tools", TestSupport.message("original"));

        var messages = stored.load("steer-tools").getFirst().messages();
        assertThat(messages).extracting(TranscriptMessage::role)
                .containsExactly("user", "assistant", "tool", "user", "assistant");
        assertThat(messages.get(2).toolResponses().getFirst().id()).isEqualTo("steer-tool");
        assertThat(counter.executions.get()).isEqualTo(1);
    }

    @Test
    void steerDoesNotResetTheModelCallBudget() {
        var stored = new TestSupport.Store();
        var calls = new AtomicInteger();
        var harness = new AssistantHarness(stored, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest()) {
            @Override
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                ChatResponse response = super.aroundModelCall(run, request, call);
                run.steers().add(TestSupport.message("late supplement"));
                return response;
            }
        };
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return TestSupport.text("answer");
        };
        var conversations = new ConversationService(new AgentRuntime(model, harness, 1, 10000, 4));

        var events = new ArrayList<AgentEvent>();
        assertThatThrownBy(() -> conversations.send("steer-budget", TestSupport.message("original"), events::add))
                .isInstanceOf(AgentException.class).hasMessageContaining("预算");
        assertThat(calls.get()).isEqualTo(1);
        var turn = stored.load("steer-budget").getFirst();
        assertThat(turn.status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(events).containsExactly(new TurnStarted(turn.id()), new ModelStarted(1), new RequestFailed(turn.failure()));
    }

    @Test
    void failedSteerCheckpointStopsBeforeAnotherModelCallAndKeepsTheInputUnacknowledged() {
        var stored = new TestSupport.Store();
        var calls = new AtomicInteger();
        var execution = new AtomicReference<AgentExecution>();
        var supplement = TestSupport.message("unsaved supplement");
        ConversationStore failing = new ConversationStore() {
            public List<ConversationTurn> load(String id) { return stored.load(id); }
            public void save(String id, ConversationTurn turn) {
                if (turn.messages().getLast().text().equals(supplement.text())) {
                    throw new IllegalStateException("steer save failed");
                }
                stored.save(id, turn);
            }
        };
        var harness = new AssistantHarness(failing, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest()) {
            @Override
            public ChatResponse aroundModelCall(AgentExecution run, Prompt request, Function<Prompt, ChatResponse> call) {
                execution.set(run);
                ChatResponse response = super.aroundModelCall(run, request, call);
                run.steers().add(supplement);
                return response;
            }
        };
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return TestSupport.text("answer");
        };
        var conversations = new ConversationService(new AgentRuntime(model, harness, 3, 10000, 4));

        assertThatThrownBy(() -> conversations.send("steer-save", TestSupport.message("original")))
                .hasMessageContaining("steer save failed");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(execution.get().steers()).containsExactly(supplement);
        assertThat(stored.load("steer-save").getFirst().messages()).extracting(TranscriptMessage::text)
                .containsExactly("original", "answer");
    }
    private int run(ChatModel model, int maxCalls, Consumer<List<TranscriptMessage>> checkpoint) {
        return runtime(model, maxCalls, 10000, 4, checkpoint).send("chat", TestSupport.message("你好")).modelCalls();
    }

    private ConversationService runtime(ChatModel model, int maxCalls, int maxChars, int maxTools,
            Consumer<List<TranscriptMessage>> checkpoint) {
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
        var harness = new AssistantHarness(journal, new NoMemory(),
                (history, memories, message) -> List.of(new UserMessage(message)),
                tools, TestSupport.manifest());
        return new ConversationService(new AgentRuntime(model, harness, maxCalls, maxChars, maxTools));
    }

    static final class CountingTool {
        final AtomicInteger executions = new AtomicInteger();
        @Tool(name = "count", description = "Count test executions")
        public String count() { return Integer.toString(executions.incrementAndGet()); }
    }
}
