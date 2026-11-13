package com.guodi.assistant.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
import com.guodi.assistant.versioning.ExecutionManifest;

@RestController
@Validated
public class ChatController {
    private final ConversationService conversations;
    private final AssistantHarness harness;
    private final ExecutionManifest manifest;

    public ChatController(ConversationService conversations, AssistantHarness harness, ExecutionManifest manifest) {
        this.conversations = conversations;
        this.harness = harness;
        this.manifest = manifest;
    }

    @PostMapping("/api/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody Request request) {
        Reply reply = conversations.send(request.conversationId(),new SessionMessage(request.messageId(), request.message()));
        if (reply == null) {
            return ResponseEntity.accepted().body(new SteerReceipt(request.messageId(), "STEERED"));
        }
        return ResponseEntity.ok(reply);
    }

    @PostMapping("/api/chat/stream")
    public void chatStream(@Valid @RequestBody Request request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        try {
            Reply reply = conversations.send(request.conversationId(),
                    new SessionMessage(request.messageId(), request.message()),
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
                // ponytail: 默认执行器通过结果前缀报告失败。有独立状态时改用状态计数。
                long failed = finished.results().stream().filter(result -> {
                    String data = result.responseData();
                    return data.startsWith("工具未启用或不存在：") || data.startsWith("工具执行失败：");
                }).count();
                writer.println("本批工具结束：成功 %d，失败 %d，耗时 %d ms"
                        .formatted(finished.results().size() - failed, failed, finished.elapsedMs()));
                for (var result : finished.results()) {
                    String preview = terminalLine(result.responseData());
                    if (preview.length() > 240) {
                        preview = preview.substring(0, 240) + "…";
                    }
                    writer.println("  %s → %s".formatted(terminalLine(result.name()), preview));
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

    @GetMapping("/api/conversations/{conversationId}")
    public List<ConversationTurn> history(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId) {
        return harness.history(conversationId);
    }

    @GetMapping("/api/components")
    public Map<String, Object> components() {
        return Map.of("mode", manifest.components().get("model").revision().id().equals("demo") ? "demo" : "live",
                "manifest", manifest, "tools", manifest.tools().stream().map(tool -> tool.revision().id()).toList());
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId,
            @NotNull UUID messageId,
            @NotBlank @Size(max = 16000) String message) {}

    public record SteerRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId,
            @NotNull UUID messageId) {}

    public record SteerReceipt(UUID messageId, String status) {}
}
