package com.guodi.assistant.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.memory.NoMemory;
import com.guodi.assistant.component.memory.NoteMemory;
import com.guodi.assistant.component.model.DemoChatModel;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.component.tool.*;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationGate;
import com.guodi.assistant.port.*;
import com.guodi.assistant.versioning.ComponentRevision;
import com.guodi.assistant.versioning.ExecutionManifest;
import com.guodi.assistant.versioning.ExecutionManifest.PromptSnapshot;

/** Composition root. Revision checks run before the application accepts a chat. */
@AutoConfiguration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    @ComponentRevision(id = "openai-compatible", version = "1.0.0")
    ChatModel chatModel(AssistantProperties properties) {
        var model = properties.model();
        if (model.provider() == AssistantProperties.Provider.DEMO) {
            return new DemoChatModel();
        }
        if (!StringUtils.hasText(model.apiKey()) || !StringUtils.hasText(model.name())) {
            throw new IllegalStateException("真实模型需要设置 AI_API_KEY 和 AI_MODEL。");
        }
        var timeout = Duration.ofSeconds(model.timeoutSeconds());
        var client = HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(timeout);
        var api = OpenAiApi.builder().apiKey(model.apiKey()).baseUrl(model.baseUrl())
                .completionsPath(model.completionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model.name()).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationStore conversationStore(AssistantProperties properties, ObjectMapper json) {
        return new FileConversationStore(properties.dataDirectory().resolve("conversations"), new JsonFiles(json));
    }

    @Bean
    @ConditionalOnMissingBean
    MemoryModule memoryModule(AssistantProperties properties, ObjectMapper json) {
        return switch (properties.components().memory().id()) {
            case "none" -> new NoMemory();
            case "notes" -> new NoteMemory(properties.dataDirectory().resolve("memory"), new JsonFiles(json));
            default -> throw new IllegalStateException("Register the requested MemoryModule as a versioned Bean");
        };
    }

    @Bean
    PromptSnapshot promptSnapshot(AssistantProperties properties) throws IOException {
        return ReleaseManifestFactory.loadPrompt(properties.prompt());
    }

    @Bean
    @ConditionalOnMissingBean
    ContextBuilder contextBuilder(AssistantProperties properties, PromptSnapshot prompt) {
        var context = properties.context();
        return new WindowContextBuilder(prompt.text(), context.recentTurns(), context.historyChars(), context.memoryChars());
    }

    @Bean
    @ConditionalOnMissingBean
    ToolExecutor toolExecutor(ObjectProvider<ToolCallback> callbacks, AssistantProperties properties) {
        var selected = VersionedToolCallback.select(callbacks.orderedStream().toList(), properties.tools());
        int resultChars = properties.limits().toolResultChars();
        return switch (properties.components().toolExecutor().version()) {
            case "1.0.0" -> new RegisteredToolExecutor(selected, resultChars);
            case "1.1.0" -> new CancellableToolExecutor(selected, resultChars);
            default -> throw new IllegalStateException("Register the requested ToolExecutor as a versioned Bean");
        };
    }

    @Bean
    ToolCallback currentTimeTool() {
        return VersionedToolCallback.from("1.0.0", new CurrentTimeTool())[0];
    }

    @Bean
    ToolCallback videoSearchLinksTool() {
        return VersionedToolCallback.from("1.0.0", new VideoSearchLinksTool())[0];
    }

    @Bean
    ExecutionManifest executionManifest(AssistantProperties properties, PromptSnapshot prompt,
            ConfigurableListableBeanFactory beans, ObjectMapper json) {
        return ReleaseManifestFactory.create(properties, prompt, beans, json);
    }

    @Bean
    ConversationGate conversationGate() {
        return new ConversationGate();
    }

    @Bean
    AgentRuntime agentRuntime(ChatModel model, ToolExecutor tools, AssistantProperties properties) {
        var limits = properties.limits();
        return new AgentRuntime(model, tools, limits.maxModelCalls(), limits.maxContextChars(), limits.maxToolsPerRound());
    }

    @Bean
    AssistantHarness assistantHarness(AgentRuntime runtime, ConversationStore conversations,
            MemoryModule memory, ContextBuilder context, ConversationGate gate, ExecutionManifest manifest) {
        return new AssistantHarness(runtime, conversations, memory, context, gate, manifest);
    }
}
