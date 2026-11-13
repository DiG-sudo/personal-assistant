package com.guodi.assistant.core.event;

import java.util.UUID;

public record TurnStarted(UUID turnId)implements  AgentEvent{}
