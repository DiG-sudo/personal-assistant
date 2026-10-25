package com.guodi.assistant.core;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.assistant.domain.TranscriptMessage;

/** Explicit call boundaries, supplied per run; no registration, priorities or mutable global context. */
public interface RuntimeHooks {
    ChatResponse aroundReasoning(AgentExecution execution, Function<List<Message>, ChatResponse> reasoning);

    default ChatResponse aroundModelCall(AgentExecution execution, Prompt request, Function<Prompt, ChatResponse> call) {
        return call.apply(request);
    }

    default List<ToolResponse> aroundToolRound(AgentExecution execution, List<ToolCall> calls,
            Supplier<List<ToolResponse>> call) {
        return call.get();
    }

    /** Append one message and return only after its authoritative checkpoint has committed. */
    void checkpoint(AgentExecution execution, TranscriptMessage message);
}
