package com.guodi.assistant.component.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.guodi.assistant.config.AssistantAutoConfiguration;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.core.ConversationService.SessionMessage;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: uses only a temporary project and removes its own Docker container. */
@EnabledIfSystemProperty(named = "assistant.docker-tests", matches = "true")
@Timeout(30)
class SandboxLifecycleTest {
    @TempDir Path directory;

    @Test
    void backgroundCommandReturnsAndIdleHeartbeatExpiryReclaimsItsProcesses() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project")).toRealPath();
        Path data = directory.resolve("data");
        String name = SandboxShell.containerName(project);
        var shell = new SandboxShell(new WorkspaceFiles(data), "ubuntu:24.04");
        var tools = new ToolContext(Map.of("projectPath", project));
        Files.writeString(project.resolve("keep.txt"), "project data");
        try {
            shell.execute("printf installed > /opt/agent-cleanup-marker", null, 5, false, tools);
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(AssistantAutoConfiguration.class))
                    .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                    .withBean(ChatModel.class, () -> prompt -> new ChatResponse(
                            List.of(new Generation(new AssistantMessage("done")))))
                    .withPropertyValues("assistant.data-directory=" + data,
                            "assistant.shell-image=ubuntu:24.04", "assistant.model.provider=demo",
                            "assistant.control.heartbeat-interval-seconds=1",
                            "assistant.control.heartbeat-request-timeout-seconds=1",
                            "assistant.control.heartbeat-timeout-seconds=2")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var service = context.getBean(ConversationService.class);
                        service.openControl("docker-test");
                        service.send("docker-test", new SessionMessage(java.util.UUID.randomUUID(), "hello", project));
                        long started = System.nanoTime();
                        assertThat(shell.execute("sleep 120 & child=$!; echo $child > long-command.pid; wait $child",
                                null, 5, true, tools)).contains("Background command submitted");
                        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5000);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        Path pid = project.resolve("long-command.pid");
                        while ((!Files.exists(pid) || Files.size(pid) == 0) && System.nanoTime() < deadline) {
                            Thread.sleep(50);
                        }
                        assertThat(shell.execute("kill -0 \"$(cat long-command.pid)\"", null, 5, false, tools))
                                .contains("Exit code: 0");
                        assertThat(docker("top", name)).contains("sleep 120");
                        String before = docker("inspect", "--format", "{{.State.StartedAt}}", name);
                        assertThat(service.heartbeat("docker-test")).isTrue();
                        String after = before;
                        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        while (after.equals(before) && System.nanoTime() < deadline) {
                            Thread.sleep(200);
                            after = docker("inspect", "--format", "{{.State.StartedAt}}", name);
                        }
                        assertThat(after).isNotEqualTo(before);
                        assertThat(service.heartbeat("docker-test")).isFalse();
                        assertThat(docker("top", name)).doesNotContain("sleep 120");
                        assertThat(project.resolve("keep.txt")).hasContent("project data");
                        assertThat(shell.execute("cat /opt/agent-cleanup-marker", null, 5, false, tools))
                                .contains("installed");
                        Thread.sleep(1200);
                        assertThat(docker("inspect", "--format", "{{.State.StartedAt}}", name)).isEqualTo(after);
                        service.openControl("docker-test");
                        assertThat(service.heartbeat("docker-test")).isTrue();
                        assertThat(docker("inspect", "--format", "{{.State.StartedAt}}", name)).isEqualTo(after);
                    });
        } finally {
            docker("rm", "--force", name);
        }
    }

    @Test
    void foregroundCommandStillWaitsForItsExitAndReturnsOutput() throws Exception {
        Path project = Files.createDirectory(directory.resolve("foreground")).toRealPath();
        var shell = new SandboxShell(new WorkspaceFiles(directory.resolve("data")), "ubuntu:24.04");
        var tools = new ToolContext(Map.of("projectPath", project));
        try {
            shell.execute("true", null, 5, null, tools);
            long started = System.nanoTime();
            assertThat(shell.execute("sleep 1; printf finished", null, 5, null, tools))
                    .isEqualTo("Exit code: 0\nfinished");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(900);
        } finally {
            docker("rm", "--force", SandboxShell.containerName(project));
        }
    }

    private static String docker(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Docker command completes: %s", command).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.exitValue()).as("Docker command succeeds: %s; output: %s", command, output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }
}
