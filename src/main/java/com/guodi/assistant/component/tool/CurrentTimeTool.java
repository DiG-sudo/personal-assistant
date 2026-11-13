// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.tool;

// 导入 Instant：UTC 时间点。
import java.time.Instant;
// 导入 Tool：把 Java 方法声明为模型可调用工具的注解。
import org.springframework.ai.tool.annotation.Tool;

// 提供一个可由 Spring AI 包装成 ToolCallback 的时间工具；本身不是工具执行器。
public final class CurrentTimeTool {
    // @Tool 声明模型可见的工具名称和描述；装配时通过版本包装及发布清单决定是否真正暴露。
    @Tool(name = "current_time", description = "读取当前 UTC 时间，返回 ISO-8601 格式。")
    // 模型请求 current_time 时执行该方法；没有输入参数。
    public String currentTime() {
        // 读取当前 UTC 时间，并转换成 ISO-8601 字符串返回给模型。
        return Instant.now().toString();
    }
}
