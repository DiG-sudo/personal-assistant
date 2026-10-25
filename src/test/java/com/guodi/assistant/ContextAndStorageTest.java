package com.guodi.assistant;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.memory.NoMemory;
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
        var withTools = ConversationTurn.start("recent", TestSupport.manifest()).complete(List.of(
                TranscriptMessage.from(new UserMessage("recent")),
                TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "lookup", "{}"))).build()),
                TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "lookup", "found"))).build()),
                TranscriptMessage.from(new AssistantMessage("recent answer"))));
        var failed = ConversationTurn.start("failed", TestSupport.manifest()).fail("no");
        var history = List.of(old, withTools, failed);
        var context = new WindowContextBuilder("system", 1, 2000, 20)
                .build(history, List.of("x".repeat(40), "note"), "new");
        assertThat(context.stream().map(message -> message.getText())).doesNotContain("old", "failed");
        assertThat(context.stream().filter(message -> message instanceof ToolResponseMessage).count()).isEqualTo(1);
        assertThat(context.getLast().getText()).isEqualTo("new");
        assertThat(history.get(1).messages()).hasSize(4);
        var small = new WindowContextBuilder("system", 8, 1, 0).build(history, List.of(), "new");
        assertThat(small).hasSize(2);
    }

    @Test
    void fileStoreRestoresPlainTextAndToolResponsesAndReplacesCheckpoints() {
        var store = new FileConversationStore(directory.resolve("conversations"), files);
        var turn = ConversationTurn.start("hello", TestSupport.manifest());
        store.save("chat", turn);
        var messages = List.of(turn.messages().getFirst(),
                TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "time", "{}"))).build()),
                TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "time", "2026"))).build()),
                TranscriptMessage.from(new AssistantMessage("你好")));
        store.save("chat", turn.complete(messages));
        var restored = new FileConversationStore(directory.resolve("conversations"), files).load("chat");
        assertThat(restored).containsExactly(turn.complete(messages));
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
    void notesAreExplicitIdempotentIsolatedAndSurviveChangingMemoryImplementation() {
        var notes = new NoteMemory(directory.resolve("memory"), files);
        var turn = TestSupport.completed("记住：我喜欢中文回答", "收到");
        notes.observe("a", turn);
        notes.observe("a", turn);
        notes.observe("a", TestSupport.completed("普通聊天", "ok"));
        assertThat(notes.recall("a", "偏好")).containsExactly("我喜欢中文回答");
        assertThat(notes.recall("b", "偏好")).isEmpty();
        assertThat(new NoMemory().recall("a", "偏好")).isEmpty();
        assertThat(new NoteMemory(directory.resolve("memory"), files).recall("a", "偏好"))
                .containsExactly("我喜欢中文回答");
    }

    @Test
    void unsupportedPersistedSchemaIsRejected() {
        var store = new FileConversationStore(directory, files);
        var turn = TestSupport.completed("q", "a");
        store.save("a", new ConversationTurn(999, turn.id(), turn.startedAt(), turn.status(), turn.messages(), null, turn.manifest()));
        assertThatThrownBy(() -> store.load("a")).hasMessageContaining("schema");
    }
}
