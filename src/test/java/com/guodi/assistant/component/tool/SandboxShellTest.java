package com.guodi.assistant.component.tool;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class SandboxShellTest {
    @TempDir Path directory;

    @Test
    void createsOnceReusesAndRestartsAStoppedProjectContainer() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project")).toRealPath();
        var shell = new SandboxShell(new WorkspaceFiles(directory.resolve("data")), "local-test-image");
        var context = new ToolContext(Map.of("projectPath", project));
        var exists = new AtomicBoolean();
        var running = new AtomicBoolean();
        var commands = new ArrayList<List<String>>();
        String commandOutput = "输出".repeat(8192) + "\n尾部";
        try (var ignored = mockConstruction(ProcessBuilder.class, (builder, construction) -> {
            @SuppressWarnings("unchecked")
            var arguments = (List<String>) construction.arguments().get(0);
            commands.add(arguments);
            String output = "";
            if (arguments.contains("ls")) {
                output = exists.get() ? "container-id\n" : "";
            } else if (arguments.get(1).equals("create")) {
                exists.set(true);
            } else if (arguments.get(1).equals("start")) {
                running.set(true);
            } else if (arguments.contains("inspect")) {
                output = new ObjectMapper().writeValueAsString(List.of(Map.of(
                        "Config", Map.of("Image", "local-test-image", "Labels",
                                Map.of("personal-assistant.project-path", project.toString())),
                        "Mounts", List.of(Map.of("Type", "bind", "Source", project.toString(),
                                "Destination", "/workspace", "RW", true)),
                        "State", Map.of("Running", running.get()))));
            } else if (arguments.get(1).equals("exec")) {
                output = commandOutput;
            }
            Process process = mock(Process.class);
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenReturn(process);
            when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
            when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
            when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(process.exitValue()).thenReturn(0);
        })) {
            assertThat(shell.execute("git status", null, 30, null, context)).isEqualTo("Exit code: 0\n" + commandOutput);
            shell.execute("npm test", null, 30, null, context);
            running.set(false);
            shell.execute("git diff", null, 30, null, context);
        }
        assertThat(commands.stream().filter(args -> args.get(1).equals("create")).count()).isEqualTo(1);
        assertThat(commands.stream().filter(args -> args.get(1).equals("start")).count()).isEqualTo(2);
        assertThat(commands.stream().filter(args -> args.get(1).equals("exec")).count()).isEqualTo(3);
        assertThat(commands).allSatisfy(args -> assertThat(args.get(0)).isEqualTo("docker"));
    }
}
