package com.guodi.assistant.component.tool;

import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;

public record ToolBinding(Kind kind, ToolCallback callback) {
    public ToolBinding {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(callback);
    }

    public enum Kind {
        READ,
        WRITE,
        SHELL
    }
}
