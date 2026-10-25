package com.guodi.assistant.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.guodi.assistant.domain.TranscriptMessage;
import com.guodi.assistant.port.ToolExecutor;

/** Fixed model/tool loop. Knows nothing about files, memory strategies, HTTP or business tools. */
public final class AgentRuntime {
    private final ChatModel model;
    private final ToolExecutor tools;
    private final int maxModelCalls;
    private final int maxContextChars;
    private final int maxToolsPerRound;

    public AgentRuntime(ChatModel model, ToolExecutor tools, int maxModelCalls,
            int maxContextChars, int maxToolsPerRound) {
        this.model = model;
        this.tools = tools;
        this.maxModelCalls = maxModelCalls;
        this.maxContextChars = maxContextChars;
        this.maxToolsPerRound = maxToolsPerRound;
    }

    /** Only committed messages feed the next step. A failed checkpoint stops execution immediately. */
    public void run(AgentExecution execution, RuntimeHooks hooks) {
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.callbacks()).internalToolExecutionEnabled(false).build();
        var toolContext = new ToolContext(Map.of("conversationId", execution.conversationId(),
                "turnId", execution.turn().id().toString()));

        for (int step = 1; step <= maxModelCalls; step++) {
            var response = hooks.aroundReasoning(execution, messages -> {
                var request = new Prompt(messages, options);
                return hooks.aroundModelCall(execution, request, actualRequest -> {
                    if (characters(actualRequest.getInstructions()) > maxContextChars) {
                        throw new AgentException("本轮上下文超过字符预算，请缩小请求或调整上下文组件。");
                    }
                    execution.modelCallStarted(maxModelCalls);
                    return model.call(actualRequest);
                });
            });
            // Validate the returned value once, including a result replaced by a wrapper.
            validateResponse(response);
            AssistantMessage assistant = response.getResult().getOutput();
            // Persist intent before calling tools, including tools with external side effects.
            hooks.checkpoint(execution, TranscriptMessage.from(assistant));
            if (!assistant.hasToolCalls()) {
                return;
            }
            // The last available model call must leave room to summarize tool results.
            if (execution.modelCalls() == maxModelCalls) {
                throw new AgentException("模型调用预算已耗尽，本轮尚未执行的工具不会运行。");
            }
            var calls = assistant.getToolCalls();
            var results = hooks.aroundToolRound(execution, calls, () -> tools.execute(calls, toolContext));
            hooks.checkpoint(execution, TranscriptMessage.from(
                    ToolResponseMessage.builder().responses(results).build()));
        }
        throw new AgentException("模型调用预算已耗尽。");
    }

    private void validateResponse(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new AgentException("模型没有返回有效响应。");
        }
        var assistant = response.getResult().getOutput();
        if (!assistant.hasToolCalls() && (assistant.getText() == null || assistant.getText().isBlank())) {
            throw new AgentException("模型返回了空回答。");
        }
        if (assistant.hasToolCalls()) {
            validateCalls(assistant);
        }
    }

    private void validateCalls(AssistantMessage assistant) {
        if (assistant.getToolCalls().size() > maxToolsPerRound) {
            throw new AgentException("模型单轮工具调用数量超过限制。");
        }
        var ids = new HashSet<String>();
        for (var call : assistant.getToolCalls()) {
            if (call.id() == null || call.id().isBlank() || call.name() == null || call.name().isBlank()
                    || call.arguments() == null || !ids.add(call.id())) {
                throw new AgentException("模型返回了无效或重复的工具调用标识。");
            }
        }
    }

    private static long characters(List<Message> messages) {
        return messages.stream().mapToLong(message -> switch (message.getMessageType()) {
            case SYSTEM -> message.getText().length();
            default -> TranscriptMessage.from(message).characters();
        }).sum();
    }
}
