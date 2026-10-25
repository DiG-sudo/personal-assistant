package com.guodi.assistant.component.tool;

import java.time.Instant;
import org.springframework.ai.tool.annotation.Tool;

public final class CurrentTimeTool {
    @Tool(name = "current_time", description = "读取当前 UTC 时间，返回 ISO-8601 格式。")
    public String currentTime() {
        return Instant.now().toString();
    }
}
