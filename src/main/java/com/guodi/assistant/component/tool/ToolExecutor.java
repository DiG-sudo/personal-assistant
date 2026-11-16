package com.guodi.assistant.component.tool;

import java.util.concurrent.CancellationException;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;

/** Executes a single tool call and returns the complete result. */
public final class ToolExecutor {
    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolOutcome execute(ToolCall call, ToolContext context) {
        var binding = registry.findBinding(call.name());
        if (binding == null) {
            return new ToolOutcome(call, "工具未启用或不存在：" + call.name(), false);
        }

        try {
            String result = binding.callback().call(call.arguments(), context);
            return new ToolOutcome(call, result == null ? "" : result, true);
        } catch (RuntimeException error) {
            // Spring AI can wrap an exception thrown by an annotated tool.
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof CancellationException cancelled) {
                    throw cancelled;
                }
            }
            return new ToolOutcome(call, "工具执行失败：" + error.getMessage(), false);
        }
    }

}
