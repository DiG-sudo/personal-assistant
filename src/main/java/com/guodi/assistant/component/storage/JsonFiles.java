// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.storage;

// 导入 IOException：文件读写可能抛出的受检异常。
import java.io.IOException;
// 导入 UncheckedIOException：携带原始 I/O 原因的运行时异常。
import java.io.UncheckedIOException;
// 导入 StandardCharsets：JDK 提供的标准字符集常量。
import java.nio.charset.StandardCharsets;
// 导入 Files：JDK 文件读取、写入、目录及移动操作。
import java.nio.file.Files;
// 导入 Path：文件系统路径类型。
import java.nio.file.Path;
// 导入 StandardCopyOption：文件移动时的原子性和替换选项。
import java.nio.file.StandardCopyOption;
// 导入 Base64：把会话 ID 编码成目录名的编码器。
import java.util.Base64;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;

// 导入 ObjectMapper：Jackson 对象与 JSON 编解码器。
import com.fasterxml.jackson.databind.ObjectMapper;

/** Local single-process JSON storage. A checkpoint replaces a complete file via atomic rename. */
// 通用 JSON 文件读写工具；不理解对话业务规则，只负责对象与文件的转换。
public final class JsonFiles {
    // Jackson 的 JSON 编解码器；Spring 配置好模块后通过构造器传入。
    private final ObjectMapper json;

    // 接收 JSON 编解码器，供所有后续读写方法复用。
    public JsonFiles(ObjectMapper json) {
        // 保存传入的 ObjectMapper 实例。
        this.json = json;
    }

    // 把任意会话 ID 映射成文件系统可使用的子目录名；static 表示无需创建 JsonFiles 对象即可调用。
    public static Path conversationDirectory(Path root, String conversationId) {
        // 使用 URL 安全的 Base64 编码器；withoutPadding 去掉结尾等号，得到目录名。
        String name = Base64.getUrlEncoder().withoutPadding()
                // 先按 UTF-8 把会话 ID 转成字节，再编码成目录名；这是可逆编码，不是加密或哈希。
                .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8));
        // 在调用方提供的根目录下定位这个会话子目录，返回路径，此处不创建目录。
        return root.resolve(name);
    }

    // <T> 是泛型类型；传入 Class<T>，使同一个读目录方法可以读取不同的持久化记录。
    public <T> List<T> readDirectory(Path directory, Class<T> type) {
        // 第一次访问时可能还没有目录，先检查是否存在。
        if (!Files.exists(directory)) {
            // 目录不存在时返回空列表，表示没有已保存记录，不为读取创建目录。
            return List.of();
        }
        // Files.list 返回需要关闭的目录流；try-with-resources 会在正常返回或异常时自动关闭。
        try (var files = Files.list(directory)) {
            // 只接受文件名以 .json 结尾的目录项；写入中的 .tmp 文件不会成为聊天记录。
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    // 逐个调用下面的 read 反序列化成 T，再收集成列表；排序由上层存储组件决定。
                    .map(path -> read(path, type)).toList();
        // 清理失败由本段捕获；主流程中的原始读写错误仍然更重要。
        } catch (IOException error) {
            // 保留原始原因并转换成非受检 I/O 异常，向 Harness/HTTP 错误处理传播目录读取失败。
            throw new UncheckedIOException("Cannot read data directory: " + directory, error);
        }
    }

    // 读取一个 JSON 文件；不存在或无法解码时保留原始 I/O 错误。
    public <T> T read(Path path, Class<T> type) {
        // 集中处理创建目录、写临时文件和替换目标文件时的 I/O 异常。
        try {
            // 先读取文件全部字节，再让 Jackson 按 type 反序列化；格式错误也会作为读取失败传播。
            return json.readValue(Files.readAllBytes(path), type);
        // 清理失败由本段捕获；主流程中的原始读写错误仍然更重要。
        } catch (IOException error) {
            // 给文件读取或 JSON 解码失败补充具体路径，并保留原始异常。
            throw new UncheckedIOException("Cannot read data file: " + path, error);
        }
    }

    // 把对象写成一个完整 JSON 检查点；成功前不会推进 Harness 中的已提交游标。
    public void write(Path target, Object value) {
        // 暂存本次写入分配的临时路径；初始为 null，表示临时文件还没有创建。
        Path temporary = null;
        // 集中处理创建目录、写临时文件和替换目标文件时的 I/O 异常。
        try {
            // 确保目标文件的父目录存在；已存在时不会清空目录。
            Files.createDirectories(target.getParent());
            // 在目标的同一目录创建独有 .tmp 文件，让后续重命名可在同一文件系统内完成。
            temporary = Files.createTempFile(target.getParent(), ".checkpoint-", ".tmp");
            // Jackson 把对象编码为 JSON 字节，然后完整写入临时文件，此时旧目标文件仍保留。
            Files.write(temporary, json.writeValueAsBytes(value));
            // 用原子重命名替换目标文件；不支持原子移动时抛错，不降级为可能出现半文件的覆盖写。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // 清理失败由本段捕获；主流程中的原始读写错误仍然更重要。
        } catch (IOException error) {
            // 保存失败时向上传播，调用方不会把这次检查点视为成功；这里不承诺 fsync 或断电级持久化。
            throw new UncheckedIOException("Cannot commit data file: " + target, error);
        // 无论主流程成功还是失败，都尝试清理临时文件。
        } finally {
            // 只有确实分配过临时路径才需要清理；创建之前失败时不访问空路径。
            if (temporary != null) {
                // 集中处理创建目录、写临时文件和替换目标文件时的 I/O 异常。
                try {
                    // 成功移动后临时文件已不存在；失败时则删除残留文件，因此使用允许目标不存在的删除方式。
                    Files.deleteIfExists(temporary);
                // 清理失败由本段捕获；主流程中的原始读写错误仍然更重要。
                } catch (IOException error) {
                    // Do not mask the original commit failure; .tmp files are never loaded as records.
                }
            }
        }
    }
}
