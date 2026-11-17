package com.guodi.assistant;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.guodi.assistant.component.storage.FileSessionStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.config.AssistantProperties.Control;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.web.ChatController;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Timeout(15)
class RequestCancellationTest {
    @TempDir Path directory;

    @Test
    void cancellationFinishesAndPersistsTheToolRoundThenAllowsTheNextInput() throws Exception {
        var fixture = new Fixture(30);
        fixture.open();
        fixture.cancel(false);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> fixture.service.send("chat", TestSupport.message("run", directory)));
            assertThat(fixture.step.entered.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.cancel(true);
            fixture.heartbeat(204);
            assertThat(task.isDone()).isFalse();
            fixture.step.release.countDown();
            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
            fixture.assertClosedFailedTurn();
            fixture.cancel(false);
            assertThat(fixture.service.send("chat", TestSupport.message("continue", directory)).answer())
                    .isEqualTo("done");
            assertThat(fixture.store.load("chat").getLast().status()).isEqualTo(ConversationTurn.Status.COMPLETED);
        } finally {
            fixture.step.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void stoppedHeartbeatsInterruptAtTheSameBoundaryAndReopeningRestoresTheSession() throws Exception {
        var fixture = new Fixture(2);
        fixture.open();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> fixture.service.send("chat", TestSupport.message("run", directory)));
            assertThat(fixture.step.entered.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.heartbeat(204);
            // Simulate the CLI process disappearing while a tool is still running.
            Thread.sleep(2200);
            fixture.heartbeat(409);
            assertThat(task.isDone()).isFalse();
            fixture.step.release.countDown();
            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
            fixture.assertClosedFailedTurn();
            fixture.heartbeat(409);
            fixture.open();
            fixture.heartbeat(204);
            assertThat(fixture.service.send("chat", TestSupport.message("continue", directory)).answer())
                    .isEqualTo("done");
        } finally {
            fixture.step.release.countDown();
            worker.shutdownNow();
        }
    }

    private class Fixture {
        final BlockingStep step = new BlockingStep();
        final AtomicInteger modelCalls = new AtomicInteger();
        final com.guodi.assistant.component.storage.FileConversationStore store = TestSupport.store(directory.resolve("turns"));
        final ConversationService service;
        final MockMvc http;

        Fixture(int timeoutSeconds) {
            var tools = TestSupport.toolRegistry(ToolCallbacks.from(step));
            ChatModel model = prompt -> modelCalls.incrementAndGet() == 1
                    ? TestSupport.tool("step-1", "step") : TestSupport.text("done");
            var harness = new AssistantHarness(store, TestSupport.contextStore(directory), "system",
                    TestSupport.contextBuilder(), tools, TestSupport.toolRounds(tools));
            service = new ConversationService(new AgentRuntime(model, harness, 10, 4),
                    new FileSessionStore(directory.resolve("sessions"), new JsonFiles(new ObjectMapper().findAndRegisterModules())),
                    store, new Control(1, 1, timeoutSeconds));
            http = MockMvcBuilders.standaloneSetup(new ChatController(service, harness, model, tools)).build();
        }

        void open() throws Exception {
            http.perform(post("/api/control/open").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"conversationId\":\"chat\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.heartbeatIntervalSeconds").value(1));
        }

        void cancel(boolean accepted) throws Exception {
            http.perform(post("/api/control/cancel").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"conversationId\":\"chat\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(accepted));
        }

        void heartbeat(int expected) throws Exception {
            http.perform(post("/api/control/heartbeat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"conversationId\":\"chat\"}"))
                    .andExpect(status().is(expected));
        }

        void assertClosedFailedTurn() {
            var turn = store.load("chat").getLast();
            assertThat(turn.status()).isEqualTo(ConversationTurn.Status.FAILED);
            assertThat(turn.messages()).extracting(TranscriptMessage::role).containsExactly("user", "assistant", "tool");
            assertThat(turn.messages().getLast().toolResponses().getFirst().id()).isEqualTo("step-1");
            assertThat(turn.messages().getLast().toolResponses().getFirst().responseData()).contains("finished");
            assertThat(modelCalls).hasValue(1);
        }
    }

    public static class BlockingStep {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Tool(name = "step", description = "Finish one controlled tool step.")
        public String step() throws InterruptedException {
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release the tool");
            return "finished";
        }
    }
}
