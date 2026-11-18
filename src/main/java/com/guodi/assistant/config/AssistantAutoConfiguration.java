package com.guodi.assistant.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.model.DelayedDemoChatModel;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.FileContextStore;
import com.guodi.assistant.component.storage.ToolResultStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.component.storage.FileSessionStore;
import com.guodi.assistant.component.tool.SandboxCleanup;
import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolExecutor;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.component.tool.ToolRoundExecutor;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;

/** Composes the default assistant components. */
@AutoConfiguration
@EnableConfigurationProperties(AssistantProperties.class)
@Import(BuiltinToolConfiguration.class)
public class AssistantAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel chatModel(AssistantProperties properties) {
        var model = properties.model();
        if (model.provider() == AssistantProperties.Provider.DEMO) {
            return new DelayedDemoChatModel();
        }
        if (!StringUtils.hasText(model.apiKey()) || !StringUtils.hasText(model.name())) {
            throw new IllegalStateException("真实模型需要设置环境变量 AI_API_KEY 和 application.yml 中的 assistant.model.name。");
        }
        var timeout = Duration.ofSeconds(model.timeoutSeconds());
        var client = HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(timeout);
        var api = OpenAiApi.builder().apiKey(model.apiKey()).baseUrl(model.baseUrl())
                .completionsPath(model.completionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
        var budget = properties.budget().forModel(model);
        var options = OpenAiChatOptions.builder().model(model.name()).internalToolExecutionEnabled(false);
        if (StringUtils.hasText(model.reasoningEffort())) {
            options.reasoningEffort(model.reasoningEffort());
        }
        var adapter = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return request -> {
            var requestOptions = request.getOptions();
            int outputTokens = requestOptions == null || requestOptions.getMaxTokens() == null
                    ? budget.maxOutputTokens() : Math.min(requestOptions.getMaxTokens(), model.maxOutputTokens());
            var transport = OpenAiChatOptions.builder().internalToolExecutionEnabled(false);
            if (requestOptions instanceof ToolCallingChatOptions tools) {
                transport.toolCallbacks(tools.getToolCallbacks());
            }
            if (model.outputTokenParameter() == AssistantProperties.OutputTokenParameter.MAX_COMPLETION_TOKENS) {
                transport.maxCompletionTokens(outputTokens);
            } else {
                transport.maxTokens(outputTokens);
            }
            return adapter.call(new Prompt(request.getInstructions(), transport.build()));
        };
    }

    @Bean
    @ConditionalOnMissingBean
    FileConversationStore conversationStore(AssistantProperties properties, ObjectMapper json) {
        return new FileConversationStore(properties.dataDirectory().resolve("conversations"), new JsonFiles(json));
    }

    @Bean
    @ConditionalOnMissingBean
    FileSessionStore sessionStore(AssistantProperties properties, ObjectMapper json) {
        return new FileSessionStore(properties.dataDirectory().resolve("sessions"), new JsonFiles(json));
    }

    @Bean
    @ConditionalOnMissingBean
    WindowContextBuilder contextBuilder(ToolResultStore results, ObjectMapper json, AssistantProperties properties) {
        return new WindowContextBuilder(results, json, properties.budget().forModel(properties.model()));
    }

    @Bean
    @ConditionalOnMissingBean
    ToolResultStore toolResultStore() {
        return new ToolResultStore();
    }

    @Bean
    @ConditionalOnMissingBean
    FileContextStore contextStore(AssistantProperties properties, ObjectMapper json) {
        return new FileContextStore(properties.dataDirectory().resolve("contexts"), json);
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry(ObjectProvider<ToolBinding> bindings) {
        return new ToolRegistry(bindings.stream().toList());
    }

    @Bean(name = "assistantReadWorkers", destroyMethod = "shutdown")
    ExecutorService assistantReadWorkers() {
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("assistant-read-", 0).factory());
    }

    @Bean
    @ConditionalOnMissingBean
    ToolExecutor toolExecutor(ToolRegistry registry) {
        return new ToolExecutor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRoundExecutor toolRoundExecutor(ToolRegistry registry, ToolExecutor executor,
            @Qualifier("assistantReadWorkers") ExecutorService readWorkers) {
        return new ToolRoundExecutor(registry, executor, readWorkers);
    }

    @Bean
    AgentRuntime agentRuntime(ChatModel model, AssistantHarness harness, AssistantProperties properties) {
        var budget = properties.budget();
        return new AgentRuntime(model, harness, budget.maxModelCalls(), budget.maxToolsPerRound());
    }

    @Bean
    AssistantHarness assistantHarness(FileConversationStore conversations, FileContextStore contextStore,
            WindowContextBuilder context, ToolRegistry toolRegistry, ToolRoundExecutor toolRounds,
            AssistantProperties properties) throws IOException {
        String prompt = properties.systemPrompt().getContentAsString(StandardCharsets.UTF_8);
        return new AssistantHarness(conversations, contextStore, prompt, context, toolRegistry, toolRounds);
    }

    @Bean
    SandboxCleanup sandboxCleanup() {
        return new SandboxCleanup();
    }

    @Bean
    ConversationService conversationService(AgentRuntime runtime, FileSessionStore sessions,
            FileConversationStore transcripts, AssistantProperties properties, SandboxCleanup cleanup) {
        return new ConversationService(runtime, sessions, transcripts, properties.control(), cleanup);
    }

    @Bean(name = "assistantSessionMonitor", destroyMethod = "shutdownNow")
    ScheduledExecutorService assistantSessionMonitor(ConversationService conversations, AssistantProperties properties) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("assistant-session-monitor").factory());
        int interval = properties.control().heartbeatIntervalSeconds();
        scheduler.scheduleWithFixedDelay(conversations::reclaimExpiredSessions, interval, interval, TimeUnit.SECONDS);
        return scheduler;
    }
}
