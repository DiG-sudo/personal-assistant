package com.guodi.assistant;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.memory.NoteMemory;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.component.storage.FileConversationStore;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantHarnessTest {
    @TempDir Path directory;
    private FileConversationStore store;

    @org.junit.jupiter.api.BeforeEach
    void createStore() {
        store = TestSupport.store(directory);
    }

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
        harness(model, TestSupport.disabledMemory(directory)).send("a", TestSupport.message("first"));
        // A fresh Harness instance uses only durable state, not an old in-memory execution.
        var result = harness(model, TestSupport.disabledMemory(directory)).send("a", TestSupport.message("second"));
        assertThat(result.answer()).isEqualTo("answer-2");
        assertThat(store.load("a")).hasSize(2).allMatch(turn -> turn.status() == ConversationTurn.Status.COMPLETED);
        assertThat(store.load("other")).isEmpty();
    }

    @Test
    void memoryObserveFailureDoesNotUndoCompletedAnswerOrCallTheModelAgain() {
        var modelCalls = new AtomicInteger();
        var brokenMemory = mock(NoteMemory.class);
        when(brokenMemory.recall("a")).thenReturn(List.of("prefers Chinese"));
        doThrow(new IllegalStateException("memory unavailable")).when(brokenMemory).observe(eq("a"), any());
        var reply = harness(prompt -> {
            modelCalls.incrementAndGet();
            assertThat(prompt.getContents()).contains("prefers Chinese");
            return TestSupport.text("已回答");
        }, brokenMemory).send("a", TestSupport.message("hello"));
        assertThat(reply.warnings()).singleElement().asString().contains("更新失败");
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.COMPLETED);
        assertThat(modelCalls.get()).isEqualTo(1);
    }

    @Test
    void memoryRecallFailureDegradesToConversationHistory() {
        var brokenMemory = mock(NoteMemory.class);
        when(brokenMemory.recall("a")).thenThrow(new IllegalStateException("unavailable"));
        var reply = harness(prompt -> TestSupport.text("ok"), brokenMemory).send("a", TestSupport.message("hello"));
        assertThat(reply.answer()).isEqualTo("ok");
        assertThat(reply.warnings()).singleElement().asString().contains("读取失败");
    }

    @Test
    void restartMarksOpenTurnsFailedWithoutReplayingTheirTools() {
        var open = ConversationTurn.start("unfinished");
        var intent = TranscriptMessage.from(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("old", "function", "external_write", "{}"))).build());
        store.save("a", open.checkpoint(List.of(open.messages().getFirst(), intent)));
        var calls = new AtomicInteger();
        harness(prompt -> {
            calls.incrementAndGet();
            assertThat(prompt.getContents()).doesNotContain("unfinished");
            return TestSupport.text("new answer");
        }, TestSupport.disabledMemory(directory)).send("a", TestSupport.message("new request"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(store.load("a").getFirst().messages().getLast().toolCalls().getFirst().name())
                .isEqualTo("external_write");
    }

    @Test
    void modelFailureIsRecordedAndDoesNotLeaveALiveTurn() {
        var harness = harness(prompt -> { throw new IllegalStateException("provider offline"); }, TestSupport.disabledMemory(directory));
        assertThatThrownBy(() -> harness.send("a", TestSupport.message("hello"))).hasMessageContaining("provider offline");
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(store.load("a").getFirst().messages().getFirst().text()).isEqualTo("hello");
    }

    @Test
    void rebuildsContextForEachStepWithoutSavingTheTransientView() {
        var calls = new AtomicInteger();
        var counter = new AgentRuntimeTest.CountingTool();
        var tools = TestSupport.toolRegistry(org.springframework.ai.support.ToolCallbacks.from(counter));
        ChatModel model = prompt -> {
            int call = calls.incrementAndGet();
            assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo("system");
            if (call == 1) return TestSupport.tool("c1", "count");
            assertThat(prompt.getInstructions().getLast()).isInstanceOf(org.springframework.ai.chat.messages.ToolResponseMessage.class);
            return TestSupport.text("done");
        };
        var context = spy(new WindowContextBuilder("system", 8, 6000, 1000));
        var harness = new AssistantHarness(store, TestSupport.disabledMemory(directory), context, tools, TestSupport.toolRounds(tools));
        var reply = TestSupport.conversations(directory, new AgentRuntime(model, harness, 3, 10000, 4)).send("a", TestSupport.message("hello"));
        assertThat(reply.modelCalls()).isEqualTo(2);
        verify(context, times(2)).prepare(anyList(), anyList(), anyList());
        assertThat(store.load("a").getFirst().messages()).extracting(TranscriptMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void resultSaveFailureKeepsOnlyCommittedIntentAndStopsBeforeAnotherModelCall() {
        var calls = new AtomicInteger();
        var counter = new AgentRuntimeTest.CountingTool();
        var tools = TestSupport.toolRegistry(org.springframework.ai.support.ToolCallbacks.from(counter));
        var brokenStore = spy(store);
        doAnswer(invocation -> {
            ConversationTurn turn = invocation.getArgument(1);
            if (turn.status() == ConversationTurn.Status.RUNNING && turn.messages().getLast().role().equals("tool")) {
                throw new IllegalStateException("result save failed");
            }
            return invocation.callRealMethod();
        }).when(brokenStore).save(anyString(), any(ConversationTurn.class));
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return TestSupport.tool("c1", "count");
        };
        var harness = new AssistantHarness(brokenStore, TestSupport.disabledMemory(directory),
                new WindowContextBuilder("system", 8, 6000, 1000), tools, TestSupport.toolRounds(tools));
        var runtime = TestSupport.conversations(directory, new AgentRuntime(model, harness, 3, 10000, 4));
        assertThatThrownBy(() -> runtime.send("a", TestSupport.message("hello"))).hasMessageContaining("result save failed");
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
        }, TestSupport.disabledMemory(directory));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> harness.send("a", TestSupport.message("first")));
                assertThat(firstInside.await(2, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return harness.send("a", TestSupport.message("second"));
                });
                assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondInside.await(150, TimeUnit.MILLISECONDS)).isFalse();
                // Distinct sessions have distinct execution locks.
                assertThat(executor.submit(() -> harness.send("b", TestSupport.message("other"))).get(2, TimeUnit.SECONDS).answer()).isEqualTo("answer");
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
                second.get(2, TimeUnit.SECONDS);
                assertThat(store.load("a")).hasSize(2);
            } finally {
                release.countDown();
            }
        }
    }

    private ConversationService harness(ChatModel model, NoteMemory memory) {
        var tools = TestSupport.toolRegistry();
        var harness = new AssistantHarness(store, memory,
                new WindowContextBuilder("system", 8, 6000, 1000), tools, TestSupport.toolRounds(tools));
        return TestSupport.conversations(directory, new AgentRuntime(model, harness, 4, 10000, 4));
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
