package com.guodi.assistant.core.event;

import com.guodi.assistant.core.AssistantHarness;

public record TurnCompleted(AssistantHarness.Reply reply) implements AgentEvent {}