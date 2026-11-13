package com.guodi.assistant.core.event;

import java.util.UUID;

public record TurnSteer(UUID turnId, UUID messageId) implements AgentEvent {}
