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

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.memory.NoteMemory;
import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;

import static org.assertj.core.api.Assertions.*;

class ContextAndStorageTest {
    @TempDir Path directory;
    private final JsonFiles files = new JsonFiles(new ObjectMapper().findAndRegisterModules());

    @Test
    void contextSelectsWholeCompletedTurnsWithoutMutatingHistory() {
        var old = TestSupport.completed("old", "old answer");
        var withTools = ConversationTurn.start("recent").checkpoint(List.of(
                TranscriptMessage.from(new UserMessage("recent")),
                TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "lookup", "{}"))).build()),
                TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "lookup", "found"))).build()),
                TranscriptMessage.from(new AssistantMessage("recent answer")))).complete();
        var failed = ConversationTurn.start("failed").fail("no");
        var history = List.of(old, withTools, failed);
        var prepared = new WindowContextBuilder("system", 1, 2000, 20)
                .prepare(history, List.of("x".repeat(40), "note"),
                        List.of(TranscriptMessage.from(new UserMessage("new"))));
        assertThat(prepared.selection().turnIds()).containsExactly(withTools.id());
        assertThat(prepared.selection().memoryIndexes()).containsExactly(1);
        var context = prepared.messages();
        assertThat(context.stream().map(message -> message.getText())).doesNotContain("old", "failed");
        assertThat(context.stream().filter(message -> message instanceof ToolResponseMessage).count()).isEqualTo(1);
        assertThat(context.getLast().getText()).isEqualTo("new");
        assertThat(history.get(1).messages()).hasSize(4);
        var small = new WindowContextBuilder("system", 8, 1, 0).prepare(history, List.of(), List.of(TranscriptMessage.from(new UserMessage("new")))).messages();
        assertThat(small).hasSize(2);
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
    void notesAreExplicitIdempotentIsolatedAndRespectDisabledMode() {
        var notes = new NoteMemory(directory.resolve("memory"), files, true);
        var turn = TestSupport.completed("记住：我喜欢中文回答", "收到");
        notes.observe("a", turn);
        notes.observe("a", turn);
        notes.observe("a", TestSupport.completed("普通聊天", "ok"));
        assertThat(notes.recall("a")).containsExactly("我喜欢中文回答");
        assertThat(notes.recall("b")).isEmpty();
        var disabled = new NoteMemory(directory.resolve("memory"), files, false);
        assertThat(disabled.recall("a")).isEmpty();
        disabled.observe("a", TestSupport.completed("记住：不会写入", "收到"));
        assertThat(new NoteMemory(directory.resolve("memory"), files, true).recall("a"))
                .containsExactly("我喜欢中文回答");
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
