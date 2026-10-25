package com.guodi.assistant.domain;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Versioned JSON-friendly transcript; never serializes provider implementation classes. */
public record TranscriptMessage(String role, String text,
        List<AssistantMessage.ToolCall> toolCalls,
        List<ToolResponseMessage.ToolResponse> toolResponses) {

    public TranscriptMessage {
        toolCalls = List.copyOf(toolCalls);
        toolResponses = List.copyOf(toolResponses);
    }

    public static TranscriptMessage from(Message message) {
        return switch (message) {
            case AssistantMessage assistant -> new TranscriptMessage("assistant",
                    assistant.getText() == null ? "" : assistant.getText(), assistant.getToolCalls(), List.of());
            case ToolResponseMessage tool -> new TranscriptMessage("tool", "", List.of(), tool.getResponses());
            case UserMessage user -> new TranscriptMessage("user", user.getText(), List.of(), List.of());
            default -> throw new IllegalArgumentException("Unsupported transcript role: " + message.getMessageType());
        };
    }

    public Message toMessage() {
        return switch (role) {
            case "user" -> new UserMessage(text);
            case "assistant" -> AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
            case "tool" -> ToolResponseMessage.builder().responses(toolResponses).build();
            default -> throw new IllegalStateException("Unknown persisted message role: " + role);
        };
    }

    public int characters() {
        return text.length()
                + toolCalls.stream().mapToInt(call -> call.id().length() + call.name().length() + call.arguments().length()).sum()
                + toolResponses.stream().mapToInt(result -> result.id().length() + result.name().length() + result.responseData().length()).sum();
    }
}
