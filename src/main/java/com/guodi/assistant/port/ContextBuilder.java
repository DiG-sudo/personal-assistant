package com.guodi.assistant.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.chat.messages.Message;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.TranscriptMessage;

/** Builds a model-only view without changing stored history; keep entire tool exchanges together. */
public interface ContextBuilder {
    List<Message> build(List<ConversationTurn> history, List<String> memories, String userMessage);

    /** Called before each reasoning step. Existing builders retain the entire active turn. */
    default ModelContext prepare(List<ConversationTurn> history, List<String> memories,
            List<TranscriptMessage> activeTurn) {
        var messages = new ArrayList<>(build(history, memories, activeTurn.getFirst().text()));
        activeTurn.stream().skip(1).map(TranscriptMessage::toMessage).forEach(messages::add);
        // An old/custom builder may not report its selection; never invent that evidence.
        return new ModelContext(messages, Optional.empty());
    }

    record ModelContext(List<Message> messages, Optional<Selection> selection) {
        public ModelContext { messages = List.copyOf(messages); }
    }

    /** Memory indexes refer to the frozen recall list for this run. */
    record Selection(List<UUID> turnIds, List<Integer> memoryIndexes) {
        public Selection {
            turnIds = List.copyOf(turnIds);
            memoryIndexes = List.copyOf(memoryIndexes);
        }
    }
}
