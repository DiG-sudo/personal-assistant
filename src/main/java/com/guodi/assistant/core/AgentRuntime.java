package com.guodi.assistant.core;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.assistant.domain.TranscriptMessage;

/** Fixed model/tool loop. Knows nothing about files, memory strategies, HTTP or business tools. */
public final class AgentRuntime {
    private final ChatModel model;
    private final AssistantHarness harness;
    private final int maxModelCalls;
    private final int maxContextChars;
    private final int maxToolsPerRound;

    public AgentRuntime(ChatModel model, AssistantHarness harness, int maxModelCalls,
            int maxContextChars, int maxToolsPerRound) {
        this.model = model;
        this.harness = harness;
        this.maxModelCalls = maxModelCalls;
        this.maxContextChars = maxContextChars;
        this.maxToolsPerRound = maxToolsPerRound;
    }

    /** 聊天入口；Harness 创建执行状态，并在生命周期中调用这个循环。 */
    public AssistantHarness.Reply chat(String conversationId, String message) {
        return harness.aroundRun(conversationId, message, this::runLoop);
    }

    private void runLoop(AgentExecution execution) {
        Function<Prompt, ChatResponse> modelCall = request -> callModel(execution, request);
        for (int step = 1; step <= maxModelCalls; step++) {
            ChatResponse response = harness.aroundReasoning(execution,
                    prompt -> harness.aroundModelCall(execution, prompt, modelCall));
            validateResponse(response);
            AssistantMessage assistant = response.getResult().getOutput();

            // 最终回答和工具意图都先保存；保存失败就停止，不执行外部工具。
            harness.checkpoint(execution, TranscriptMessage.from(assistant));
            if (!assistant.hasToolCalls()) {
                return;
            }
            if (execution.modelCalls() == maxModelCalls) {
                throw new AgentException("模型调用预算已耗尽，本轮尚未执行的工具不会运行。");
            }
            harness.aroundToolRound(execution, assistant);
        }
        throw new AgentException("模型调用预算已耗尽。");
    }

    private ChatResponse callModel(AgentExecution execution, Prompt request) {
        if (characters(request.getInstructions()) > maxContextChars) {
            throw new AgentException("本轮上下文超过字符预算，请缩小请求或调整上下文组件。");
        }
        execution.modelCallStarted(maxModelCalls);
        return model.call(request);
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
