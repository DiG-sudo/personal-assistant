// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.storage;

// 导入 Path：文件系统路径类型。
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
// 导入 Comparator：排序规则构建工具。
import java.util.Comparator;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;

// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;

/** Stores each turn as a JSON file in its conversation directory. */
public final class FileConversationStore {
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

    // Harness 恢复历史、Controller 查询历史时都会经过这里。
    public List<ConversationTurn> load(String conversationId) {
        // 计算该会话目录，把其中 JSON 文件反序列化为 ConversationTurn 列表。
        var turns = files.readDirectory(JsonFiles.conversationDirectory(root, conversationId), ConversationTurn.class);
        // 逐轮检查落盘数据的格式兼容性，防止把未知格式当成有效记录。
        for (var turn : turns) {
            if (turn.schemaVersion() < 1 || turn.schemaVersion() > ConversationTurn.CURRENT_SCHEMA) {
                // 发现不受支持或缺少必要快照的记录时立即失败，避免带着错误历史继续执行。
                throw new IllegalStateException("Unsupported transcript schema: " + turn.schemaVersion());
            }
        }
        // 按 startedAt 从早到晚排序，不能依赖文件系统返回目录项的顺序。
        return turns.stream().sorted(Comparator.comparing(ConversationTurn::startedAt)
                // 开始时间相同时再按轮次 UUID 排序，保证相同输入记录得到稳定顺序，最后收集为列表。
                .thenComparing(ConversationTurn::id)).toList();
    }

    /** Include conversations created before separate session metadata existed. */
    public List<String> conversationIds() {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var directories = Files.list(root)) {
            List<String> ids = new ArrayList<>();
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String id;
                try {
                    id = new String(Base64.getUrlDecoder().decode(directory.getFileName().toString()),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                        && JsonFiles.conversationDirectory(root, id).equals(directory)) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot list conversation directories", error);
        }
    }

    // 同一 turn 的新检查点覆盖相同文件，不另建一份轮次记录。
    public void save(String conversationId, ConversationTurn turn) {
        // 在会话目录下以 turn.id().json 为目标路径，交给 JsonFiles 原子替换整轮对象。
        files.write(JsonFiles.conversationDirectory(root, conversationId).resolve(turn.id() + ".json"), turn);
    }
}
