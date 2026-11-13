package com.guodi.assistant.core.event;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

public record ToolsStarted(List<ToolCall> calls) implements AgentEvent {
    public ToolsStarted {
        calls = List.copyOf(calls);
    }
}
