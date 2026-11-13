// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.port;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;

/** Implementations atomically replace one turn, preserving its id and start time. */
// 聊天存储的接口约定；Harness 依赖这个接口，实际读写交给 component/storage 中的实现。
public interface ConversationStore {
    //支持读取会话记录
    // 传入会话 ID，返回该会话保存的各轮记录；这里只声明方法，不包含文件或数据库代码。
    List<ConversationTurn> load(String conversationId);
    //支持保存一轮会话记录
    // 保存或替换指定 turn 的检查点；同一 turn 保持相同 ID 和开始时间，由实现保证原子替换。
    void save(String conversationId, ConversationTurn turn);
}
