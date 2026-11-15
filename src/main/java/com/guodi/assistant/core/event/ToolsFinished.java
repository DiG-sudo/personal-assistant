package com.guodi.assistant.core.event;

import java.util.List;

import com.guodi.assistant.component.tool.ToolOutcome;

public record ToolsFinished(List<ToolOutcome> outcomes, long elapsedMs) implements AgentEvent {
    public ToolsFinished {
        outcomes = List.copyOf(outcomes);
    }
}
