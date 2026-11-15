package com.guodi.assistant.config;

import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.ClassUtils;

import com.guodi.assistant.component.tool.SandboxShell;
import com.guodi.assistant.component.tool.ToolBinding;
import com.guodi.assistant.component.tool.ToolBinding.Kind;
import com.guodi.assistant.component.tool.WorkspaceFiles;

/** Declares built-in tools through the same bindings used by custom tools. */
@Configuration(proxyBeanMethods = false)
@Lazy
class BuiltinToolConfiguration {
    @Bean
    @ConditionalOnMissingBean
    WorkspaceFiles workspaceFiles(AssistantProperties properties) {
        return new WorkspaceFiles(properties.dataDirectory());
    }

    @Bean
    ToolBinding listFilesTool(WorkspaceFiles workspace) {
        return binding(Kind.READ, workspace, "listFiles");
    }

    @Bean
    ToolBinding globFilesTool(WorkspaceFiles workspace) {
        return binding(Kind.READ, workspace, "globFiles");
    }

    @Bean
    ToolBinding grepFilesTool(WorkspaceFiles workspace) {
        return binding(Kind.READ, workspace, "grepFiles");
    }

    @Bean
    ToolBinding readFileTool(WorkspaceFiles workspace) {
        return binding(Kind.READ, workspace, "readFile");
    }

    @Bean
    ToolBinding writeFileTool(WorkspaceFiles workspace) {
        return binding(Kind.WRITE, workspace, "writeFile");
    }

    @Bean
    ToolBinding editFileTool(WorkspaceFiles workspace) {
        return binding(Kind.WRITE, workspace, "editFile");
    }

    @Bean
    ToolBinding executeTool(WorkspaceFiles workspace, AssistantProperties properties) {
        return binding(Kind.SHELL, new SandboxShell(workspace, properties.shellImage()), "execute");
    }

    private static ToolBinding binding(Kind kind, Object tool, String methodName) {
        var method = ClassUtils.getMethod(tool.getClass(), methodName, (Class<?>[]) null);
        var callback = MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.from(method))
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(tool)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
        return new ToolBinding(kind, callback);
    }
}
