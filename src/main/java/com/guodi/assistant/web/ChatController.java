package com.guodi.assistant.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.guodi.assistant.component.model.DemoChatModel;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.ToolExecutor;
import com.guodi.assistant.versioning.ExecutionManifest;

@RestController
@Validated
public class ChatController {
    private final AssistantHarness harness;
    private final ChatModel model;
    private final ToolExecutor tools;
    private final ExecutionManifest manifest;

    public ChatController(AssistantHarness harness, ChatModel model, ToolExecutor tools, ExecutionManifest manifest) {
        this.harness = harness;
        this.model = model;
        this.tools = tools;
        this.manifest = manifest;
    }

    @PostMapping("/api/chat")
    public AssistantHarness.Reply chat(@Valid @RequestBody Request request) {
        return harness.chat(request.conversationId(), request.message());
    }

    @GetMapping("/api/conversations/{conversationId}")
    public List<ConversationTurn> history(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId) {
        return harness.history(conversationId);
    }

    @GetMapping("/api/components")
    public Map<String, Object> components() {
        return Map.of("mode", model instanceof DemoChatModel ? "demo" : "live",
                "manifest", manifest, "tools", tools.callbacks().stream().map(tool -> tool.getToolDefinition().name()).toList());
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String conversationId,
            @NotBlank @Size(max = 16000) String message) {}
}
