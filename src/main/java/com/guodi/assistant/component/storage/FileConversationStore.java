// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.storage;

// 导入 Path：文件系统路径类型。
import java.nio.file.Path;
// 导入 Comparator：排序规则构建工具。
import java.util.Comparator;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;

// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;
// 导入 ConversationStore：聊天存储接口。
import com.guodi.assistant.port.ConversationStore;

// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@com.guodi.assistant.versioning.ComponentRevision(id = "json-files", version = "1.0.0")
// ConversationStore 的文件实现；每个会话一个目录，每轮对话一个 JSON 文件。
public final class FileConversationStore implements ConversationStore {
    // 聊天存储根目录；实际会话目录通过 JsonFiles.conversationDirectory 推导。
    private final Path root;
    // 保存 JSON 工具，负责具体序列化、文件读取和原子替换。
    private final JsonFiles files;

    // 构造器接收根目录和 JSON 工具，具体值由配置装配代码提供。
    public FileConversationStore(Path root, JsonFiles files) {
        // 保存聊天根目录。
        this.root = root;
        // 保存 JSON 文件工具。
        this.files = files;
        
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 实现接口读取方法；Harness 恢复历史、Controller 查询历史时都会经过这里。
    public List<ConversationTurn> load(String conversationId) {
        // 计算该会话目录，把其中 JSON 文件反序列化为 ConversationTurn 列表。
        var turns = files.readDirectory(JsonFiles.conversationDirectory(root, conversationId), ConversationTurn.class);
        // 逐轮检查落盘数据的格式兼容性，防止把未知格式当成有效记录。
        for (var turn : turns) {
            // 只接受 schemaVersion 1 或 2，其他版本当前实现无法安全解释。
            if ((turn.schemaVersion() != 1 && turn.schemaVersion() != 2)|| (turn.schemaVersion() == 2 && turn.manifest() == null)) {
                // 发现不受支持或缺少必要快照的记录时立即失败，避免带着错误历史继续执行。
                throw new IllegalStateException("Unsupported transcript schema: " + turn.schemaVersion());
            }
        }
        // 按 startedAt 从早到晚排序，不能依赖文件系统返回目录项的顺序。
        return turns.stream().sorted(Comparator.comparing(ConversationTurn::startedAt)
                // 开始时间相同时再按轮次 UUID 排序，保证相同输入记录得到稳定顺序，最后收集为列表。
                .thenComparing(ConversationTurn::id)).toList();
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 实现保存接口；同一 turn 的新检查点覆盖相同文件，不另建一份轮次记录。
    public void save(String conversationId, ConversationTurn turn) {
        // 在会话目录下以 turn.id().json 为目标路径，交给 JsonFiles 原子替换整轮对象。
        files.write(JsonFiles.conversationDirectory(root, conversationId).resolve(turn.id() + ".json"), turn);
    }
}
