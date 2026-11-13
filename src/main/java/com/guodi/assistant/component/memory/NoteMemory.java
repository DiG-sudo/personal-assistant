// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.memory;

// 导入 Path：文件系统路径类型。
import java.nio.file.Path;
// 导入 Instant：UTC 时间点。
import java.time.Instant;
// 导入 Comparator：排序规则构建工具。
import java.util.Comparator;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 UUID：轮次或调用的唯一标识类型。
import java.util.UUID;

// 导入 JsonFiles：项目内共享的 JSON 文件读写工具。
import com.guodi.assistant.component.storage.JsonFiles;
// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;
// 导入 MemoryModule：长期记忆接口。
import com.guodi.assistant.port.MemoryModule;

/** Deliberately simple: persist only explicit "记住：" notes, scoped to the conversation. */
// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@com.guodi.assistant.versioning.ComponentRevision(id = "notes", version = "1.0.0")
// 按会话隔离的备注记忆实现；只保存用户明确要求记住的文本。
public final class NoteMemory implements MemoryModule {
    // 记忆存储根目录；每个 conversationId 会映射到自己的子目录。
    private final Path root;
    // 复用 JSON 文件工具完成读取和原子写入，不在这里重复实现文件操作。
    private final JsonFiles files;

    // 构造器接收记忆目录和 JSON 文件工具。
    public NoteMemory(Path root, JsonFiles files) {
        // 保存记忆根目录。
        this.root = root;
        // 保存文件读写工具实例。
        this.files = files;
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 每轮开始时读取该会话的备注；当前实现不使用 query 做相关性检索。
    public List<String> recall(String conversationId, String query) {
        // ponytail: latest 20 explicit notes; replace this component when semantic retrieval is needed.
        // 把会话 ID 转成目录名，读取目录中的 Note 对象，再把列表转换成流。
        return files.readDirectory(JsonFiles.conversationDirectory(root, conversationId), Note.class).stream()
                // 按 createdAt 创建时间倒序排列；Note::createdAt 是调用 record 访问器的方法引用。
                .sorted(Comparator.comparing(Note::createdAt).reversed())
                // 最多保留最新 20 条，再把 Note 映射成正文字符串，收集为列表。
                .limit(20).map(Note::text).toList();
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // Harness 在成功提交一轮后调用；当前实现只检查本轮最开始的用户消息。
    public void observe(String conversationId, ConversationTurn turn) {
        // 取本轮第一条消息的正文，并用 strip 去掉两端空白。
        String message = turn.messages().getFirst().text().strip();
        // 必须以中文或英文冒号形式的 '记住：' 开头；普通聊天不会自动变成长期备注。
        if (!message.startsWith("记住：") && !message.startsWith("记住:")) {
            // 没有明确记忆指令时结束方法，不写入任何文件。
            return;
        }
        // substring(3) 去掉 '记住' 和冒号三个字符，再清理备注两端空白。
        String note = message.substring(3).strip();
        // 只有前缀后确实存在备注正文时才保存。
        if (!note.isEmpty()) {
            // 以来源 turn ID 为文件名保存备注；同一轮再次观察会覆盖同一文件，避免重复新增。
            files.write(JsonFiles.conversationDirectory(root, conversationId).resolve(turn.id() + ".json"),
                    // 记录来源轮次 ID、轮次开始时间和备注正文，并交给 JSON 工具写入。
                    new Note(turn.id(), turn.startedAt(), note));
        }
    }

    // 备注的数据结构；record 自动提供 sourceTurnId()、createdAt()、text() 等只读访问器。
    public record Note(UUID sourceTurnId, Instant createdAt, String text) {}
}
