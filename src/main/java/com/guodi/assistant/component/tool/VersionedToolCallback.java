package com.guodi.assistant.component.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.guodi.assistant.versioning.ExecutionManifest.Ref;
import com.guodi.assistant.versioning.ExecutionManifest.ToolSnapshot;
import com.guodi.assistant.versioning.Fingerprints;
import com.guodi.assistant.versioning.ConfigurationSnapshot;

/** Binds a tool revision to the same callback that will actually be executed. */
public final class VersionedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolSnapshot snapshot;

    public VersionedToolCallback(String version, ToolCallback delegate, Object implementation) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Tool version must not be blank");
        }
        this.delegate = delegate;
        var definition = delegate.getToolDefinition();
        this.snapshot = new ToolSnapshot(new Ref(definition.name(), version), implementation.getClass().getName(),
                Fingerprints.sha256(Fingerprints.classBytes(implementation.getClass())),
                definition.description(), definition.inputSchema(),
                implementation instanceof ConfigurationSnapshot source ? source.versionedSettings() : java.util.Map.of());
    }

    public static VersionedToolCallback[] from(String version, Object tool) {
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                .map(callback -> new VersionedToolCallback(version, callback, tool))
                .toArray(VersionedToolCallback[]::new);
    }

    /** Select exactly the revisions declared by the release; never fall back to a newer version. */
    public static List<ToolCallback> select(List<ToolCallback> available, List<Ref> selected) {
        var catalog = new LinkedHashMap<Ref, VersionedToolCallback>();
        for (var callback : available) {
            if (!(callback instanceof VersionedToolCallback tool)) {
                throw new IllegalStateException("Tool must declare a version: " + callback.getToolDefinition().name());
            }
            if (catalog.putIfAbsent(tool.snapshot.revision(), tool) != null) {
                throw new IllegalStateException("Duplicate tool revision: " + tool.snapshot.revision());
            }
        }
        var result = new ArrayList<ToolCallback>();
        for (var revision : selected) {
            var tool = catalog.get(revision);
            if (tool == null) {
                throw new IllegalStateException("Tool revision is not registered: " + revision);
            }
            result.add(tool);
        }
        return List.copyOf(result);
    }

    public ToolSnapshot snapshot() { return snapshot; }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String arguments) { return delegate.call(arguments); }
    @Override public String call(String arguments, ToolContext context) { return delegate.call(arguments, context); }
}
