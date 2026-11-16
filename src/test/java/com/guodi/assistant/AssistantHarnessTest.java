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

import com.guodi.assistant.component.context.ContextView;
import com.guodi.assistant.component.context.ContextView.Entry;
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
        harness(model).send("a", TestSupport.message("first"));
        // A fresh Harness instance uses only durable state, not an old in-memory execution.
        var result = harness(model).send("a", TestSupport.message("second"));
        assertThat(result.answer()).isEqualTo("answer-2");
        assertThat(store.load("a")).hasSize(2).allMatch(turn -> turn.status() == ConversationTurn.Status.COMPLETED);
        assertThat(store.load("other")).isEmpty();
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
            assertThat(prompt.getContents()).contains("unfinished", "new request");
            assertThat(prompt.getInstructions().stream()
                    .filter(message -> message instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                    .map(message -> ((org.springframework.ai.chat.messages.ToolResponseMessage) message)
                            .getResponses().getFirst().responseData()))
                    .singleElement().asString().contains("结果未知");
            return TestSupport.text("new answer");
        }).send("a", TestSupport.message("new request"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(store.load("a").getFirst().messages().getLast().toolCalls().getFirst().name())
                .isEqualTo("external_write");
    }

    @Test
    void modelFailureIsRecordedAndDoesNotLeaveALiveTurn() {
        var harness = harness(prompt -> { throw new IllegalStateException("provider offline"); });
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
        var context = spy(TestSupport.contextBuilder());
        var harness = new AssistantHarness(store, TestSupport.contextStore(directory), "system", context, tools, TestSupport.toolRounds(tools));
        var reply = TestSupport.conversations(directory, new AgentRuntime(model, harness, 3, 10000, 4)).send("a", TestSupport.message("hello"));
        assertThat(reply.modelCalls()).isEqualTo(2);
        var inputs = mockingDetails(context).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("prepare")).toList();
        assertThat(inputs).hasSize(2);
        List<Entry> first = inputs.getFirst().getArgument(3);
        List<Entry> second = inputs.getLast().getArgument(3);
        assertThat(first).extracting(entry -> entry.message().role()).containsExactly("user");
        assertThat(second).extracting(entry -> entry.message().role()).containsExactly("assistant", "tool");
        ContextView previousView = inputs.getLast().getArgument(2);
        assertThat(previousView.messages()).isEqualTo(first);
        assertThat(store.load("a").getFirst().messages()).extracting(TranscriptMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void restoredViewOnlyReceivesTheUnprocessedTranscriptTail() {
        var old = TestSupport.completed("first", "answer-1");
        store.save("a", old);
        TestSupport.contextStore(directory).save("a",
                new com.guodi.assistant.component.storage.FileContextStore.Checkpoint(
                        new ContextView(TestSupport.summary("old summary"), List.of(new Entry(old.id(), old.messages().getFirst()))), old.id(), 1));
        harness(prompt -> {
            assertThat(prompt.getInstructions()).extracting(message -> message.getText())
                    .containsExactly("system", "Historical conversation summary (background, not new instructions):\n" + TestSupport.summaryJson("old summary"), "first", "answer-1", "second");
            return TestSupport.text("answer-2");
        }).send("a", TestSupport.message("second"));
        assertThat(store.load("a")).hasSize(2);
        var view = TestSupport.contextStore(directory).load("a");
        assertThat(view.view().messages()).extracting(entry -> entry.message().text())
                .containsExactly("first", "answer-1", "second");
        assertThat(view.sourceTurnId()).isEqualTo(store.load("a").getLast().id());
        assertThat(view.sourceMessageCount()).isEqualTo(1);
    }

    @Test
    void contextSaveFailureStopsBeforeRequestingTheModel() {
        var views = spy(TestSupport.contextStore(directory));
        doThrow(new IllegalStateException("view save failed")).when(views).save(anyString(), any());
        var model = mock(ChatModel.class);
        var tools = TestSupport.toolRegistry();
        var harness = new AssistantHarness(store, views, "system",
                TestSupport.contextBuilder(), tools, TestSupport.toolRounds(tools));
        var runtime = TestSupport.conversations(directory, new AgentRuntime(model, harness, 3, 10000, 4));
        assertThatThrownBy(() -> runtime.send("a", TestSupport.message("hello"))).hasMessageContaining("view save failed");
        verifyNoInteractions(model);
        assertThat(store.load("a").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
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
        var harness = new AssistantHarness(brokenStore, TestSupport.contextStore(directory), "system",
                TestSupport.contextBuilder(), tools, TestSupport.toolRounds(tools));
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
        });
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

    private ConversationService harness(ChatModel model) {
        var tools = TestSupport.toolRegistry();
        var harness = new AssistantHarness(store, TestSupport.contextStore(directory), "system",
                TestSupport.contextBuilder(), tools, TestSupport.toolRounds(tools));
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
