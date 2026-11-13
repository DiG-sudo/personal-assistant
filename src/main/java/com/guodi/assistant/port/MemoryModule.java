// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.port;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;

/** Conversation-scoped memory. observe must be idempotent by completedTurn.id(). */
// 长期记忆接口：只管理跨轮次使用的备注，不保存另一份完整聊天记录。
public interface MemoryModule {
    // 每轮开始时按会话召回记忆；query 是本轮查询文本，实现可用它筛选相关备注。
    List<String> recall(String conversationId, String query);
    // 本轮成功提交后观察结果并更新记忆；同一个 completedTurn.id() 重复调用不能重复写入。
    void observe(String conversationId, ConversationTurn completedTurn);
}
