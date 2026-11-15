package com.guodi.assistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties("manifest")
public record ConversationTurn(int schemaVersion, UUID id, Instant startedAt, Status status,
        List<TranscriptMessage> messages, String failure) {

    public static final int CURRENT_SCHEMA = 3;

    public enum Status { RUNNING, COMPLETED, FAILED }

    public ConversationTurn {
        messages = List.copyOf(messages);
    }

    public static ConversationTurn start(String userMessage) {
        return new ConversationTurn(CURRENT_SCHEMA, UUID.randomUUID(), Instant.now(), Status.RUNNING,
                List.of(new TranscriptMessage("user", userMessage, List.of(), List.of())), null);
    }

    public ConversationTurn checkpoint(List<TranscriptMessage> transcript) {
        return new ConversationTurn(CURRENT_SCHEMA, id, startedAt, Status.RUNNING, transcript, null);
    }

    public ConversationTurn complete() {
        return new ConversationTurn(CURRENT_SCHEMA, id, startedAt, Status.COMPLETED, messages, null);
    }

    public ConversationTurn fail(String reason) {
        return new ConversationTurn(CURRENT_SCHEMA, id, startedAt, Status.FAILED, messages, reason);
    }
}
