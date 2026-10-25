package com.guodi.assistant.component.model;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Deterministic local demo, never impersonates a real LLM or remote search service. */
@com.guodi.assistant.versioning.ComponentRevision(id = "demo", version = "1.0.0")
public final class DemoChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
        var last = prompt.getInstructions().getLast();
        if (last instanceof ToolResponseMessage tools) {
            String result = tools.getResponses().stream()
                    .map(tool -> tool.name() + ": " + tool.responseData())
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            boolean video = tools.getResponses().stream().anyMatch(tool -> tool.name().equals("video_search_links"));
            return text("演示模式 · 工具返回：\n" + result
                    + (video ? "\n视频工具只生成搜索入口链接，未检索或验证视频。" : ""));
        }
        String user = last.getText();
        String tool = null;
        String args = "{}";
        if (user.contains("时间") || user.contains("几点")) {
            tool = "current_time";
        } else if (user.contains("视频")) {
            tool = "video_search_links";
            args = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("query", user).toString();
        }
        if (tool != null) {
            String name = tool;
            boolean enabled = prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks().stream().anyMatch(callback -> callback.getToolDefinition().name().equals(name));
            if (!enabled) {
                return text("演示模式：对应工具尚未启用。");
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", tool, args))).build())));
        }
        if (user.contains("记忆") || user.contains("记得")) {
            String memory = prompt.getInstructions().stream().filter(message -> message instanceof UserMessage
                    && message.getText().startsWith("以下是检索到的历史记忆数据"))
                    .map(message -> message.getText()).findFirst().orElse("没有加载到长期记忆。");
            return text("演示模式 · " + memory);
        }
        return text("演示模式：已收到「" + user + "」。这是本地确定性演示模型；配置真实模型后即可自由聊天。");
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
