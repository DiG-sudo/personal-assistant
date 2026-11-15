package com.guodi.assistant.domain;

import java.time.Instant;

/** Durable conversation identity; a null projectPath denotes an unbound legacy conversation. */
public record ConversationInfo(String conversationId, String projectPath, String title,
        Instant createdAt, Instant updatedAt) {}
