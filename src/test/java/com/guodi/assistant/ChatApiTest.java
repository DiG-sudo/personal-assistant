package com.guodi.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import com.guodi.assistant.component.tool.ToolBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.stringContainsInOrder;

@SpringBootTest(properties = {"assistant.model.provider=demo", "assistant.shell-image=local-test-image"})
@AutoConfigureMockMvc
class ChatApiTest {
    @TempDir static Path data;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(data.resolve("workspace"));
        registry.add("assistant.data-directory", () -> data.resolve("data").toString());
    }

    @Test
    void reportsRegisteredToolsWithoutAWebChatPage() throws Exception {
        http.perform(get("/")).andExpect(status().isNotFound());
        http.perform(get("/index.html")).andExpect(status().isNotFound());
        http.perform(get("/api/components")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("demo"))
                .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.contains(
                        "edit_file", "execute", "glob_files", "grep_files", "list_files",
                        "read_file", "sample", "write_file")));
    }

    @Test
    void persistsAndRestoresConversationThroughHttp() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-history", "第一条请求")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").doesNotExist());
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-history", "第二条请求")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(containsString("第二条请求")));
        http.perform(get("/api/conversations/api-history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].messages[0].text").value("第一条请求"))
                .andExpect(jsonPath("$[1].messages.length()").value(2));
        org.assertj.core.api.Assertions.assertThat(data.resolve("data/memory")).doesNotExist();
    }

    @Test
    void runsAToolRoundThroughTheRealApplicationWiring() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-time", "测试工具")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modelCalls").value(2));
        http.perform(get("/api/conversations/api-time")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messages[1].toolCalls[0].name").value("sample"))
                .andExpect(jsonPath("$[0].messages[2].role").value("tool"));
    }

    @Test
    void streamsToolProgressAndTheCommittedAnswerInOrder() throws Exception {
        http.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(request("api-stream-time", "测试工具")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(stringContainsInOrder(
                        "开始处理", "请求模型（第 1 次）", "准备调用 1 个工具", "sample {}",
                        "本批工具结束：成功 1，失败 0，耗时", "sample →",
                        "请求模型（第 2 次）", "工具测试完成")));
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

    @Test
    void exposesSessionMetadataAndKeepsFullHistoryWhileRejectingAnotherProject() throws Exception {
        String id = "api-resume";
        String project = data.resolve("workspace").toRealPath().toString();
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request(id, "恢复会话测试"))).andExpect(status().isOk());
        http.perform(get("/api/conversations/" + id + "/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.projectPath").value(project))
                .andExpect(jsonPath("$.title").value("恢复会话测试"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        http.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.conversationId == 'api-resume')].projectPath")
                        .value(org.hamcrest.Matchers.contains(project)));

        Path other = Files.createDirectories(data.resolve("other-workspace"));
        for (String endpoint : new String[] {"/api/chat", "/api/chat/stream"}) {
            http.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("conversationId", id, "messageId", UUID.randomUUID(),
                            "message", "不应执行", "projectPath", other.toString()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(containsString("会话已绑定项目目录")));
        }
        http.perform(get("/api/conversations/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(request(id, "继续原会话"))).andExpect(status().isOk());
        http.perform(get("/api/conversations/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].messages[0].text").value("恢复会话测试"))
                .andExpect(jsonPath("$[1].messages[0].text").value("继续原会话"));
        http.perform(get("/api/conversations/nonexistent/session")).andExpect(status().isNotFound());
        http.perform(get("/api/conversations/bad.id/session")).andExpect(status().isBadRequest());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ToolFixtures {
        @Bean
        ToolBinding sampleTool() {
            return new ToolBinding(ToolBinding.Kind.READ, ToolCallbacks.from(new ToolExecutorTest.SampleTool())[0]);
        }

        @Bean
        ChatModel testModel() {
            return new com.guodi.assistant.component.model.DemoChatModel() {
                @Override
                public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                    var last = prompt.getInstructions().getLast();
                    if (last instanceof ToolResponseMessage) {
                        return TestSupport.text("工具测试完成");
                    }
                    if ("测试工具".equals(last.getText())) {
                        return TestSupport.tool("fixture-call", "sample");
                    }
                    return super.call(prompt);
                }
            };
        }
    }

    private String request(String conversationId, String message) throws Exception {
        return json.writeValueAsString(Map.of("conversationId", conversationId,
                "messageId", UUID.randomUUID(), "message", message,
                "projectPath", data.resolve("workspace").toString()));
    }
}
