package com.guodi.assistant.component.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.assistant.port.ToolExecutor;

/** One allowlist controls exposure and execution. Tools run sequentially in model call order. */
@com.guodi.assistant.versioning.ComponentRevision(id = "serial", version = "1.0.0")
public class RegisteredToolExecutor implements ToolExecutor {
    private final Map<String, ToolCallback> tools;
    private final int resultChars;

    public RegisteredToolExecutor(List<ToolCallback> callbacks, int resultChars) {
        var registered = new TreeMap<String, ToolCallback>();
        for (var callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (registered.putIfAbsent(name, callback) != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
        this.resultChars = resultChars;
    }

    @Override
    public List<ToolCallback> callbacks() {
        return List.copyOf(tools.values());
    }

    @Override
    public List<ToolResponse> execute(List<ToolCall> calls, ToolContext context) {
        var results = new ArrayList<ToolResponse>();
        for (var call : calls) {
            ToolCallback callback = tools.get(call.name());
            String content;
            if (callback == null) {
                content = "工具未启用或不存在：" + call.name();
            } else {
                try {
                    content = callback.call(call.arguments(), context);
                    if (content == null) {
                        content = "";
                    }
                } catch (RuntimeException error) {
                    content = failureResult(error);
                }
            }
            if (content.length() > resultChars) {
                content = content.substring(0, resultChars) + "\n[工具结果已截断]";
            }
            results.add(new ToolResponse(call.id(), call.name(), content));
        }
        return List.copyOf(results);
    }

    protected String failureResult(RuntimeException error) {
        return "工具执行失败：" + error.getMessage();
    }
}
