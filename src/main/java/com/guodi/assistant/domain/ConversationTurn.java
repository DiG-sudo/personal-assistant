package com.guodi.assistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.guodi.assistant.versioning.ExecutionManifest;

/** Schema 2 freezes the execution manifest; schema 1 records remain readable without fabricated versions. */
public record ConversationTurn(int schemaVersion, UUID id, Instant startedAt, Status status,
        List<TranscriptMessage> messages, String failure, ExecutionManifest manifest) {

    public enum Status { RUNNING, COMPLETED, FAILED }

    public ConversationTurn {
        messages = List.copyOf(messages);
    }

    public static ConversationTurn start(String userMessage, ExecutionManifest manifest) {
        return new ConversationTurn(2, UUID.randomUUID(), Instant.now(), Status.RUNNING,
                List.of(new TranscriptMessage("user", userMessage, List.of(), List.of())), null, manifest);
    }

    public ConversationTurn checkpoint(List<TranscriptMessage> transcript) {
        return new ConversationTurn(schemaVersion, id, startedAt, Status.RUNNING, transcript, null, manifest);
    }

    public ConversationTurn complete(List<TranscriptMessage> transcript) {
        return new ConversationTurn(schemaVersion, id, startedAt, Status.COMPLETED, transcript, null, manifest);
    }

    public ConversationTurn fail(String reason) {
        return new ConversationTurn(schemaVersion, id, startedAt, Status.FAILED, messages, reason, manifest);
    }
}
