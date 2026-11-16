package com.guodi.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import com.guodi.assistant.component.context.ContextView;
import com.guodi.assistant.component.context.ContextView.Entry;
import com.guodi.assistant.component.context.WindowContextBuilder;
import com.guodi.assistant.component.storage.FileContextStore;
import com.guodi.assistant.component.storage.FileContextStore.Checkpoint;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.component.storage.ToolResultStore;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextStrategiesTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void processesOnlyAppendedResultsAndReusesPublishedFilesAfterRestart() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        String content = "HEAD\n" + "中😀\n".repeat(1000) + "TAIL";
        UUID turn = UUID.randomUUID();
        var intent = intent(turn, "c1");
        var raw = result(turn, "c1", content);
        var store = spy(new ToolResultStore());
        var builder = TestSupport.contextBuilder(store, 256);
        var prepared = builder.prepare("system", List.of(), ContextView.empty(), List.of(intent, raw), project, TestSupport::unexpectedModelCall);
        String preview = prepared.view().messages().getLast().message().toolResponses().getFirst().responseData();
        assertThat(preview).startsWith("HEAD").endsWith("TAIL").contains(".agent/tool-results/", "read_file", "char_offset");
        assertThat(new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator().estimate(preview))
                .isLessThanOrEqualTo(256);
        assertThat(raw.message().toolResponses().getFirst().responseData()).isEqualTo(content);
        Path resultFile;
        try (var paths = Files.list(project.resolve(".agent/tool-results"))) {
            resultFile = paths.findFirst().orElseThrow();
        }
        assertThat(Files.readString(resultFile)).isEqualTo(content);
        var modified = Files.getLastModifiedTime(resultFile);
        var views = new FileContextStore(directory.resolve("views"), json);
        views.save("a", new Checkpoint(prepared.view(), turn, 2));
        var resumed = builder.prepare("system", List.of(), views.load("a").view(), List.of(), project, TestSupport::unexpectedModelCall);
        assertThat(resumed.view()).isEqualTo(prepared.view());
        verify(store, times(1)).save(any(), anyString(), anyString());
        // Raw-tail recovery after a failed view commit must reuse the same immutable file.
        TestSupport.contextBuilder(new ToolResultStore(), 256).prepare("system", List.of(), ContextView.empty(),
                List.of(intent, raw), project, TestSupport::unexpectedModelCall);
        assertThat(Files.getLastModifiedTime(resultFile)).isEqualTo(modified);
        try (var paths = Files.list(resultFile.getParent())) {
            assertThat(paths.toList()).containsExactly(resultFile);
        }
    }

    @Test
    void mergesOldSummaryAndOlderMessagesWhileKeepingABudgetedSuffix() {
        UUID oldest = UUID.randomUUID(), recent = UUID.randomUUID(), current = UUID.randomUUID();
        var older = List.of(user(oldest, "old task"), intent(oldest, "c1"), result(oldest, "c1", "old output"));
        var kept = List.of(user(recent, "recent question"), user(recent, "steer: wait"),
                user(current, "new task"), intent(current, "c2"), result(current, "c2", "new output"));
        var all = new java.util.ArrayList<>(older);
        all.addAll(kept);
        ContextView original = new ContextView(TestSupport.summary("previous summary"), all);
        var calls = new AtomicInteger();
        ChatModel summaryModel = request -> {
            calls.incrementAndGet();
            assertThat(request.getContents()).contains("previous summary", "old task", "old output")
                    .doesNotContain("recent question", "steer: wait", "new output");
            assertThat(request.getInstructions().getFirst().getText()).contains("goal", "keyDecisions", "criticalContext");
            assertThat(((ToolCallingChatOptions) request.getOptions()).getToolCallbacks()).isEmpty();
            return TestSupport.text(TestSupport.summaryJson("updated summary"));
        };
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(64000));
        var tokenizer = new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator();
        int retainedTokens = kept.stream().mapToInt(entry -> {
            try { return 8 + tokenizer.estimate(json.writeValueAsString(entry.message())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new AssertionError(error); }
        }).sum();
        ContextView compacted = builder.compact(original, retainedTokens, summaryModel::call);
        assertThat(compacted.summary()).isEqualTo(TestSupport.summary("updated summary"));
        assertThat(compacted.messages()).containsExactlyElementsOf(kept);
        var checkpoints = new FileContextStore(directory.resolve("views"), json);
        checkpoints.save("summary", new Checkpoint(compacted, current, 3));
        assertThat(checkpoints.load("summary").view()).isEqualTo(compacted);
        assertThat(original.messages()).hasSize(8);
        var prompt = builder.prepare("system", List.of(), compacted, List.of(), directory, TestSupport::unexpectedModelCall).prompt();
        assertThat(prompt.getInstructions().get(1).getText()).contains("updated summary", "not new instructions");
        assertThat(builder.compact(compacted, retainedTokens, summaryModel::call))
                .isSameAs(compacted);
        assertThat(calls).hasValue(1);
    }

    @Test
    void compactsInsideOneTurnAndKeepsAllResultsOfTheNewestToolRound() {
        UUID turn = UUID.randomUUID();
        var older = List.of(user(turn, "implement login without Redis"), intent(turn, "old"),
                result(turn, "old", "earlier progress"));
        var latestIntent = new Entry(turn, TranscriptMessage.from(AssistantMessage.builder()
                .content("verify both files").toolCalls(List.of(
                        new AssistantMessage.ToolCall("a", "function", "sample", "{}"),
                        new AssistantMessage.ToolCall("b", "function", "sample", "{}"))).build()));
        var latestResult = new Entry(turn, TranscriptMessage.from(ToolResponseMessage.builder().responses(List.of(
                new ToolResponse("a", "sample", "first output"),
                new ToolResponse("b", "sample", "second output"))).build()));
        var all = new java.util.ArrayList<>(older);
        all.addAll(List.of(latestIntent, latestResult));
        var original = new ContextView(null, all);
        ChatModel model = request -> {
            assertThat(request.getContents()).contains("implement login without Redis", "earlier progress")
                    .doesNotContain("verify both files", "first output", "second output");
            return TestSupport.text(TestSupport.summaryJson("implement login without Redis; earlier progress recorded"));
        };
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(1000));
        // Even a budget smaller than the last result retains its entire intent/result unit.
        var compacted = builder.compact(original, 1, model::call);
        assertThat(compacted.messages()).containsExactly(latestIntent, latestResult);
        assertThat(compacted.summary().goal()).contains("without Redis");
        assertThat(original.messages()).containsExactlyElementsOf(all);
        var views = new FileContextStore(directory.resolve("views"), json);
        views.save("split", new Checkpoint(compacted, turn, all.size()));
        assertThat(builder.prepare("system", List.of(), views.load("split").view(), List.of(), directory, TestSupport::unexpectedModelCall).view())
                .isEqualTo(compacted);
        assertThat(builder.compact(compacted, 1, model::call)).isSameAs(compacted);
    }

    @Test
    void countsUserAndPlainAssistantMessagesAsIndependentUnitsRegardlessOfTurnId() {
        UUID turn = UUID.randomUUID();
        var question = user(turn, "original request");
        var steer = user(turn, "correction: use Session");
        var answer = new Entry(turn, TranscriptMessage.from(new AssistantMessage("latest answer")));
        var original = new ContextView(null, List.of(question, steer, answer));
        ChatModel model = request -> {
            assertThat(request.getContents()).contains("original request", "correction: use Session")
                    .doesNotContain("latest answer");
            return TestSupport.text(TestSupport.summaryJson("use Session"));
        };
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(1000));
        assertThat(builder.compact(original, 1, model::call).messages()).containsExactly(answer);
        assertThat(builder.compact(ContextView.empty(), 0, model::call)).isEqualTo(ContextView.empty());
        assertThat(builder.compact(new ContextView(null, List.of(answer)), 0, model::call).messages()).containsExactly(answer);
        assertThatThrownBy(() -> builder.compact(original, -1, model::call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidJsonOrModelFailuresDoNotReplaceTheOldSummaryOrPersistedView() throws Exception {
        var view = new ContextView(TestSupport.summary("old"),
                List.of(user(UUID.randomUUID(), "before"), user(UUID.randomUUID(), "now")));
        var checkpoints = new FileContextStore(directory.resolve("views"), json);
        checkpoints.save("a", new Checkpoint(view, view.messages().getLast().turnId(), 1));
        var returned = new java.util.concurrent.atomic.AtomicReference<String>();
        ChatModel model = request -> TestSupport.text(returned.get());
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(64000));
        var valid = json.readTree(TestSupport.summaryJson("new"));
        var missing = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("constraints");
        var wrongType = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongType).put("goal", 42);
        var invalidItem = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) invalidItem.get("constraints")).add(false);
        var missingProgress = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingProgress.get("progress")).remove("blocked");
        var extra = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("extra", "unrequested");
        var nullValue = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) nullValue).putNull("constraints");
        for (String invalid : List.of("not JSON", "[]", "null", missing.toString(), wrongType.toString(),
                invalidItem.toString(), missingProgress.toString(), extra.toString(), nullValue.toString(),
                valid + " {}", "```json\n" + valid + "\n```", "{\"goal\":\"one\",\"goal\":\"two\"}")) {
            returned.set(invalid);
            assertThatThrownBy(() -> builder.compact(view, 0, model::call)).hasMessageContaining("invalid structured JSON");
            assertThat(checkpoints.load("a").view()).isEqualTo(view);
        }
        for (var response : List.of(TestSupport.text(" "), TestSupport.tool("bad", "sample"),
                new org.springframework.ai.chat.model.ChatResponse(List.of()))) {
            var invalidModel = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(64000));
            assertThatThrownBy(() -> invalidModel.compact(view, 0, request -> response)).isInstanceOf(IllegalStateException.class);
        }
        var offline = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(64000));
        assertThatThrownBy(() -> offline.compact(view, 0, request -> { throw new IllegalStateException("offline"); })).hasMessage("offline");
        assertThat(view.summary().goal()).isEqualTo("old");
        assertThat(view.messages()).hasSize(2);
    }

    @Test
    void automaticCompactionFailureKeepsThePreviousCheckpointAndStopsNormalReasoning() {
        var transcript = TestSupport.store(directory.resolve("records"));
        var views = TestSupport.contextStore(directory);
        var old = TestSupport.completed("old " + "x ".repeat(8500), "old answer");
        transcript.save("auto", old);
        var oldView = new ContextView(null, old.messages().stream().map(message -> new Entry(old.id(), message)).toList());
        var before = new Checkpoint(oldView, old.id(), old.messages().size());
        views.save("auto", before);
        var calls = new AtomicInteger();
        ChatModel model = request -> {
            calls.incrementAndGet();
            assertThat(request.getInstructions().getFirst().getText()).contains("handoff summary");
            return TestSupport.text("invalid JSON");
        };
        var tools = TestSupport.toolRegistry();
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(10000));
        var harness = new AssistantHarness(transcript, views, "system", builder, tools, TestSupport.toolRounds(tools));
        assertThatThrownBy(() -> new AgentRuntime(model, harness, 3, 4)
                .chat("auto", "continue", directory, List.of(), event -> {}))
                .hasMessageContaining("invalid structured JSON");
        assertThat(calls).hasValue(1);
        assertThat(views.load("auto")).isEqualTo(before);
        assertThat(transcript.load("auto").getLast().status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(transcript.load("auto").getFirst()).isEqualTo(old);
    }

    @Test
    void summaryConsumesTheSameCallBudgetAndCannotBypassItsLimit() {
        var transcript = TestSupport.store(directory.resolve("records"));
        var views = TestSupport.contextStore(directory);
        var old = TestSupport.completed("old " + "x ".repeat(8500), "old answer");
        transcript.save("shared", old);
        var calls = new AtomicInteger();
        ChatModel model = request -> {
            calls.incrementAndGet();
            assertThat(request.getInstructions().getFirst().getText()).contains("handoff summary");
            assertThat(request.getOptions().getMaxTokens()).isEqualTo(256);
            return TestSupport.text(TestSupport.summaryJson("resume old work"));
        };
        var tools = TestSupport.toolRegistry();
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(10000));
        var harness = new AssistantHarness(transcript, views, "system", builder, tools, TestSupport.toolRounds(tools));
        var events = new java.util.ArrayList<com.guodi.assistant.core.event.AgentEvent>();
        assertThatThrownBy(() -> new AgentRuntime(model, harness, 1, 4)
                .chat("shared", "continue", directory, List.of(), events::add)).hasMessageContaining("预算");
        assertThat(calls).hasValue(1);
        assertThat(events.stream().filter(event -> event instanceof com.guodi.assistant.core.event.ModelStarted).toList())
                .containsExactly(new com.guodi.assistant.core.event.ModelStarted(1));
        assertThat(views.load("shared").view().summary().goal()).isEqualTo("resume old work");
        assertThat(transcript.load("shared").getFirst()).isEqualTo(old);
        assertThat(transcript.load("shared").getLast().status()).isEqualTo(ConversationTurn.Status.FAILED);
    }

    @Test
    void oversizedSummaryInputStopsBeforeCallingTheModel() {
        var view = new ContextView(null, List.of(user(UUID.randomUUID(), "old " + "x ".repeat(2000)),
                user(UUID.randomUUID(), "now")));
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(1000));
        assertThatThrownBy(() -> builder.compact(view, 0, TestSupport::unexpectedModelCall))
                .hasMessageContaining("Context exceeds");
        assertThat(view.messages()).hasSize(2);
    }

    @Test
    void toolDefinitionsAndTheLatestWholeToolRoundAreIncludedInTheInputLimit() {
        var builder = new WindowContextBuilder(new ToolResultStore(), json, TestSupport.budget(1000));
        var definition = org.springframework.ai.tool.definition.ToolDefinition.builder().name("huge")
                .description("description ".repeat(2000)).inputSchema("{\"type\":\"object\"}").build();
        var callback = mock(org.springframework.ai.tool.ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        assertThatThrownBy(() -> builder.prepare("system", List.of(callback), ContextView.empty(),
                List.of(user(UUID.randomUUID(), "now")), directory, TestSupport::unexpectedModelCall))
                .hasMessageContaining("Context exceeds");

        UUID turn = UUID.randomUUID();
        var intent = new Entry(turn, TranscriptMessage.from(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("large", "function", "write_file",
                        "{\"content\":\"" + "x ".repeat(2000) + "\"}"))).build()));
        var result = result(turn, "large", "written");
        assertThatThrownBy(() -> builder.prepare("system", List.of(), ContextView.empty(),
                List.of(intent, result), directory, TestSupport::unexpectedModelCall))
                .hasMessageContaining("Context exceeds");
        assertThat(intent.message().toolCalls().getFirst().arguments()).contains("x ".repeat(2000));
    }

    @Test
    void repairsMissingResponsesWithinTheViewWithoutChangingRawFacts() {
        UUID turn = UUID.randomUUID();
        var intent = new Entry(turn, TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("c1", "function", "sample", "{}"),
                new AssistantMessage.ToolCall("c2", "function", "sample", "{}"))).build()));
        var partial = result(turn, "c1", "known");
        var prepared = TestSupport.contextBuilder().prepare("system", List.of(), ContextView.empty(),
                List.of(intent, partial, user(UUID.randomUUID(), "continue")), directory, TestSupport::unexpectedModelCall);
        assertThat(prepared.view().messages().get(1).message().toolResponses()).extracting(ToolResponse::id)
                .containsExactly("c1", "c2");
        assertThat(prepared.view().messages().get(1).message().toolResponses().getLast().responseData())
                .contains("outcome is unknown", "partially executed", "inspect the actual state",
                        "Do not repeat side effects without verification");
        assertThat(partial.message().toolResponses()).hasSize(1);
    }

    @Test
    void legacyViewRebuildsFromRawRecordsButInvalidNewViewDoesNotSilentlyReset() throws Exception {
        Path root = directory.resolve("views");
        var store = new FileContextStore(root, json);
        Path path = JsonFiles.conversationDirectory(root, "a").resolve("view.json");
        Files.createDirectories(path.getParent());
        json.writeValue(path.toFile(), java.util.Map.of("view", List.of(user(UUID.randomUUID(), "old").message()),
                "sourceTurnId", UUID.randomUUID(), "sourceMessageCount", 1));
        assertThat(store.load("a")).isEqualTo(new Checkpoint(ContextView.empty(), null, 0));
        json.writeValue(path.toFile(), java.util.Map.of("view", java.util.Map.of(
                "summary", "old Markdown summary", "messages", List.of()),
                "sourceTurnId", UUID.randomUUID(), "sourceMessageCount", 3));
        assertThat(store.load("a")).isEqualTo(new Checkpoint(ContextView.empty(), null, 0));
        Files.writeString(path, "{\"view\":{\"summary\":null,\"messages\":[{\"turnId\":null}]}}");
        assertThatThrownBy(() -> store.load("a")).isInstanceOf(java.io.UncheckedIOException.class);
        Files.writeString(path, "broken json");
        assertThatThrownBy(() -> store.load("a")).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void resultStorageRejectsSymlinksAndDoesNotOverwriteChangedResults() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(project.resolve(".agent"), outside);
        var store = new ToolResultStore();
        String identity = "a".repeat(64);
        assertThatThrownBy(() -> store.save(project, identity, "output")).isInstanceOf(java.io.UncheckedIOException.class);
        try (var paths = Files.list(outside)) {
            assertThat(paths.toList()).isEmpty();
        }
        Files.delete(project.resolve(".agent"));
        Path relative = store.save(project, identity, "output");
        Files.writeString(project.resolve(relative), "changed");
        assertThatThrownBy(() -> store.save(project, identity, "output")).hasMessageContaining("Cannot save");
        assertThat(Files.readString(project.resolve(relative))).isEqualTo("changed");
    }

    @Test
    void runtimePersistsFullResultWhileModelAndRestoredViewUseOnlyPreview() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        var original = new LargeTool();
        var tools = TestSupport.toolRegistry(ToolCallbacks.from(original));
        var transcript = TestSupport.store(directory.resolve("records"));
        var views = TestSupport.contextStore(directory);
        var harness = new AssistantHarness(transcript, views, "system", TestSupport.contextBuilder(new ToolResultStore(), 256),
                tools, TestSupport.toolRounds(tools));
        var calls = new AtomicInteger();
        ChatModel model = prompt -> {
            if (calls.incrementAndGet() == 1) return TestSupport.tool("c1", "large");
            String result = ((ToolResponseMessage) prompt.getInstructions().getLast()).getResponses().getFirst().responseData();
            assertThat(result).contains(".agent/tool-results/").hasSizeLessThan(1000);
            return TestSupport.text("done");
        };
        new AgentRuntime(model, harness, 3, 4).chat("a", "test", project, List.of(), event -> {});
        var saved = transcript.load("a").getFirst();
        assertThat(saved.status()).isEqualTo(ConversationTurn.Status.COMPLETED);
        assertThat(saved.messages().get(2).toolResponses().getFirst().responseData()).isEqualTo(json.writeValueAsString(original.content));
        assertThat(views.load("a").view().messages().getLast().message().toolResponses().getFirst().responseData())
                .contains(".agent/tool-results/");
    }

    @Test
    void failedResultPublicationLeavesRawOutputAndDoesNotRequestTheModelAgain() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.createSymbolicLink(project.resolve(".agent"), directory);
        var original = new LargeTool();
        var tools = TestSupport.toolRegistry(ToolCallbacks.from(original));
        var transcript = TestSupport.store(directory.resolve("records"));
        var harness = new AssistantHarness(transcript, TestSupport.contextStore(directory), "system",
                TestSupport.contextBuilder(new ToolResultStore(), 256), tools, TestSupport.toolRounds(tools));
        var calls = new AtomicInteger();
        ChatModel model = prompt -> { calls.incrementAndGet(); return TestSupport.tool("c1", "large"); };
        assertThatThrownBy(() -> new AgentRuntime(model, harness, 3, 4)
                .chat("a", "test", project, List.of(), event -> {})).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(calls).hasValue(1);
        var saved = transcript.load("a").getFirst();
        assertThat(saved.status()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(saved.messages().getLast().toolResponses().getFirst().responseData()).isEqualTo(json.writeValueAsString(original.content));
    }

    private static Entry user(UUID turn, String text) {
        return new Entry(turn, TranscriptMessage.from(new UserMessage(text)));
    }

    private static Entry intent(UUID turn, String id) {
        return new Entry(turn, TranscriptMessage.from(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", "sample", "{}"))).build()));
    }

    private static Entry result(UUID turn, String id, String content) {
        return new Entry(turn, TranscriptMessage.from(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponse(id, "sample", content))).build()));
    }

    public static class LargeTool {
        final String content = "HEAD" + "中😀".repeat(10000) + "TAIL";
        @Tool(name = "large", description = "large fixture")
        public String large() { return content; }
    }
}
