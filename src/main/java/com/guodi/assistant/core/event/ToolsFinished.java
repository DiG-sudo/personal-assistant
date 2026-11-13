package com.guodi.assistant.core.event;

import java.util.List;

import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;

public record ToolsFinished(List<ToolResponse> results, long elapsedMs) implements AgentEvent {
    public ToolsFinished {
        results = List.copyOf(results);
    }
}
