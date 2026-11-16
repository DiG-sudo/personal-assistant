package com.guodi.assistant;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.ToolResultStore;
import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolBinding.Kind;
import com.guodi.assistant.component.tool.ToolExecutor;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.config.AssistantProperties;

import static org.assertj.core.api.Assertions.*;

class ComponentWiringTest {
    @TempDir java.nio.file.Path directory;

    @BeforeEach
    void createWorkspace() throws Exception {
        Files.createDirectory(directory.resolve("workspace"));
    }

    @Test
    void registeredToolsAreAvailable() {
        runner().withUserConfiguration(ExtraTool.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolRegistry.class).callbacks())
                    .extracting(tool -> tool.getToolDefinition().name())
                    .containsExactly("edit_file", "execute", "glob_files", "grep_files", "list_files",
                            "read_file", "sample", "write_file");
        });
    }

    @Test
    void userBeansReplaceDefaultComponents() {
        runner().withUserConfiguration(CustomComponents.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class)
                    .hasSingleBean(FileConversationStore.class).hasSingleBean(WindowContextBuilder.class).hasSingleBean(ToolRegistry.class);
            var reply = context.getBean(ConversationService.class).send("custom", TestSupport.message("hello"));
            assertThat(reply.answer()).isEqualTo("custom answer");
            assertThat(new com.guodi.assistant.web.ChatController(context.getBean(ConversationService.class),
                    context.getBean(AssistantHarness.class),
                    context.getBean(ChatModel.class), context.getBean(ToolRegistry.class)).components())
                    .containsEntry("mode", "live");
            assertThat(context.getBean(FileConversationStore.class).load("custom")).hasSize(1);
        });
    }

    @Test
    void addingAToolBeanRegistersItWithoutEditingRuntimeOrHarness() {
        runner().withUserConfiguration(ExtraTool.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolRegistry.class).callbacks()).extracting(tool -> tool.getToolDefinition().name())
                    .contains("sample").hasSize(8);
        });
    }

    @Test
    void codingToolsUseTheSameRegistryAndExecutor() throws Exception {
        var workspace = Files.createDirectory(directory.resolve("project"));
        runner().withPropertyValues(
                "assistant.data-directory=" + directory.resolve("data"),

                "assistant.shell-image=local-test-image")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ToolBinding.class).values())
                            .extracting(binding -> binding.callback().getToolDefinition().name())
                            .containsExactlyInAnyOrder("edit_file", "execute", "glob_files", "grep_files",
                                    "list_files", "read_file", "write_file");
                    assertThat(context.getBeansOfType(ToolBinding.class).values()).allSatisfy(binding -> {
                        String name = binding.callback().getToolDefinition().name();
                        assertThat(binding.kind()).isEqualTo(switch (name) {
                            case "edit_file", "write_file" -> Kind.WRITE;
                            case "execute" -> Kind.SHELL;
                            default -> Kind.READ;
                        });
                    });
                    assertThat(context.getBean(ToolRegistry.class).callbacks())
                            .extracting(tool -> tool.getToolDefinition().name())
                            .containsExactly("edit_file", "execute", "glob_files", "grep_files", "list_files",
                                    "read_file", "write_file");
                    var executor = context.getBean(ToolExecutor.class);
                    var toolContext = new ToolContext(Map.of("projectPath", workspace));
                    var write = new ToolCall("w", "function", "write_file",
                            """
                            {"path":"src/Main.java","content":"hello hello"}
                            """);
                    assertThat(executor.execute(write, toolContext).success()).isTrue();
                    assertThat(executor.execute(write, toolContext).success()).isFalse();
                    var edit = new ToolCall("e", "function", "edit_file",
                            """
                            {"path":"src/Main.java","old_string":"hello","new_string":"world","replace_all":true}
                            """);
                    assertThat(executor.execute(edit, toolContext).success()).isTrue();
                    assertThat(workspace.resolve("src/Main.java")).hasContent("world world");
                });
    }

    @Test
    void customRegistryControlsToolsWithoutInitializingBuiltIns() {
        runner().withUserConfiguration(CustomComponents.class)
                .withPropertyValues("assistant.shell-image=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ToolRegistry.class).callbacks()).isEmpty();
                });
    }

    @Test
    void duplicateToolBindingsFailAtStartup() {
        runner().withUserConfiguration(ExtraTool.class)
                .withBean("duplicateSample", ToolBinding.class,
                        () -> new ToolBinding(Kind.READ, ToolCallbacks.from(new ToolExecutorTest.SampleTool())[0]))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Duplicate tool name: sample");
                });
    }

    @Test
    void customAndBuiltInToolsShareTheSameDuplicateNameCheck() throws Exception {
        var workspace = Files.createDirectory(directory.resolve("project"));
        runner().withBean(ToolBinding.class, () -> new ToolBinding(Kind.READ, ToolCallbacks.from(new Object() {
            @Tool(name = "read_file", description = "custom read")
            public String read() {
                return "custom";
            }
        })[0])).withPropertyValues(
                "assistant.data-directory=" + directory.resolve("data")).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Duplicate tool name: read_file");
                });
    }

    @Test
    void agentExposesRegisteredToolsAndExecutesAReadTool() throws Exception {
        var workspace = Files.createDirectory(directory.resolve("project"));
        Files.writeString(workspace.resolve("README.md"), "hello from workspace");
        var modelCalls = new AtomicInteger();
        runner().withBean(ChatModel.class, () -> prompt -> {
            var options = (ToolCallingChatOptions) prompt.getOptions();
            assertThat(options.getToolCallbacks()).extracting(tool -> tool.getToolDefinition().name())
                    .contains("read_file").hasSize(7);
            if (modelCalls.incrementAndGet() == 1) {
                var call = new ToolCall("read", "function", "read_file", "{\"path\":\"README.md\"}");
                var assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
                return new ChatResponse(List.of(new Generation(assistant)));
            }
            var response = (ToolResponseMessage) prompt.getInstructions().getLast();
            assertThat(response.getResponses()).singleElement().satisfies(tool -> {
                assertThat(tool.id()).isEqualTo("read");
                assertThat(tool.responseData()).contains("1: hello from workspace");
            });
            return TestSupport.text("read complete");
        }).withPropertyValues(
                "assistant.data-directory=" + directory.resolve("data"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var reply = context.getBean(ConversationService.class)
                            .send("coding", TestSupport.message("read README.md", workspace));
                    assertThat(reply.answer()).isEqualTo("read complete");
                    assertThat(reply.modelCalls()).isEqualTo(2);
                });
    }

    @Test
    void normalSendAutomaticallyCompactsSavesAndReusesTheView() {
        var summaryCalls = new AtomicInteger();
        var answerCalls = new AtomicInteger();
        runner().withPropertyValues("assistant.budget.window-tokens=10000",
                        "assistant.budget.max-output-tokens=128", "assistant.budget.summary-output-tokens=256")
                .withBean(ChatModel.class, () -> prompt -> {
                    if (prompt.getInstructions().getFirst().getText().contains("handoff summary")) {
                        summaryCalls.incrementAndGet();
                        assertThat(prompt.getContents()).contains("older question")
                                .doesNotContain("older answer", "recent question", "continue");
                        return TestSupport.text(TestSupport.summaryJson("older progress"));
                    }
                    answerCalls.incrementAndGet();
                    assertThat(prompt.getContents()).contains("older progress", "recent question", "recent answer")
                            .doesNotContain("older question");
                    return TestSupport.text("done");
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var transcript = context.getBean(FileConversationStore.class);
                    var older = TestSupport.completed("older question " + "x ".repeat(8500), "older answer");
                    var recent = TestSupport.completed("recent question", "recent answer");
                    transcript.save("auto", older);
                    transcript.save("auto", recent);
                    var service = context.getBean(ConversationService.class);
                    assertThat(service.send("auto", TestSupport.message("continue")).answer()).isEqualTo("done");
                    var views = context.getBean(com.guodi.assistant.component.storage.FileContextStore.class);
                    var checkpoint = views.load("auto");
                    assertThat(checkpoint.view().summary().goal()).isEqualTo("older progress");
                    assertThat(checkpoint.view().messages()).extracting(entry -> entry.message().text())
                            .containsExactly("older answer", "recent question", "recent answer", "continue");
                    assertThat(service.send("auto", TestSupport.message("next")).answer()).isEqualTo("done");
                    assertThat(summaryCalls).hasValue(1);
                    assertThat(answerCalls).hasValue(2);
                    assertThat(transcript.load("auto").getFirst()).isEqualTo(older);
                    assertThat(views.load("auto").view().messages()).extracting(entry -> entry.message().text())
                            .contains("done", "next");
                });
    }

    @Test
    void normalSendCompactsTheCurrentTurnBeforeTheNextReasoningCall() {
        var summaries = new AtomicInteger();
        var reasoning = new AtomicInteger();
        runner().withUserConfiguration(ExtraTool.class)
                .withPropertyValues("assistant.budget.window-tokens=10000",
                        "assistant.budget.max-output-tokens=128", "assistant.budget.summary-output-tokens=256")
                .withBean(ChatModel.class, () -> prompt -> {
                    if (prompt.getInstructions().getFirst().getText().contains("handoff summary")) {
                        summaries.incrementAndGet();
                        assertThat(prompt.getContents()).contains("current request", "earlier reasoning")
                                .doesNotContain("latest-call");
                        return TestSupport.text(TestSupport.summaryJson("continue current request"));
                    }
                    if (reasoning.incrementAndGet() == 1) {
                        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                                .content("earlier reasoning " + "x ".repeat(4000))
                                .toolCalls(List.of(new ToolCall("first-call", "function", "sample", "{}"))).build())));
                    }
                    if (reasoning.get() == 2) {
                        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                                .content("latest verification " + "y ".repeat(4000))
                                .toolCalls(List.of(new ToolCall("latest-call", "function", "sample", "{}"))).build())));
                    }
                    assertThat(prompt.getContents()).contains("continue current request").doesNotContain("earlier reasoning");
                    assertThat(((AssistantMessage) prompt.getInstructions().get(2)).getToolCalls())
                            .extracting(ToolCall::id).containsExactly("latest-call");
                    assertThat(prompt.getInstructions().getLast()).isInstanceOf(ToolResponseMessage.class);
                    return TestSupport.text("done");
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(ConversationService.class);
                    var reply = service.send("split", TestSupport.message("current request"));
                    assertThat(reply.answer()).isEqualTo("done");
                    assertThat(reply.modelCalls()).isEqualTo(4);
                    assertThat(summaries).hasValue(1);
                    assertThat(reasoning).hasValue(3);
                    var views = context.getBean(com.guodi.assistant.component.storage.FileContextStore.class);
                    var checkpoint = views.load("split");
                    assertThat(checkpoint.view().summary().goal()).isEqualTo("continue current request");
                    assertThat(checkpoint.view().messages()).hasSize(2);
                    assertThat(checkpoint.sourceMessageCount()).isEqualTo(5);
                    var raw = context.getBean(FileConversationStore.class).load("split").getFirst();
                    assertThat(raw.messages()).hasSize(6);
                    assertThat(raw.messages().get(1).text()).contains("earlier reasoning");
                    assertThat(raw.status()).isEqualTo(com.guodi.assistant.domain.ConversationTurn.Status.COMPLETED);
                });
    }

    @Test
    void builtInToolsRequireAnExplicitShellImage() {
        runner().withPropertyValues(
                "assistant.data-directory=" + directory.resolve("data"),

                "assistant.shell-image=").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Set assistant.shell-image to a Docker image to enable execute");
                });
    }

    @Test
    void modelCapabilitiesClampTheConfiguredWorkingAndOutputBudgets() {
        runner().withPropertyValues("assistant.model.context-window-tokens=131072",
                "assistant.model.max-output-tokens=8192").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AssistantProperties.class);
            var effective = properties.budget().forModel(properties.model());
            assertThat(effective.windowTokens()).isEqualTo(131072);
            assertThat(effective.maxOutputTokens()).isEqualTo(8192);
            assertThat(effective.summaryOutputTokens()).isEqualTo(8192);
            assertThat(effective.inputTokens()).isEqualTo(122880);
            assertThat(effective.compactAtTokens()).isEqualTo(98304);
            assertThat(effective.keepRecentTokens()).isEqualTo(18432);
        });
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        for (String property : List.of("assistant.budget.max-model-calls=0",
                "assistant.budget.compact-ratio=0", "assistant.budget.keep-recent-ratio=0.9",
                "assistant.budget.tool-result-preview-tokens=8192",
                "assistant.model.context-window-tokens=16384")) {
            runner().withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                .withPropertyValues("assistant.data-directory=" + directory.resolve("data"),

                        "assistant.shell-image=local-test-image", "assistant.model.provider=demo");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomComponents {
        @Bean ChatModel customModel() { return prompt -> TestSupport.text("custom answer"); }
        @Bean FileConversationStore customStore(AssistantProperties properties) {
            return TestSupport.store(properties.dataDirectory().resolve("custom"));
        }
        @Bean WindowContextBuilder customContext(ObjectMapper json) {
            return new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(64000));
        }
        @Bean
        ToolRegistry customTools() {
            return new ToolRegistry(List.of());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraTool {
        @Bean ToolBinding sampleTool() {
            return new ToolBinding(ToolBinding.Kind.READ, ToolCallbacks.from(new ToolExecutorTest.SampleTool())[0]);
        }
    }
}
