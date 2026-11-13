// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.tool;

// 导入 URLEncoder：把关键词编码为 URL 查询参数的工具。
import java.net.URLEncoder;
// 导入 StandardCharsets：JDK 提供的标准字符集常量。
import java.nio.charset.StandardCharsets;
// 导入 Map：按键查找值的映射容器。
import java.util.Map;

// 导入 Tool：把 Java 方法声明为模型可调用工具的注解。
import org.springframework.ai.tool.annotation.Tool;
// 导入 ToolParam：为工具入参提供模型可见说明的注解。
import org.springframework.ai.tool.annotation.ToolParam;

/** Example capability, intentionally no remote HTTP access or claim of verified search results. */
// 示例业务工具：根据关键词生成搜索入口 URL，不发网络请求，也不返回搜索结果。
public final class VideoSearchLinksTool {
    // 声明工具名称和模型可见描述，明确只生成搜索入口的能力边界。
    @Tool(name = "video_search_links", description = "生成 Bilibili 和 YouTube 的视频搜索入口链接。"
            // 把限制说明拼接进工具描述，避免模型把生成链接误当成已经检索。
            + "不会抓取视频、验证资源或返回实际搜索结果，回答时应说明这一点。")
    // ToolParam 的说明用于参数 Schema；query 由模型传入，返回值是平台名到 URL 的映射。
    public Map<String, String> search(@ToolParam(description = "视频搜索关键词") String query) {
        // 在工具输入边界检查非空、非纯空白以及 500 字符上限，防止无效或过长参数。
        if (query == null || query.isBlank() || query.length() > 500) {
            // 拒绝非法参数；工具执行器会把普通参数错误转换为模型可读的失败结果。
            throw new IllegalArgumentException("搜索关键词需要为 1 到 500 个字符。");
        }
        // 先 strip 去除两端空白，再按 UTF-8 做 URL 表单编码，避免空格、中文和特殊符号破坏查询参数。
        String encoded = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        // 创建不可修改的返回映射，先放入 Bilibili 的搜索入口。
        return Map.of("bilibili", "https://search.bilibili.com/all?keyword=" + encoded,
                // 再放入 YouTube 的搜索入口；两个 URL 使用相同的编码后关键词。
                "youtube", "https://www.youtube.com/results?search_query=" + encoded);
    }
}
