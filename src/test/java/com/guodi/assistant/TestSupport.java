package com.guodi.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.ConversationStore;

final class TestSupport {
    static com.guodi.assistant.versioning.ExecutionManifest manifest() {
        return new com.guodi.assistant.versioning.ExecutionManifest("test-fixture", "test-fixture", null,
                Map.of(), List.of(), Map.of());
    }

    static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static ChatResponse tool(String id, String name) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}"))).build())));
    }

    static ConversationTurn completed(String question, String answer) {
        return ConversationTurn.start(question, TestSupport.manifest()).complete(List.of(
                TranscriptMessage.from(new UserMessage(question)),
                TranscriptMessage.from(new AssistantMessage(answer))));
    }

    static final class Store implements ConversationStore {
        private final Map<String, List<ConversationTurn>> conversations = new ConcurrentHashMap<>();

        @Override
        public List<ConversationTurn> load(String id) {
            return List.copyOf(conversations.getOrDefault(id, List.of()));
        }

        @Override
        public void save(String id, ConversationTurn turn) {
            conversations.compute(id, (key, previous) -> {
                var turns = new ArrayList<>(previous == null ? List.of() : previous);
                turns.removeIf(existing -> existing.id().equals(turn.id()));
                turns.add(turn);
                turns.sort(Comparator.comparing(ConversationTurn::startedAt));
                return List.copyOf(turns);
            });
        }
    }
}
