package com.guodi.assistant.config;

import java.nio.file.Path;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import com.guodi.assistant.versioning.ExecutionManifest.Ref;

@ConfigurationProperties("assistant")
@Validated
public record AssistantProperties(
        @NotNull Path dataDirectory,
        @NotBlank String releaseId,
        @NotNull @Valid Prompt prompt,
        @NotNull @Valid Components components,
        @NotNull List<@NotNull @Valid Ref> tools,
        @NotNull @Valid Model model,
        @NotNull @Valid Limits limits,
        @NotNull @Valid Context context) {

    public enum Provider { DEMO, OPENAI }

    public record Prompt(@NotBlank String id, @NotBlank String version, @NotNull Resource resource,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256) {}

    public record Components(@NotNull @Valid Ref memory, @NotNull @Valid Ref context,
            @NotNull @Valid Ref store, @NotNull @Valid Ref toolExecutor) {}

    public record Model(@DefaultValue("demo") Provider provider, String apiKey, String baseUrl,
            String completionsPath, String name, @Min(1) int timeoutSeconds) {}

    public record Limits(@Min(1) int maxModelCalls, @Min(1) int maxContextChars,
            @Min(1) int maxToolsPerRound, @Min(1) int toolResultChars) {}

    public record Context(@Min(0) int recentTurns, @Min(0) int historyChars, @Min(0) int memoryChars) {}
}
