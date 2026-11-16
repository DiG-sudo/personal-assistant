package com.guodi.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import com.guodi.assistant.component.storage.FileContextStore;
import com.guodi.assistant.component.storage.FileContextStore.Checkpoint;

import com.guodi.assistant.component.context.ContextView;
import com.guodi.assistant.component.context.ContextView.Entry;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;

import static org.assertj.core.api.Assertions.*;

class ContextAndStorageTest {
    @TempDir Path directory;
    private final JsonFiles files = new JsonFiles(new ObjectMapper().findAndRegisterModules());

    @Test
    void emptyLiveAndRestoredViewsUseTheSameIncrementalAssembly() {
        var builder = TestSupport.contextBuilder();
        var tools = List.of(ToolCallbacks.from(new ToolExecutorTest.SampleTool())[0]);
        var user = TranscriptMessage.from(new UserMessage("request"));
        var source = ConversationTurn.start("request");
        var first = builder.prepare("system", tools, ContextView.empty(), List.of(new Entry(source.id(), user)), directory, TestSupport::unexpectedModelCall);
        var intent = TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("c1", "function", "sample", "{}"))).build());
        var result = TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "sample", "found"))).build());
        var second = builder.prepare("system", tools, first.view(), List.of(new Entry(source.id(), intent), new Entry(source.id(), result)), directory, TestSupport::unexpectedModelCall);
        assertThat(first.view().messages()).extracting(Entry::message).containsExactly(user);
        assertThat(second.view().messages()).extracting(Entry::message).containsExactly(user, intent, result);
        assertThat(second.prompt().getInstructions()).extracting(message -> message.getMessageType().getValue())
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(((ToolCallingChatOptions) second.prompt().getOptions()).getToolCallbacks()).containsExactlyElementsOf(tools);
        assertThat(((ToolCallingChatOptions) second.prompt().getOptions()).getInternalToolExecutionEnabled()).isFalse();

        var store = new FileContextStore(directory, new ObjectMapper().findAndRegisterModules());
        store.save("chat", new Checkpoint(second.view(), source.id(), 3));
        var restored = new FileContextStore(directory, new ObjectMapper().findAndRegisterModules()).load("chat");
        var next = TranscriptMessage.from(new UserMessage("continue"));
        var third = builder.prepare("updated system", List.of(), restored.view(), List.of(new Entry(java.util.UUID.randomUUID(), next)), directory, TestSupport::unexpectedModelCall);
        assertThat(third.view().messages()).extracting(Entry::message).containsExactly(user, intent, result, next);
        assertThat(third.prompt().getInstructions().getFirst().getText()).isEqualTo("updated system");
        assertThat(third.prompt().getInstructions().stream().filter(message -> message.getMessageType().getValue().equals("system")))
                .hasSize(1);
        assertThat(((ToolCallingChatOptions) third.prompt().getOptions()).getToolCallbacks()).isEmpty();
        assertThat(restored.sourceTurnId()).isEqualTo(source.id());
        assertThat(restored.sourceMessageCount()).isEqualTo(3);
        assertThat(store.load("new").view()).isEqualTo(ContextView.empty());
    }

    @Test
    void fileStoreRestoresPlainTextAndToolResponsesAndReplacesCheckpoints() {
        var store = new FileConversationStore(directory.resolve("conversations"), files);
        var turn = ConversationTurn.start("hello");
        store.save("chat", turn);
        var messages = List.of(turn.messages().getFirst(),
                TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "time", "{}"))).build()),
                TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "time", "2026"))).build()),
                TranscriptMessage.from(new AssistantMessage("你好")));
        store.save("chat", turn.checkpoint(messages).complete());
        var restored = new FileConversationStore(directory.resolve("conversations"), files).load("chat");
        assertThat(restored).containsExactly(turn.checkpoint(messages).complete());
        assertThat(restored.getFirst().messages().get(2).toMessage()).isInstanceOf(ToolResponseMessage.class);
        assertThat(store.load("different")).isEmpty();
    }

    @Test
    void encodedConversationNamesCannotEscapeStorageRoot() {
        var store = new FileConversationStore(directory, files);
        var turn = TestSupport.completed("你好", "世界");
        store.save("../../outside/中文", turn);
        assertThat(store.load("../../outside/中文")).containsExactly(turn);
        assertThat(JsonFiles.conversationDirectory(directory, "../../outside/中文").getParent()).isEqualTo(directory);
    }

    @Test
    void readsOldTranscriptsAndWritesNewCheckpointsWithoutAManifest() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var store = new FileConversationStore(directory, files);
        var turn = ConversationTurn.start("interrupted");
        var conversation = JsonFiles.conversationDirectory(directory, "legacy");
        Files.createDirectories(conversation);
        var path = conversation.resolve(turn.id() + ".json");
        var legacy = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(turn);

        legacy.put("schemaVersion", 1);
        json.writeValue(path.toFile(), legacy);
        assertThat(store.load("legacy").getFirst().messages()).isEqualTo(turn.messages());

        legacy.put("schemaVersion", 2);
        legacy.putObject("manifest").put("releaseId", "old-release");
        json.writeValue(path.toFile(), legacy);
        var restored = store.load("legacy").getFirst();
        assertThat(restored.schemaVersion()).isEqualTo(2);
        assertThat(restored.messages()).isEqualTo(turn.messages());
        assertThat(json.readTree(path.toFile()).has("manifest")).isTrue();

        store.save("legacy", restored.fail("interrupted"));
        assertThat(store.load("legacy").getFirst().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(json.readTree(path.toFile()).path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(json.readTree(path.toFile()).has("manifest")).isFalse();
    }

    @Test
    void unsupportedPersistedSchemaIsRejected() {
        var store = new FileConversationStore(directory, files);
        var turn = TestSupport.completed("q", "a");
        store.save("a", new ConversationTurn(999, turn.id(), turn.startedAt(), turn.status(), turn.messages(), null));
        assertThatThrownBy(() -> store.load("a")).hasMessageContaining("schema");
    }
}
