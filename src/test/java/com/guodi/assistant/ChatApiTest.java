package com.guodi.assistant;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.stringContainsInOrder;

@SpringBootTest(properties = "assistant.model.provider=demo")
@AutoConfigureMockMvc
class ChatApiTest {
    @TempDir static Path data;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("assistant.data-directory", () -> data.toString());
    }

    @Test
    void servesTheChatPageAndReportsTheActualEnabledTools() throws Exception {
        http.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("个人助手"));
        http.perform(get("/api/components")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("demo"))
                .andExpect(jsonPath("$.tools.length()").value(2));
    }

    @Test
    void savesNotesRecallsThemAndRestoresTheTranscriptThroughHttp() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-notes", "记住：我喜欢中文回答")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").isEmpty());
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-notes", "你记得什么")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(containsString("我喜欢中文回答")));
        http.perform(get("/api/conversations/api-notes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].messages.length()").value(2));
    }

    @Test
    void runsAToolRoundThroughTheRealApplicationWiring() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-time", "现在几点")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modelCalls").value(2));
        http.perform(get("/api/conversations/api-time")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messages[1].toolCalls[0].name").value("current_time"))
                .andExpect(jsonPath("$[0].messages[2].role").value("tool"));
    }

    @Test
    void streamsToolProgressAndTheCommittedAnswerInOrder() throws Exception {
        http.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-stream-time", "现在几点")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(stringContainsInOrder(
                        "开始处理", "请求模型（第 1 次）", "准备调用 1 个工具", "current_time {}",
                        "本批工具结束：成功 1，失败 0，耗时", "current_time →",
                        "请求模型（第 2 次）", "演示模式 · 工具返回：")));
        http.perform(get("/api/conversations/api-stream-time"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].messages[2].role").value("tool"));
    }

    @Test
    void rejectsInvalidRequestsAtTheHttpBoundary() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("bad.id", "hello")))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("ok", "  ")))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/conversations/bad.id")).andExpect(status().isBadRequest());
    }

    @Test
    void requiresAnInputIdAndRejectsSteerForAnUnknownWaitingMessage() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"missing-id\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/steer").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", "unknown",
                        "messageId", UUID.randomUUID()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    private String request(String conversationId, String message) throws Exception {
        return json.writeValueAsString(Map.of("conversationId", conversationId,
                "messageId", UUID.randomUUID(), "message", message));
    }
}
