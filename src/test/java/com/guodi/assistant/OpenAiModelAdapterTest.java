package com.guodi.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.domain.ConversationTurn;

import static org.assertj.core.api.Assertions.*;

/** Local protocol fixtures exercise the provider adapter without remote requests. */
class OpenAiModelAdapterTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {"max-tokens", "max-completion-tokens"})
    void realAdapterLeavesToolExecutionToRuntimeAndUsesConfiguredEndpoint(String outputParameter) throws Exception {
        var requests = new ArrayList<JsonNode>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String message = requests.size() == 1
                    ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"fixture-call\",\"type\":\"function\","
                      + "\"function\":{\"name\":\"sample\",\"arguments\":\"{}\"}}]}"
                    : "{\"role\":\"assistant\",\"content\":\"fixture final answer\"}";
            String body = "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-model\","
                    + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\""
                    + (requests.size() == 1 ? "tool_calls" : "stop") + "\"}]}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            runner(server).withPropertyValues("assistant.model.reasoning-effort=none",
                    "assistant.model.output-token-parameter=" + outputParameter).run(context -> {
                assertThat(context).hasNotFailed();
                var harness = context.getBean(AssistantHarness.class);
                var reply = context.getBean(ConversationService.class).send("provider", TestSupport.message("测试工具"));
                assertThat(reply.modelCalls()).isEqualTo(2);
                assertThat(reply.answer()).isEqualTo("fixture final answer");
                assertThat(harness.history("provider").getFirst().messages()).hasSize(4);
                assertThat(requests).hasSize(2);
                assertThat(requests.getFirst().get("model").asText()).isEqualTo("fixture-model");
                String field = outputParameter.replace('-', '_');
                String otherField = field.equals("max_tokens") ? "max_completion_tokens" : "max_tokens";
                assertThat(requests.getFirst().path(field).asInt()).isEqualTo(32768);
                assertThat(requests.getFirst().has(otherField)).isFalse();
                assertThat(requests.getFirst().get("tools")).hasSize(8);
                assertThat(requests.getLast().get("messages").toString()).contains("fixture-call", "tool");
                assertThat(requests).allSatisfy(request ->
                        assertThat(request.path("reasoning_effort").asText()).isEqualTo("none"));
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summaryUsesTheConfiguredRealAdapterAndStoresAJsonObject() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            var message = json.createObjectNode().put("role", "assistant")
                    .put("content", TestSupport.summaryJson("current JSON goal"));
            var choice = json.createObjectNode().put("index", 0).put("finish_reason", "stop");
            choice.set("message", message);
            var body = json.createObjectNode().put("id", "summary-fixture").put("object", "chat.completion")
                    .put("created", 1).put("model", "fixture-model");
            body.putArray("choices").add(choice);
            byte[] response = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            runner(server).run(context -> {
                assertThat(context).hasNotFailed();
                var previous = TestSupport.completed("older question", "older answer");
                var current = ConversationTurn.start("current question");
                var entries = new ArrayList<com.guodi.assistant.component.context.ContextView.Entry>();
                previous.messages().forEach(message -> entries.add(
                        new com.guodi.assistant.component.context.ContextView.Entry(previous.id(), message)));
                entries.add(new com.guodi.assistant.component.context.ContextView.Entry(current.id(), current.messages().getFirst()));
                var view = new com.guodi.assistant.component.context.ContextView(TestSupport.summary("old goal"), entries);
                var compacted = context.getBean(com.guodi.assistant.component.context.WindowContextBuilder.class)
                        .compact(view, 1, context.getBean(org.springframework.ai.chat.model.ChatModel.class)::call);
                assertThat(compacted.summary().goal()).isEqualTo("current JSON goal");
                assertThat(compacted.messages()).hasSize(1);
                assertThat(requests).hasSize(1);
                var request = requests.getFirst();
                assertThat(request.path("model").asText()).isEqualTo("fixture-model");
                assertThat(request.path("tools").size()).isZero();
                assertThat(request.path("max_tokens").asInt()).isEqualTo(8192);
                assertThat(request.get("messages").get(0).path("content").asText()).contains("JSON object", "criticalContext");
                try {
                    var payload = json.readTree(request.get("messages").get(1).path("content").asText());
                    assertThat(payload.path("previousSummary").path("goal").asText()).isEqualTo("old goal");
                    assertThat(payload.get("olderMessages")).hasSize(2);
                    assertThat(payload.toString()).doesNotContain("current question");
                    var store = context.getBean(com.guodi.assistant.component.storage.FileContextStore.class);
                    store.save("summary", new com.guodi.assistant.component.storage.FileContextStore.Checkpoint(compacted, current.id(), 1));
                    var saved = com.guodi.assistant.component.storage.JsonFiles.conversationDirectory(
                            directory.resolve("data/contexts"), "summary").resolve("view.json");
                    assertThat(json.readTree(Files.readAllBytes(saved)).path("view").path("summary").isObject()).isTrue();
                    assertThat(store.load("summary").view()).isEqualTo(compacted);
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureIsNotAutomaticallyRetriedAndTheTurnIsSavedAsFailed() throws Exception {
        var attempts = new AtomicInteger();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"error\":{\"message\":\"fixture unavailable\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            runner(server).run(context -> {
                assertThat(context).hasNotFailed();
                var harness = context.getBean(AssistantHarness.class);
                assertThatThrownBy(() -> context.getBean(ConversationService.class).send("failed-provider", TestSupport.message("hello"))).isInstanceOf(RuntimeException.class);
                assertThat(attempts.get()).isEqualTo(1);
                assertThat(harness.history("failed-provider").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
            });
        } finally {
            server.stop(0);
        }
    }

    private ApplicationContextRunner runner(HttpServer server) throws Exception {
        var workspace = Files.createDirectories(directory.resolve("workspace"));
        return new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class, () -> json)
                .withBean(ToolBinding.class,
                        () -> new ToolBinding(ToolBinding.Kind.READ, ToolCallbacks.from(new ToolExecutorTest.SampleTool())[0]))
                .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                .withPropertyValues("assistant.data-directory=" + directory.resolve("data"),
                        "assistant.shell-image=local-test-image",
                        "assistant.model.provider=openai",
                        "assistant.model.api-key=local-fixture-only", "assistant.model.name=fixture-model",
                        "assistant.model.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                        "assistant.model.completions-path=/custom/chat");
    }
}
