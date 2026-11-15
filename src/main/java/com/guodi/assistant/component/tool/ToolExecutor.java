package com.guodi.assistant.component.tool;

import java.util.concurrent.CancellationException;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;

/** Executes a single tool call and bounds the result sent back to the model. */
public final class ToolExecutor {
    private final ToolRegistry registry;
    private final int resultChars;

    public ToolExecutor(ToolRegistry registry, int resultChars) {
        this.registry = registry;
        this.resultChars = resultChars;
    }

    public ToolOutcome execute(ToolCall call, ToolContext context) {
        var binding = registry.findBinding(call.name());
        if (binding == null) {
            return outcome(call, "工具未启用或不存在：" + call.name(), false);
        }

        try {
            String result = binding.callback().call(call.arguments(), context);
            return outcome(call, result == null ? "" : result, true);
        } catch (RuntimeException error) {
            // Spring AI can wrap an exception thrown by an annotated tool.
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof CancellationException cancelled) {
                    throw cancelled;
                }
            }
            return outcome(call, "工具执行失败：" + error.getMessage(), false);
        }
    }

    private ToolOutcome outcome(ToolCall call, String content, boolean success) {
        if (content.length() > resultChars) {
            content = content.substring(0, resultChars) + "\n[工具结果已截断]";
        }
        return new ToolOutcome(call, content, success);
    }
}
