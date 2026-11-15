// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.model;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;

// 导入 AssistantMessage：模型的助手响应消息。
import org.springframework.ai.chat.messages.AssistantMessage;
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

/** Deterministic local demo. */
// 实现 Spring AI ChatModel 的本地确定性模型；不请求远端大模型，便于验证完整调用链。
public class DemoChatModel implements ChatModel {
    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 接收 Harness 构造的 Prompt，根据最新一条输入生成演示响应。
    public ChatResponse call(Prompt prompt) {
        String user = prompt.getInstructions().getLast().getText();
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
        // 未命中记忆规则时，回复本地演示的固定确认文本。
        return text("演示模式：已收到「" + user + "」。这是本地确定性演示模型；配置真实模型后即可自由聊天。");
    }

    // 统一把普通字符串包装为 Spring AI 的模型响应，供上面的各文本分支复用。
    private static ChatResponse text(String text) {
        // AssistantMessage 是一条助手回答；Generation 表示一个候选；List.of 将候选放进 ChatResponse。
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
