package com.guodi.assistant;

import java.nio.file.Path;

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

@SpringBootTest(properties = "assistant.model.provider=demo")
@AutoConfigureMockMvc
class ChatApiTest {
    @TempDir static Path data;
    @Autowired MockMvc http;

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
                .content("{\"conversationId\":\"api-notes\",\"message\":\"记住：我喜欢中文回答\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").isEmpty());
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"api-notes\",\"message\":\"你记得什么\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(containsString("我喜欢中文回答")));
        http.perform(get("/api/conversations/api-notes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].messages.length()").value(2));
    }

    @Test
    void runsAToolRoundThroughTheRealApplicationWiring() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"api-time\",\"message\":\"现在几点\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modelCalls").value(2));
        http.perform(get("/api/conversations/api-time")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messages[1].toolCalls[0].name").value("current_time"))
                .andExpect(jsonPath("$[0].messages[2].role").value("tool"));
    }

    @Test
    void rejectsInvalidRequestsAtTheHttpBoundary() throws Exception {
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"bad.id\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"ok\",\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/conversations/bad.id")).andExpect(status().isBadRequest());
    }
}
