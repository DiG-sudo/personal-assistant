package com.guodi.assistant.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.versioning.ExecutionManifest;

@RestController
@Validated
public class ChatController {
    private final AgentRuntime runtime;
    private final AssistantHarness harness;
    private final ExecutionManifest manifest;

    public ChatController(AgentRuntime runtime, AssistantHarness harness, ExecutionManifest manifest) {
        this.runtime = runtime;
        this.harness = harness;
        this.manifest = manifest;
    }

    @PostMapping("/api/chat")
    public AssistantHarness.Reply chat(@Valid @RequestBody Request request) {
        return runtime.chat(request.conversationId(), request.message());
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
            @NotBlank @Size(max = 16000) String message) {}
}
