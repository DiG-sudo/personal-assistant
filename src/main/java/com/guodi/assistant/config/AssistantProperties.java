package com.guodi.assistant.config;

import java.nio.file.Path;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
        @NotNull @Valid Budget budget,
        @DefaultValue @NotNull @Valid Control control) {

    public enum Provider { DEMO, OPENAI }
    public enum OutputTokenParameter { MAX_TOKENS, MAX_COMPLETION_TOKENS }

    public record Model(@DefaultValue("demo") Provider provider, String apiKey, String baseUrl,
            String completionsPath, String name, String reasoningEffort, @Min(1) int timeoutSeconds,
            @DefaultValue("131072") @Min(1) int contextWindowTokens,
            @DefaultValue("32768") @Min(1) int maxOutputTokens,
            @DefaultValue("max-tokens") @NotNull OutputTokenParameter outputTokenParameter) {}

    public record Control(
            @DefaultValue("5") @Min(1) int heartbeatIntervalSeconds,
            @DefaultValue("3") @Min(1) int heartbeatRequestTimeoutSeconds,
            @DefaultValue("30") @Min(1) int heartbeatTimeoutSeconds) {
        public Control {
            if (heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
                throw new IllegalArgumentException("Heartbeat timeout must exceed the sending interval");
            }
        }
    }

    public record Budget(
            @Min(1) int windowTokens,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double compactRatio,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double keepRecentRatio,
            @Min(1) int maxOutputTokens,
            @Min(1) int summaryOutputTokens,
            @Min(1) int toolResultThresholdTokens,
            @Min(1) int toolResultPreviewTokens,
            @Min(1) int maxModelCalls,
            @Min(1) int maxToolsPerRound) {

        public Budget {
            if (maxOutputTokens >= windowTokens || summaryOutputTokens >= windowTokens) {
                throw new IllegalArgumentException("Output reserves must be smaller than the working window");
            }
            if (keepRecentRatio >= compactRatio) {
                throw new IllegalArgumentException("Recent retention ratio must be smaller than the compaction ratio");
            }
            if (toolResultPreviewTokens >= toolResultThresholdTokens) {
                throw new IllegalArgumentException("Tool result preview must be smaller than the inline threshold");
            }
        }

        public Budget forModel(Model model) {
            return new Budget(Math.min(windowTokens, model.contextWindowTokens()), compactRatio, keepRecentRatio,
                    Math.min(maxOutputTokens, model.maxOutputTokens()),
                    Math.min(summaryOutputTokens, model.maxOutputTokens()),
                    toolResultThresholdTokens, toolResultPreviewTokens, maxModelCalls, maxToolsPerRound);
        }

        public int inputTokens() { return windowTokens - maxOutputTokens; }
        public int compactAtTokens() { return (int) (inputTokens() * compactRatio); }
        public int keepRecentTokens() { return (int) (inputTokens() * keepRecentRatio); }
    }
}
