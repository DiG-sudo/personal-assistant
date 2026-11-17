package com.guodi.assistant.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.guodi.assistant.component.model.DemoChatModel;
import com.guodi.assistant.component.tool.ToolRegistry;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.AssistantHarness.Reply;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.core.ConversationService.SessionMessage;
import com.guodi.assistant.core.event.AgentEvent;
import com.guodi.assistant.core.event.ModelStarted;
import com.guodi.assistant.core.event.RequestFailed;
import com.guodi.assistant.core.event.ToolsFinished;
import com.guodi.assistant.core.event.ToolsStarted;
import com.guodi.assistant.core.event.TurnCompleted;
import com.guodi.assistant.core.event.TurnStarted;
import com.guodi.assistant.core.event.TurnSteer;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.domain.ConversationInfo;

@RestController
@Validated
public class ChatController {
    private final ConversationService conversations;
    private final AssistantHarness harness;
    private final ChatModel model;
    private final ToolRegistry tools;

    public ChatController(ConversationService conversations, AssistantHarness harness, ChatModel model,
            ToolRegistry tools) {
        this.conversations = conversations;
        this.harness = harness;
        this.model = model;
        this.tools = tools;
    }

    @PostMapping("/api/control/open")
    public com.guodi.assistant.config.AssistantProperties.Control openControl(
            @Valid @RequestBody ControlRequest request) {
        return conversations.openControl(request.conversationId());
    }

    @PostMapping("/api/control/heartbeat")
    public ResponseEntity<?> heartbeat(@Valid @RequestBody ControlRequest request) {
        if (!conversations.heartbeat(request.conversationId())) {
            return ResponseEntity.status(409).body(Map.of("error", "Window control expired; reopen the session."));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/control/cancel")
    public Map<String, Boolean> cancel(@Valid @RequestBody ControlRequest request) {
        return Map.of("accepted", conversations.cancel(request.conversationId()));
    }

    @PostMapping("/api/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody Request request) {
        Reply reply = conversations.send(request.conversationId(), sessionMessage(request));
        if (reply == null) {
            return ResponseEntity.accepted().body(new SteerReceipt(request.messageId(), "STEERED"));
        }
        return ResponseEntity.ok(reply);
    }

    @PostMapping("/api/chat/stream")
    public void chatStream(@Valid @RequestBody Request request, HttpServletResponse response) throws IOException {
        SessionMessage message = sessionMessage(request);
        response.setContentType("text/plain;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        try {
            Reply reply = conversations.send(request.conversationId(),
                    message,
                    event -> {
                        if (!(event instanceof RequestFailed) || response.isCommitted()) {
                            writeProgress(writer, event);
                        }
                    });
            if (reply == null) {
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
            }
        } catch (RuntimeException error) {
            if (!response.isCommitted()) {
                response.reset();
                throw error;
            }
        }
    }

    private static SessionMessage sessionMessage(Request request) {
        try {
            Path path = Path.of(request.projectPath());
            if (!path.isAbsolute()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectPath must be an absolute directory");
            }
            Path root = path.toRealPath();
            if (!Files.isDirectory(root) || root.getParent() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectPath must be a project directory");
            }
            return new SessionMessage(request.messageId(), request.message(), root);
        } catch (IOException | InvalidPathException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectPath must be an existing directory", error);
        }
    }

    private static void writeProgress(PrintWriter writer, AgentEvent event) {
        switch (event) {
            case TurnStarted started -> writer.println("开始处理 " + started.turnId());
            case ModelStarted started -> writer.println("请求模型（第 %d 次）".formatted(started.modelCalls()));
            case ToolsStarted started -> {
                writer.println("准备调用 %d 个工具".formatted(started.calls().size()));
                for (var call : started.calls()) {
                    writer.println("  %s %s".formatted(terminalLine(call.name()), terminalLine(call.arguments())));
                }
            }
            case ToolsFinished finished -> {
                long failed = finished.outcomes().stream().filter(outcome -> !outcome.success()).count();
                writer.println("本批工具结束：成功 %d，失败 %d，耗时 %d ms"
                        .formatted(finished.outcomes().size() - failed, failed, finished.elapsedMs()));
                for (var result : finished.outcomes()) {
                    String preview = terminalLine(result.responseData());
                    if (preview.length() > 240) {
                        preview = preview.substring(0, 240) + "…";
                    }
                    writer.println("  %s → %s".formatted(terminalLine(result.call().name()), preview));
                }
            }
            case TurnCompleted completed -> {
                writer.println();
                writer.println(completed.reply().answer().replaceAll("[\\p{Cc}&&[^\\n\\t]]", ""));
            }
            case RequestFailed failed -> writer.println("[请求失败] " + terminalLine(failed.reason()));
            case TurnSteer ignored -> { return; }
            default -> { return; }
        }
        writer.flush();
    }

    private static String terminalLine(String text) {
        return text.replaceAll("\\p{Cc}", " ");
    }

    @PostMapping("/api/steer")
    public ResponseEntity<?> steer(@Valid @RequestBody SteerRequest request) {
        if (!conversations.steer(request.conversationId(), request.messageId())) {
            return ResponseEntity.status(409).body(Map.of("error", "消息不在等待列表或当前没有执行中的任务。"));
        }
        return ResponseEntity.ok(new SteerReceipt(request.messageId(), "STEERED"));
    }

    @GetMapping("/api/conversations")
    public List<ConversationInfo> sessions() {
        return conversations.list();
    }

    @GetMapping("/api/conversations/{conversationId}/session")
    public ConversationInfo session(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId) {
        return conversations.info(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在。"));
    }

    @GetMapping("/api/conversations/{conversationId}")
    public List<ConversationTurn> history(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId) {
        return harness.history(conversationId);
    }

    @GetMapping("/api/components")
    public Map<String, Object> components() {
        return Map.of("mode", model instanceof DemoChatModel ? "demo" : "live",
                "tools", tools.callbacks().stream().map(callback -> callback.getToolDefinition().name()).toList());
    }

    public record ControlRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId) {}

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId,
            @NotNull UUID messageId,
            @NotBlank @Size(max = 16000) String message,
            @NotBlank String projectPath) {}

    public record SteerRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId,
            @NotNull UUID messageId) {}

    public record SteerReceipt(UUID messageId, String status) {}
}
