package com.guodi.assistant;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.guodi.assistant.core.AgentException;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.core.ConversationService.SessionMessage;
import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.core.event.RequestFailed;
import com.guodi.assistant.core.event.ToolsFinished;
import com.guodi.assistant.core.event.TurnStarted;
import com.guodi.assistant.core.event.TurnSteer;
import com.guodi.assistant.web.ChatController;
import com.guodi.assistant.web.ChatErrorHandler;

import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerSteerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void historyBelongsToHarnessWithoutSessionAdmission() {
        ConversationService conversations = mock(ConversationService.class);
        AssistantHarness harness = mock(AssistantHarness.class);
        var history = java.util.List.<com.guodi.assistant.domain.ConversationTurn>of();
        when(harness.history("chat")).thenReturn(history);
        var controller = new ChatController(conversations, harness, TestSupport.manifest());

        org.assertj.core.api.Assertions.assertThat(controller.history("chat")).isSameAs(history);

        verify(harness).history("chat");
        verifyNoInteractions(conversations);
    }

    @Test
    void promotedChatReturnsAReceiptInsteadOfAnExecutionError() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        var http = MockMvcBuilders.standaloneSetup(
                new ChatController(conversations, mock(AssistantHarness.class), TestSupport.manifest())).build();
        UUID id = UUID.randomUUID();

        http.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "conversationId", "chat", "messageId", id, "message", "supplement"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("STEERED"));

        verify(conversations).send("chat", new SessionMessage(id, "supplement"));
    }

    @Test
    void steerUsesOnlyTheConversationAndInputId() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        var http = MockMvcBuilders.standaloneSetup(
                new ChatController(conversations, mock(AssistantHarness.class), TestSupport.manifest())).build();
        UUID id = UUID.randomUUID();
        when(conversations.steer("chat", id)).thenReturn(true);

        http.perform(post("/api/steer").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "conversationId", "chat", "messageId", id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STEERED"));

        verify(conversations).steer("chat", id);
    }

    @Test
    void promotedStreamReturns202WithoutStartingATurn() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        var http = MockMvcBuilders.standaloneSetup(
                new ChatController(conversations, mock(AssistantHarness.class), TestSupport.manifest())).build();

        http.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", "chat",
                        "messageId", UUID.randomUUID(), "message", "supplement"))))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void streamCountsExistingToolFailuresAndReportsLaterRunFailureInTheBody() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        var http = MockMvcBuilders.standaloneSetup(
                new ChatController(conversations, mock(AssistantHarness.class), TestSupport.manifest())).build();
        UUID id = UUID.randomUUID();
        when(conversations.send(eq("chat"), eq(new SessionMessage(id, "go")), any())).thenAnswer(invocation -> {
            Consumer<AgentEvent> events = invocation.getArgument(2);
            UUID turnId = UUID.randomUUID();
            UUID steerId = UUID.randomUUID();
            events.accept(new TurnStarted(turnId));
            events.accept(new TurnSteer(turnId, steerId));
            events.accept(new ToolsFinished(List.of(
                    new ToolResponse("1", "current_time", "2026-11-12T06:32:00Z"),
                    new ToolResponse("2", "missing", "工具未启用或不存在：missing"),
                    new ToolResponse("3", "lookup", "工具执行失败：参数无效")), 18));
            var error = new AgentException("模型没有返回有效响应。");
            events.accept(new RequestFailed(error.getMessage()));
            throw error;
        });

        http.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", "chat", "messageId", id, "message", "go"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本批工具结束：成功 1，失败 2，耗时 18 ms")))
                .andExpect(content().string(containsString("lookup → 工具执行失败：参数无效")))
                .andExpect(content().string(containsString("[请求失败] 模型没有返回有效响应。")))
                .andExpect(content().string(not(containsString("补充"))));
    }

    @Test
    void streamKeepsTheHttpErrorStatusWhenTheTurnHasNotStarted() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        var http = MockMvcBuilders.standaloneSetup(
                new ChatController(conversations, mock(AssistantHarness.class), TestSupport.manifest()))
                .setControllerAdvice(new ChatErrorHandler()).build();
        UUID id = UUID.randomUUID();
        when(conversations.send(eq("chat"), eq(new SessionMessage(id, "go")), any())).thenAnswer(invocation -> {
            Consumer<AgentEvent> events = invocation.getArgument(2);
            var error = new AgentException("无法开始执行。");
            events.accept(new RequestFailed(error.getMessage()));
            throw error;
        });

        http.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", "chat", "messageId", id, "message", "go"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("无法开始执行。"));
    }
}
