package com.guodi.assistant.component.tool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Example capability, intentionally no remote HTTP access or claim of verified search results. */
public final class VideoSearchLinksTool {
    @Tool(name = "video_search_links", description = "生成 Bilibili 和 YouTube 的视频搜索入口链接。"
            + "不会抓取视频、验证资源或返回实际搜索结果，回答时应说明这一点。")
    public Map<String, String> search(@ToolParam(description = "视频搜索关键词") String query) {
        if (query == null || query.isBlank() || query.length() > 500) {
            throw new IllegalArgumentException("搜索关键词需要为 1 到 500 个字符。");
        }
        String encoded = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        return Map.of("bilibili", "https://search.bilibili.com/all?keyword=" + encoded,
                "youtube", "https://www.youtube.com/results?search_query=" + encoded);
    }
}
