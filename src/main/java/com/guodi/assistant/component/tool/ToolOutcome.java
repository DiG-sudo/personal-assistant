package com.guodi.assistant.component.tool;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;

public record ToolOutcome(ToolCall call, String responseData, boolean success) {
    public ToolResponse toolResponse() {
        return new ToolResponse(call.id(), call.name(), responseData);
    }
}
