package com.guodi.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.domain.ConversationTurn;

import static org.assertj.core.api.Assertions.*;

/** Local protocol fixtures exercise the provider adapter without remote requests. */
class OpenAiModelAdapterTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void realAdapterLeavesToolExecutionToRuntimeAndUsesConfiguredEndpoint() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String message = requests.size() == 1
                    ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"fixture-call\",\"type\":\"function\","
                      + "\"function\":{\"name\":\"current_time\",\"arguments\":\"{}\"}}]}"
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
            runner(server).run(context -> {
                assertThat(context).hasNotFailed();
                var harness = context.getBean(AssistantHarness.class);
                var reply = harness.chat("provider", "现在几点");
                assertThat(reply.modelCalls()).isEqualTo(2);
                assertThat(reply.answer()).isEqualTo("fixture final answer");
                assertThat(harness.history("provider").getFirst().messages()).hasSize(4);
                assertThat(requests).hasSize(2);
                assertThat(requests.getFirst().get("model").asText()).isEqualTo("fixture-model");
                assertThat(requests.getFirst().get("tools")).hasSize(1);
                assertThat(requests.getLast().get("messages").toString()).contains("fixture-call", "tool");
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
                assertThatThrownBy(() -> harness.chat("failed-provider", "hello")).isInstanceOf(RuntimeException.class);
                assertThat(attempts.get()).isEqualTo(1);
                assertThat(harness.history("failed-provider").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
            });
        } finally {
            server.stop(0);
        }
    }

    private ApplicationContextRunner runner(HttpServer server) {
        return new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class, () -> json)
                .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                .withPropertyValues("assistant.data-directory=" + directory, "assistant.model.provider=openai",
                        "assistant.model.api-key=local-fixture-only", "assistant.model.name=fixture-model",
                        "assistant.model.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                        "assistant.model.completions-path=/custom/chat",
                        "assistant.tools[0].id=current_time", "assistant.tools[0].version=1.0.0", "assistant.components.memory.id=none");
    }
}
