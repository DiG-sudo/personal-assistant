package com.guodi.assistant.config;

import java.nio.file.Path;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("assistant")
@Validated
public record AssistantProperties(
        @NotNull Path dataDirectory,
        @NotNull Resource systemPrompt,
        String shellImage,
        @NotNull @Valid Model model,
        @NotNull @Valid Limits limits) {

    public enum Provider { DEMO, OPENAI }

    public record Model(@DefaultValue("demo") Provider provider, String apiKey, String baseUrl,
            String completionsPath, String name, String reasoningEffort, @Min(1) int timeoutSeconds) {}

    public record Limits(@Min(1) int maxModelCalls, @Min(1) int maxContextChars,
            @Min(1) int maxToolsPerRound) {}

}
