package com.guodi.assistant.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
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

    public Result run(List<Message> initialContext, String conversationId,
            Consumer<List<TranscriptMessage>> checkpoint) {
        List<Message> messages = new ArrayList<>(initialContext);
        int turnStart = messages.size() - 1;
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.callbacks()).internalToolExecutionEnabled(false).build();
        var toolContext = new ToolContext(Map.of("conversationId", conversationId));

        for (int callNumber = 1; callNumber <= maxModelCalls; callNumber++) {
            if (characters(messages) > maxContextChars) {
                throw new AgentException("本轮上下文超过字符预算，请缩小请求或调整上下文组件。");
            }
            var response = model.call(new Prompt(messages, options));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new AgentException("模型没有返回有效响应。");
            }
            AssistantMessage assistant = response.getResult().getOutput();
            if (!assistant.hasToolCalls() && (assistant.getText() == null || assistant.getText().isBlank())) {
                throw new AgentException("模型返回了空回答。");
            }
            if (assistant.hasToolCalls()) {
                validateCalls(assistant);
            }
            messages.add(assistant);
            // Persist intent before calling tools, including tools with external side effects.
            checkpoint.accept(transcript(messages, turnStart));
            if (!assistant.hasToolCalls()) {
                return new Result(transcript(messages, turnStart), callNumber);
            }
            // The last available model call must leave room to summarize tool results.
            if (callNumber == maxModelCalls) {
                throw new AgentException("模型调用预算已耗尽，本轮尚未执行的工具不会运行。");
            }
            var results = tools.execute(assistant.getToolCalls(), toolContext);
            messages.add(ToolResponseMessage.builder().responses(results).build());
            checkpoint.accept(transcript(messages, turnStart));
        }
        throw new AgentException("模型调用预算已耗尽。");
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

    private static List<TranscriptMessage> transcript(List<Message> messages, int start) {
        return messages.subList(start, messages.size()).stream().map(TranscriptMessage::from).toList();
    }

    private static long characters(List<Message> messages) {
        return messages.stream().mapToLong(message -> switch (message.getMessageType()) {
            case SYSTEM -> message.getText().length();
            default -> TranscriptMessage.from(message).characters();
        }).sum();
    }

    public record Result(List<TranscriptMessage> messages, int modelCalls) {
        public Result {
            messages = List.copyOf(messages);
        }
        public String answer() {
            return messages.getLast().text();
        }
    }
}
