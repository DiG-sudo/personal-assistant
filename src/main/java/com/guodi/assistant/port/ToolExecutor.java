package com.guodi.assistant.port;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** The exposed callbacks and the allowed execution set must come from the same registry. */
public interface ToolExecutor {
    List<ToolCallback> callbacks();
    List<ToolResponse> execute(List<ToolCall> calls, ToolContext context);
}
