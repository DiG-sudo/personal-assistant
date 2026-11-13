// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.model;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 UUID：轮次或调用的唯一标识类型。
import java.util.UUID;

// 导入 AssistantMessage：模型的助手响应消息。
import org.springframework.ai.chat.messages.AssistantMessage;
// 导入 ToolResponseMessage：一整组工具执行结果消息。
import org.springframework.ai.chat.messages.ToolResponseMessage;
// 导入 UserMessage：模型的用户或数据消息。
import org.springframework.ai.chat.messages.UserMessage;
// 导入 ChatModel：Spring AI 的模型调用接口。
import org.springframework.ai.chat.model.ChatModel;
// 导入 ChatResponse：Spring AI 的模型响应容器。
import org.springframework.ai.chat.model.ChatResponse;
// 导入 Generation：一次模型响应中的一个候选结果。
import org.springframework.ai.chat.model.Generation;
// 导入 Prompt：传给模型的消息及工具选项。
import org.springframework.ai.chat.prompt.Prompt;
// 导入 ToolCallingChatOptions：模型工具调用配置。
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Deterministic local demo, never impersonates a real LLM or remote search service. */
// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@com.guodi.assistant.versioning.ComponentRevision(id = "demo", version = "1.0.0")
// 实现 Spring AI ChatModel 的本地确定性模型；不请求远端大模型，便于验证完整调用链。
public class DemoChatModel implements ChatModel {
    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 接收 Harness 构造的 Prompt，根据最新一条输入生成演示响应。
    public ChatResponse call(Prompt prompt) {
        // getInstructions 返回消息列表；取最后一条来判断当前是用户请求还是工具结果。
        var last = prompt.getInstructions().getLast();
        // instanceof 同时判断类型并绑定变量 tools；最新消息是工具结果时，进入工具总结分支。
        if (last instanceof ToolResponseMessage tools) {
            // 把本轮工具响应逐条转换为展示文本。
            String result = tools.getResponses().stream()
                    // 为每个工具结果拼上工具名称和正文。
                    .map(tool -> tool.name() + ": " + tool.responseData())
                    // 用换行把多条结果合并；没有结果时用 orElse 返回空字符串。
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            // anyMatch 检查是否有视频入口工具的结果，用于追加演示限制说明。
            boolean video = tools.getResponses().stream().anyMatch(tool -> tool.name().equals("video_search_links"));
            // 把工具结果包装成普通 assistant 回答；此时不会再创建工具调用。
            return text("演示模式 · 工具返回：\n" + result
                    // 三元表达式：只有使用了视频工具，才补充未检索、未验证视频的说明。
                    + (video ? "\n视频工具只生成搜索入口链接，未检索或验证视频。" : ""));
        }
        // 取最新消息的文本，后面的关键词规则基于这段文本判断。
        String user = last.getText();
        // 用 null 表示尚未选中工具；这里只是演示规则，不是通用意图路由。
        String tool = null;
        // 默认工具参数是空 JSON 对象，时间工具不需要额外参数。
        String args = "{}";
        // 识别时间类演示请求，满足任一关键词就走时间工具。
        if (user.contains("时间") || user.contains("几点")) {
            // 选择已注册工具的名称，名称必须与允许列表一致。
            tool = "current_time";
        // 不是时间请求时再识别视频请求，决定后续工具及参数。
        } else if (user.contains("视频")) {
            // 选择视频搜索入口链接工具；该工具只生成链接，不执行网络搜索。
            tool = "video_search_links";
            // 使用 Jackson 创建 JSON 对象，避免直接拼字符串造成 JSON 转义错误。
            args = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    // 把用户文本写入 query 字段，再序列化成工具调用所需的 JSON 参数字符串。
                    .put("query", user).toString();
        }
        // 只有前面的规则选中了工具，才尝试返回工具调用响应。
        if (tool != null) {
            // 复制工具名供 Lambda 使用；name 不再被赋值，满足 Lambda 捕获变量的要求。
            String name = tool;
            // 先确认 Prompt 使用工具调用选项，并把选项绑定为 options，才能检查工具允许列表。
            boolean enabled = prompt.getOptions() instanceof ToolCallingChatOptions options
                    // 遍历本次请求实际暴露的工具；anyMatch 只接受名称完全匹配的回调。
                    && options.getToolCallbacks().stream().anyMatch(callback -> callback.getToolDefinition().name().equals(name));
            // 相关工具未启用时走普通文本回复，不生成不允许执行的工具调用。
            if (!enabled) {
                // 直接返回工具未启用的说明。
                return text("演示模式：对应工具尚未启用。");
            }
            // 创建一个候选模型响应；assistant 正文为空，实际动作写在 toolCalls 中。
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    // 创建带 UUID 调用 ID、function 类型、工具名和 JSON 参数的工具请求；这里只声明意图，不执行工具。
                    .toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", tool, args))).build())));
        }
        // 没有选中工具时，再处理记忆查询演示。
        if (user.contains("记忆") || user.contains("记得")) {
            // 从完整 Prompt 中查找 Harness/上下文组件提供的记忆数据消息。
            String memory = prompt.getInstructions().stream().filter(message -> message instanceof UserMessage
                    // 只选正文以前面约定的记忆数据前缀开头的 user 消息，不把普通历史当作召回记忆。
                    && message.getText().startsWith("以下是检索到的历史记忆数据"))
                    // 取匹配消息的文本；findFirst 只取第一条，找不到时返回默认说明。
                    .map(message -> message.getText()).findFirst().orElse("没有加载到长期记忆。");
            // 把召回的记忆数据作为普通文本回答返回。
            return text("演示模式 · " + memory);
        }
        // 未命中工具或记忆规则时，回复本地演示的固定确认文本。
        return text("演示模式：已收到「" + user + "」。这是本地确定性演示模型；配置真实模型后即可自由聊天。");
    }

    // 统一把普通字符串包装为 Spring AI 的模型响应，供上面的各文本分支复用。
    private static ChatResponse text(String text) {
        // AssistantMessage 是一条助手回答；Generation 表示一个候选；List.of 将候选放进 ChatResponse。
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
