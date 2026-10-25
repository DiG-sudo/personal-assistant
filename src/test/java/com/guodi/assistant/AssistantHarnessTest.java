package com.guodi.assistant;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.memory.NoMemory;
import com.guodi.assistant.component.tool.RegisteredToolExecutor;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationGate;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.MemoryModule;

import static org.assertj.core.api.Assertions.*;

class AssistantHarnessTest {
    private final TestSupport.Store store = new TestSupport.Store();

    @Test
    void restoresCompletedConversationAndPersistsPlainTextAnswers() {
        var calls = new AtomicInteger();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 2) {
                assertThat(prompt.getInstructions().stream()
                        .filter(message -> message instanceof AssistantMessage).map(message -> message.getText()))
                        .containsExactly("answer-1");
            }
            return TestSupport.text("answer-" + calls.get());
        };
        harness(model, new NoMemory()).chat("a", "first");
        // A fresh Harness instance uses only durable state, not an old in-memory execution.
        var result = harness(model, new NoMemory()).chat("a", "second");
        assertThat(result.answer()).isEqualTo("answer-2");
        assertThat(store.load("a")).hasSize(2).allMatch(turn -> turn.status() == ConversationTurn.Status.COMPLETED);
        assertThat(store.load("other")).isEmpty();
    }

    @Test
    void memoryObserveFailureDoesNotUndoCompletedAnswerOrCallTheModelAgain() {
        var modelCalls = new AtomicInteger();
        MemoryModule brokenMemory = new MemoryModule() {
            public List<String> recall(String id, String query) { return List.of("prefers Chinese"); }
            public void observe(String id, ConversationTurn turn) { throw new IllegalStateException("memory unavailable"); }
        };
        var reply = harness(prompt -> {
            modelCalls.incrementAndGet();
            assertThat(prompt.getContents()).contains("prefers Chinese");
            return TestSupport.text("已回答");
        }, brokenMemory).chat("a", "hello");
        assertThat(reply.warnings()).singleElement().asString().contains("更新失败");
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.COMPLETED);
        assertThat(modelCalls.get()).isEqualTo(1);
    }

    @Test
    void memoryRecallFailureDegradesToConversationHistory() {
        MemoryModule brokenMemory = new MemoryModule() {
            public List<String> recall(String id, String query) { throw new IllegalStateException("unavailable"); }
            public void observe(String id, ConversationTurn turn) {}
        };
        var reply = harness(prompt -> TestSupport.text("ok"), brokenMemory).chat("a", "hello");
        assertThat(reply.answer()).isEqualTo("ok");
        assertThat(reply.warnings()).singleElement().asString().contains("读取失败");
    }

    @Test
    void restartMarksOpenTurnsFailedWithoutReplayingTheirTools() {
        var open = ConversationTurn.start("unfinished", TestSupport.manifest());
        var intent = TranscriptMessage.from(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("old", "function", "external_write", "{}"))).build());
        store.save("a", open.checkpoint(List.of(open.messages().getFirst(), intent)));
        var calls = new AtomicInteger();
        harness(prompt -> {
            calls.incrementAndGet();
            assertThat(prompt.getContents()).doesNotContain("unfinished");
            return TestSupport.text("new answer");
        }, new NoMemory()).chat("a", "new request");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(store.load("a").getFirst().messages().getLast().toolCalls().getFirst().name())
                .isEqualTo("external_write");
    }

    @Test
    void modelFailureIsRecordedAndDoesNotLeaveALiveTurn() {
        var harness = harness(prompt -> { throw new IllegalStateException("provider offline"); }, new NoMemory());
        assertThatThrownBy(() -> harness.chat("a", "hello")).hasMessageContaining("provider offline");
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(store.load("a").getFirst().messages().getFirst().text()).isEqualTo("hello");
    }

    @Test
    void rebuildsContextForEachStepWithoutSavingTheTransientView() {
        var builds = new AtomicInteger();
        var calls = new AtomicInteger();
        var counter = new AgentRuntimeTest.CountingTool();
        var tools = new RegisteredToolExecutor(List.of(org.springframework.ai.support.ToolCallbacks.from(counter)), 1000);
        var runtime = new AgentRuntime(prompt -> {
            int call = calls.incrementAndGet();
            assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo("view-" + call);
            if (call == 1) return TestSupport.tool("c1", "count");
            assertThat(prompt.getInstructions().getLast()).isInstanceOf(org.springframework.ai.chat.messages.ToolResponseMessage.class);
            return TestSupport.text("done");
        }, tools, 3, 10000, 4);
        com.guodi.assistant.port.ContextBuilder context = (history, memories, message) -> List.of(
                new org.springframework.ai.chat.messages.SystemMessage("view-" + builds.incrementAndGet()),
                new org.springframework.ai.chat.messages.UserMessage(message));
        var reply = new AssistantHarness(runtime, store, new NoMemory(), context,
                new ConversationGate(), TestSupport.manifest()).chat("a", "hello");
        assertThat(reply.modelCalls()).isEqualTo(2);
        assertThat(builds.get()).isEqualTo(2);
        assertThat(store.load("a").getFirst().messages()).extracting(TranscriptMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void resultSaveFailureKeepsOnlyCommittedIntentAndStopsBeforeAnotherModelCall() {
        var calls = new AtomicInteger();
        var counter = new AgentRuntimeTest.CountingTool();
        var tools = new RegisteredToolExecutor(List.of(org.springframework.ai.support.ToolCallbacks.from(counter)), 1000);
        com.guodi.assistant.port.ConversationStore brokenStore = new com.guodi.assistant.port.ConversationStore() {
            public List<ConversationTurn> load(String id) { return store.load(id); }
            public void save(String id, ConversationTurn turn) {
                if (turn.status() == ConversationTurn.Status.RUNNING && turn.messages().getLast().role().equals("tool")) {
                    throw new IllegalStateException("result save failed");
                }
                store.save(id, turn);
            }
        };
        var runtime = new AgentRuntime(prompt -> {
            calls.incrementAndGet();
            return TestSupport.tool("c1", "count");
        }, tools, 3, 10000, 4);
        var harness = new AssistantHarness(runtime, brokenStore, new NoMemory(),
                new WindowContextBuilder("system", 8, 6000, 1000), new ConversationGate(), TestSupport.manifest());
        assertThatThrownBy(() -> harness.chat("a", "hello")).hasMessageContaining("result save failed");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(counter.executions.get()).isEqualTo(1);
        var failed = store.load("a").getFirst();
        assertThat(failed.status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(failed.messages()).extracting(TranscriptMessage::role).containsExactly("user", "assistant");
    }

    @Test
    @Timeout(10)
    void sameConversationIsSerializedWhileAnotherConversationCanProgress() throws Exception {
        var firstInside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondInside = new CountDownLatch(1);
        var harness = harness(prompt -> {
            String message = prompt.getInstructions().getLast().getText();
            if (message.equals("first")) {
                firstInside.countDown();
                await(release);
            }
            if (message.equals("second")) {
                secondInside.countDown();
                assertThat(prompt.getInstructions().stream().filter(m -> m instanceof AssistantMessage).count()).isEqualTo(1);
            }
            return TestSupport.text("answer");
        }, new NoMemory());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> harness.chat("a", "first"));
                assertThat(firstInside.await(2, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return harness.chat("a", "second");
                });
                assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondInside.await(150, TimeUnit.MILLISECONDS)).isFalse();
                // "a" and "b" hash to different stripes.
                assertThat(executor.submit(() -> harness.chat("b", "other")).get(2, TimeUnit.SECONDS).answer()).isEqualTo("answer");
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
                second.get(2, TimeUnit.SECONDS);
                assertThat(store.load("a")).hasSize(2);
            } finally {
                release.countDown();
            }
        }
    }

    private AssistantHarness harness(ChatModel model, MemoryModule memory) {
        var tools = new RegisteredToolExecutor(List.of(), 1000);
        return new AssistantHarness(new AgentRuntime(model, tools, 4, 10000, 4), store, memory,
                new WindowContextBuilder("system", 8, 6000, 1000), new ConversationGate(), TestSupport.manifest());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("test latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
