// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.port;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 ToolCall：模型请求的一次工具调用，包含 ID、名称和参数。
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
// 导入 ToolResponse：对应某个工具调用 ID 的结果。
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
// 导入 ToolContext：不由模型参数直接提供的内部工具上下文。
import org.springframework.ai.chat.model.ToolContext;
// 导入 ToolCallback：Spring AI 的工具定义与调用接口。
import org.springframework.ai.tool.ToolCallback;

/** The exposed callbacks and the allowed execution set must come from the same registry. */
// 工具执行接口；提供给模型的工具和真正允许执行的工具必须来自同一套注册信息。
public interface ToolExecutor {
    // 返回当前启用的工具回调；Harness 用回调里的名称、描述、参数 Schema 告诉模型能调用什么。
    List<ToolCallback> callbacks();
    // 按模型请求执行工具；calls 带调用 ID、名称和 JSON 参数，context 带内部上下文，结果保留调用 ID 和顺序。
    List<ToolResponse> execute(List<ToolCall> calls, ToolContext context);
}
