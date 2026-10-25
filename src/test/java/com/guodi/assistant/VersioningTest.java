package com.guodi.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;

import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.component.tool.VersionedToolCallback;
import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.config.AssistantProperties;
import com.guodi.assistant.config.ReleaseManifestFactory;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.versioning.ExecutionManifest;
import com.guodi.assistant.versioning.ExecutionManifest.Ref;

import static org.assertj.core.api.Assertions.*;

class VersioningTest {
    @TempDir Path directory;

    @Test
    void toolsSelectAnExactRevisionAndRejectAnUnregisteredOne() {
        var old = VersionedToolCallback.from("1.0.0", new ConfiguredTool("old"))[0];
        var next = VersionedToolCallback.from("2.0.0", new ConfiguredTool("new"))[0];
        assertThat(old.snapshot().configuration()).containsEntry("prefix", "old");
        assertThat(next.snapshot().configuration()).containsEntry("prefix", "new");
        assertThat(VersionedToolCallback.select(List.of(old, next), List.of(new Ref("sample", "1.0.0"))))
                .containsExactly(old);
        assertThatThrownBy(() -> VersionedToolCallback.select(List.of(old), List.of(new Ref("sample", "2.0.0"))))
                .hasMessageContaining("not registered");
        assertThatThrownBy(() -> VersionedToolCallback.select(List.of(old, old), List.of(old.snapshot().revision())))
                .hasMessageContaining("Duplicate");
    }

    @Test
    void invalidPromptContentAndComponentVersionsAreRejected() {
        var prompt = new AssistantProperties.Prompt("test", "1.0.0",
                new ByteArrayResource("changed".getBytes(StandardCharsets.UTF_8)), "0".repeat(64));
        assertThatThrownBy(() -> ReleaseManifestFactory.loadPrompt(prompt)).hasMessageContaining("checksum mismatch");
        runner().withPropertyValues("assistant.components.context.version=9.0.0").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void switchingReleasePreservesTheManifestOfAnEarlierTurn() {
        var original = new ExecutionManifest[1];
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            original[0] = context.getBean(ExecutionManifest.class);
            assertThat(original[0].releaseId()).isEqualTo("assistant-harness-1.1.0");
            assertThat(original[0].components().get("toolExecutor").revision().version()).isEqualTo("1.1.0");
            assertThat(original[0].settings()).containsKeys("executionSha256", "contextContractSha256");
            assertThatThrownBy(() -> ((java.util.Map<?, ?>) original[0].settings().get("context")).clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            var properties = context.getBean(AssistantProperties.class);
            var again = ReleaseManifestFactory.create(properties, original[0].prompt(),
                    context.getSourceApplicationContext().getBeanFactory(), context.getBean(ObjectMapper.class));
            assertThat(again.fingerprint()).isEqualTo(original[0].fingerprint());
            var reply = context.getBean(AgentRuntime.class).chat("release-test", "hello");
            assertThat(reply.configurationId()).isEqualTo(original[0].fingerprint());
            var components = new com.guodi.assistant.web.ChatController(
                    context.getBean(AgentRuntime.class), context.getBean(AssistantHarness.class), original[0]).components();
            assertThat(components).containsEntry("mode", "demo")
                    .containsEntry("tools", List.of("current_time", "video_search_links"));
        });
        runner().withPropertyValues("spring.config.import=classpath:releases/compact-v1.yml").run(context -> {
            assertThat(context).hasNotFailed();
            var current = context.getBean(ExecutionManifest.class);
            assertThat(current.releaseId()).isEqualTo("assistant-compact-1.0.0");
            assertThat(current.components().get("toolExecutor").revision().version()).isEqualTo("1.0.0");
            assertThat(current.fingerprint()).isNotEqualTo(original[0].fingerprint());
            var harness = context.getBean(AssistantHarness.class);
            context.getBean(AgentRuntime.class).chat("release-test", "hello again");
            var turns = harness.history("release-test");
            assertThat(turns.getFirst().manifest()).isEqualTo(original[0]);
            assertThat(turns.getLast().manifest()).isEqualTo(current);
            assertThat(turns.getFirst().fail("test").manifest()).isEqualTo(original[0]);
        });
    }

    @Test
    void legacyRecordsRemainUnversionedAndNewRecordsRequireAManifest() {
        var store = new FileConversationStore(directory, new JsonFiles(new ObjectMapper().findAndRegisterModules()));
        var turn = TestSupport.completed("old", "answer");
        store.save("legacy", new ConversationTurn(1, turn.id(), turn.startedAt(), turn.status(), turn.messages(), null, null));
        assertThat(store.load("legacy").getFirst().manifest()).isNull();
        store.save("invalid", new ConversationTurn(2, turn.id(), turn.startedAt(), turn.status(), turn.messages(), null, null));
        assertThatThrownBy(() -> store.load("invalid")).hasMessageContaining("schema");
    }

    static final class ConfiguredTool implements com.guodi.assistant.versioning.ConfigurationSnapshot {
        private final String prefix;
        ConfiguredTool(String prefix) { this.prefix = prefix; }
        public java.util.Map<String, Object> versionedSettings() { return java.util.Map.of("prefix", prefix); }
        @org.springframework.ai.tool.annotation.Tool(name = "sample", description = "sample")
        public String sample() { return prefix; }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                .withPropertyValues("assistant.data-directory=" + directory, "assistant.model.provider=demo");
    }
}
