package com.guodi.assistant.versioning;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/** Immutable, secret-free snapshot of the configuration actually assembled for a run. */
public record ExecutionManifest(String releaseId, String fingerprint, PromptSnapshot prompt,
        Map<String, ComponentSnapshot> components, List<ToolSnapshot> tools, Map<String, Object> settings) {

    public ExecutionManifest {
        components = Map.copyOf(components);
        tools = List.copyOf(tools);
        settings = Map.copyOf(settings);
    }

    public record Ref(@NotBlank String id, @NotBlank String version) {
        @Override public String toString() { return id + "@" + version; }
    }

    public record PromptSnapshot(Ref revision, String resource, String sha256, String text) {}
    public record ComponentSnapshot(Ref revision, String implementation, String implementationSha256,
            Map<String, Object> configuration) {
        public ComponentSnapshot { configuration = Map.copyOf(configuration); }
    }
    public record ToolSnapshot(Ref revision, String implementation, String implementationSha256,
            String description, String inputSchema, Map<String, Object> configuration) {
        public ToolSnapshot { configuration = Map.copyOf(configuration); }
    }
}
