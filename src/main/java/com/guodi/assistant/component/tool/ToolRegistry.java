package com.guodi.assistant.component.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.ai.tool.ToolCallback;

public final class ToolRegistry {
    private final Map<String, ToolBinding> bindings;

    public ToolRegistry(List<ToolBinding> tools) {
        var registered = new TreeMap<String, ToolBinding>();
        for (var binding : tools) {
            String name = binding.callback().getToolDefinition().name();
            if (registered.putIfAbsent(name, binding) != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        bindings = Collections.unmodifiableMap(registered);
    }

    ToolBinding findBinding(String name) {
        return bindings.get(name);
    }

    public List<ToolCallback> callbacks() {
        return bindings.values().stream().map(ToolBinding::callback).toList();
    }
}
