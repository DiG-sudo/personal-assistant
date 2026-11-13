// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.port;

// 导入 ArrayList：可追加的列表，用于构建消息或工具结果。
import java.util.ArrayList;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 Optional：显式表示可有可无的选择诊断。
import java.util.Optional;
// 导入 UUID：轮次或调用的唯一标识类型。
import java.util.UUID;

// 导入 Message：Spring AI 的通用消息类型。
import org.springframework.ai.chat.messages.Message;
// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;
// 导入 TranscriptMessage：可保存、可转换成模型消息的聊天数据。
import com.guodi.assistant.domain.TranscriptMessage;

/** Builds a model-only view without changing stored history; keep entire tool exchanges together. */
// 上下文组件接口：从已保存数据构建本次模型输入，只选择视图，不修改聊天存储。
public interface ContextBuilder {
    // 基础入口：接收历史轮次、召回记忆和当前用户文本，返回 Spring AI 的模型消息列表。
    List<Message> build(List<ConversationTurn> history, List<String> memories, String userMessage);

    /** Called before each reasoning step. Existing builders retain the entire active turn. */
    // default 表示接口自带默认实现；实现类可以直接使用，也可以覆盖它，每次推理都会调用。
    default ModelContext prepare(List<ConversationTurn> history, List<String> memories,
            // activeTurn 是当前轮次已提交的全部消息，包含用户输入及后续 assistant/tool 消息。
            List<TranscriptMessage> activeTurn) {
        // 先用第一条用户消息调用 build；ArrayList 复制结果，便于在本次模型视图中追加消息。
        var messages = new ArrayList<>(build(history, memories, activeTurn.getFirst().text()));
        // stream 遍历本轮消息；skip(1) 跳过已传给 build 的首条用户消息；map 转成模型消息，再依次加入列表。
        activeTurn.stream().skip(1).map(TranscriptMessage::toMessage).forEach(messages::add);
        // An old/custom builder may not report its selection; never invent that evidence.
        // 返回模型视图；Optional.empty() 表示这个实现没有提供历史/记忆选择明细，不编造诊断数据。
        return new ModelContext(messages, Optional.empty());
    }

    // record 自动生成构造器和访问器；messages 是模型输入，selection 是可选的选择明细。
    record ModelContext(List<Message> messages, Optional<Selection> selection) {
        // record 的紧凑构造器：把传入列表复制为不可修改列表，防止外部改变已返回的视图。
        public ModelContext { messages = List.copyOf(messages); }
    }

    /** Memory indexes refer to the frozen recall list for this run. */
    // 选择明细：turnIds 记录选中的历史轮次 ID，memoryIndexes 记录选中记忆在本轮 recall 列表中的索引。
    record Selection(List<UUID> turnIds, List<Integer> memoryIndexes) {
        // 构造选择明细时复制两个列表，保证诊断数据在返回后保持稳定。
        public Selection {
            // List.copyOf 创建不可修改的历史 ID 列表，调用者不能通过原列表修改它。
            turnIds = List.copyOf(turnIds);
            // 复制记忆索引列表；索引对应传入 prepare 的 memories，而不是另一份重新查询的列表。
            memoryIndexes = List.copyOf(memoryIndexes);
        }
    }
}
