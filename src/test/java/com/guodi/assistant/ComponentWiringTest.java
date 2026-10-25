package com.guodi.assistant;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import com.guodi.assistant.component.memory.NoMemory;
import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.port.*;

import static org.assertj.core.api.Assertions.*;

class ComponentWiringTest {
    @TempDir java.nio.file.Path directory;

    @Test
    void defaultComponentsRunADemoToolRoundAndCanDisableMemoryAndTools() {
        runner().withPropertyValues("assistant.components.memory.id=none", "assistant.tools[0].id=current_time", "assistant.tools[0].version=1.0.0").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MemoryModule.class);
            assertThat(context.getBean(MemoryModule.class)).isInstanceOf(NoMemory.class);
            var tools = context.getBean(ToolExecutor.class);
            assertThat(tools.callbacks()).extracting(tool -> tool.getToolDefinition().name()).containsExactly("current_time");
            var answer = context.getBean(AgentRuntime.class).chat("demo", "现在几点");
            assertThat(answer.modelCalls()).isEqualTo(2);
            assertThat(answer.answer()).contains("current_time");
        });
    }

    @Test
    void userBeansReplaceEveryDefaultPortWithoutChangingCoreClasses() {
        runner().withUserConfiguration(CustomComponents.class).withPropertyValues(
                "assistant.components.context.id=custom-context", "assistant.components.store.id=custom-store",
                "assistant.components.memory.id=none", "assistant.components.tool-executor.id=custom-tools",
                "assistant.components.tool-executor.version=1.0.0").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class).hasSingleBean(MemoryModule.class)
                    .hasSingleBean(ConversationStore.class).hasSingleBean(ContextBuilder.class).hasSingleBean(ToolExecutor.class);
            var reply = context.getBean(AgentRuntime.class).chat("custom", "hello");
            assertThat(reply.answer()).isEqualTo("custom answer");
            assertThat(new com.guodi.assistant.web.ChatController(context.getBean(AgentRuntime.class), context.getBean(AssistantHarness.class),
                    context.getBean(com.guodi.assistant.versioning.ExecutionManifest.class)).components())
                    .containsEntry("mode", "live");
            assertThat(context.getBean(ConversationStore.class).load("custom")).hasSize(1);
            assertThat(context.getBean(MemoryModule.class)).isSameAs(context.getBean("customMemory"));
        });
    }

    @Test
    void addingAToolBeanRegistersItWithoutEditingRuntimeOrHarness() {
        runner().withUserConfiguration(ExtraTool.class).withPropertyValues(
                "assistant.tools[0].id=current_time", "assistant.tools[0].version=1.0.0",
                "assistant.tools[1].id=video_search_links", "assistant.tools[1].version=1.0.0",
                "assistant.tools[2].id=sample", "assistant.tools[2].version=1.0.0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolExecutor.class).callbacks()).extracting(tool -> tool.getToolDefinition().name())
                    .contains("sample", "current_time", "video_search_links");
        });
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        runner().withPropertyValues("assistant.limits.max-model-calls=0").run(context ->
                assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                .withPropertyValues("assistant.data-directory=" + directory, "assistant.model.provider=demo");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomComponents {
        @Bean @com.guodi.assistant.versioning.ComponentRevision(id = "custom-model", version = "1.0.0") ChatModel customModel() { return prompt -> TestSupport.text("custom answer"); }
        @Bean @com.guodi.assistant.versioning.ComponentRevision(id = "custom-store", version = "1.0.0") ConversationStore customStore() { return new TestSupport.Store(); }
        @Bean MemoryModule customMemory() { return new NoMemory(); }
        @Bean @com.guodi.assistant.versioning.ComponentRevision(id = "custom-context", version = "1.0.0") ContextBuilder customContext() {
            return (history, memories, message) -> List.of(new UserMessage(message));
        }
        @Bean @com.guodi.assistant.versioning.ComponentRevision(id = "custom-tools", version = "1.0.0")
        ToolExecutor customTools(org.springframework.beans.factory.ObjectProvider<ToolCallback> callbacks,
                com.guodi.assistant.config.AssistantProperties properties) {
            var selected = com.guodi.assistant.component.tool.VersionedToolCallback.select(
                    callbacks.orderedStream().toList(), properties.tools());
            var delegate = new com.guodi.assistant.component.tool.RegisteredToolExecutor(selected, 1000);
            return new ToolExecutor() {
                public List<ToolCallback> callbacks() { return delegate.callbacks(); }
                public List<ToolResponse> execute(List<ToolCall> calls, ToolContext context) { return delegate.execute(calls, context); }
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraTool {
        @Bean ToolCallback sampleTool() {
            return com.guodi.assistant.component.tool.VersionedToolCallback.from("1.0.0", new RegisteredToolExecutorTest.SampleTool())[0];
        }
    }
}
