/*
 * Tool descriptions adapted from AgentScope Java's ShellExecuteTool.
 * Copyright 2024-2026 the original author or authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.guodi.assistant.component.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Executes commands in a persistent Docker container for each project. */
public final class SandboxShell {
    private static final Logger log = LoggerFactory.getLogger(SandboxShell.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DOCKER_TIMEOUT_SECONDS = 30;
    private static final int CREATE_TIMEOUT_SECONDS = 300;
    private static final String PROJECT_LABEL = "personal-assistant.project-path";
    private final WorkspaceFiles workspace;
    private final String image;

    public SandboxShell(WorkspaceFiles workspace, String image) {
        if (image == null || image.isBlank() || image.startsWith("-")) {
            throw new IllegalArgumentException("Set assistant.shell-image to a Docker image to enable execute");
        }
        this.workspace = workspace;
        this.image = image;
    }

    @Tool(name = "execute", description =
            "Execute a shell command in a Linux sandbox. The project is mounted at /workspace,"
                    + " the default working directory, and shares files with the file tools."
                    + " Development tools may need installation. Docker is managed outside the sandbox."
                    + " Each call starts a new shell; cd and exports do not persist between calls."
                    + " Use for git, npm, build, test, and other terminal operations."
                    + " Set run_in_background to true for persistent commands; returns after launch without waiting for completion."
                    + " Check readiness, process status, and any application logs with subsequent commands."
                    + " Returns combined output and exit code for foreground commands. If a dedicated tool"
                    + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                    + " commands. Provide the command directly, without docker exec or docker run prefixes."
                    + " Examples: {\"command\":\"git status\"}, {\"command\":\"npm test\"}.")
    public synchronized String execute(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(description = "Project-relative working directory (default: '.'). Absolute paths, '..', and symlinks are not allowed", required = false)
            String working_directory,
            @ToolParam(description = "Timeout in seconds: 1-120 (default: 30)", required = false)
            Integer timeout,
            @ToolParam(description = "Run in the background and return after launch without waiting for completion (default: false)", required = false)
            Boolean run_in_background, ToolContext context) throws IOException {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("A shell command is required");
        }
        Path root = workspace.root(context);
        Path directory = workspace.resolve(root, working_directory == null ? "." : working_directory);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("working_directory must be an existing workspace directory");
        }
        int seconds = timeout == null ? 30 : timeout;
        if (seconds < 1 || seconds > 120) {
            throw new IllegalArgumentException("timeout must be between 1 and 120 seconds");
        }
        String container = ensureContainer(root);
        var arguments = executeArguments(container, root.relativize(directory), command);
        if (Boolean.TRUE.equals(run_in_background)) {
            arguments = new java.util.ArrayList<>(arguments);
            arguments.add(2, "-d");
            runDocker(arguments, seconds, container).checkedOutput();
            return "Background command submitted. Check readiness with a subsequent command.";
        }
        var result = runDocker(arguments, seconds, container);
        return "Exit code: " + result.exitCode() + "\n" + result.checkedOutput();
    }

    /** The caller supplies the canonical project root obtained from WorkspaceFiles. */
    private String ensureContainer(Path root) throws IOException {
        String name = containerName(root);
        // A failed Docker connection must not be mistaken for a missing container.
        String existing = runDocker(List.of("docker", "container", "ls", "--all",
                "--filter", "name=^/" + name + "$", "--format", "{{.ID}}"), DOCKER_TIMEOUT_SECONDS, null)
                .checkedOutput().strip();
        if (existing.isEmpty()) {
            runDocker(createArguments(root, name), CREATE_TIMEOUT_SECONDS, null).checkedOutput();
            runDocker(List.of("docker", "start", name), DOCKER_TIMEOUT_SECONDS, null).checkedOutput();
        } else {
            String output = runDocker(List.of("docker", "container", "inspect", name), DOCKER_TIMEOUT_SECONDS, null)
                    .checkedOutput();
            JsonNode container = JSON.readTree(output).get(0);
            validateContainer(container, root);
            if (!container.path("State").path("Running").asBoolean()) {
                runDocker(List.of("docker", "start", name), DOCKER_TIMEOUT_SECONDS, null).checkedOutput();
            }
        }
        return name;
    }

    private void validateContainer(JsonNode container, Path root) {
        JsonNode config = container.path("Config");
        if (!root.toString().equals(config.path("Labels").path(PROJECT_LABEL).asText())
                || !image.equals(config.path("Image").asText())) {
            throw new IllegalStateException("Existing sandbox does not match the project or configured image; "
                    + "remove it explicitly before creating a replacement");
        }
        int binds = 0;
        for (JsonNode mount : container.path("Mounts")) {
            if ("bind".equals(mount.path("Type").asText())) {
                binds++;
                if (!root.toString().equals(mount.path("Source").asText())
                        || !"/workspace".equals(mount.path("Destination").asText())
                        || !mount.path("RW").asBoolean()) {
                    throw new IllegalStateException("Existing sandbox has an unexpected host bind mount");
                }
            }
        }
        if (binds != 1) {
            throw new IllegalStateException("Existing sandbox must bind mount only the project directory");
        }
    }

    static String containerName(Path root) {
        return "personal-assistant-" + UUID.nameUUIDFromBytes(root.toString().getBytes(StandardCharsets.UTF_8));
    }

    List<String> createArguments(Path root, String name) {
        // Docker parses --mount as CSV; quote fields so commas and quotes in paths stay literal.
        String mount = "\"type=bind\",\"source=" + root.toString().replace("\"", "\"\"")
                + "\",\"target=/workspace\",\"bind-recursive=disabled\"";
        return List.of(
                "docker", "create", "--name", name, "--pull=missing", "--init",
                "--label", PROJECT_LABEL + "=" + root,
                "--network=bridge", "--security-opt=no-new-privileges", "--user=0:0",
                "--cpus=2", "--memory=1g", "--memory-swap=1g", "--pids-limit=128",
                "--log-driver=none", "--mount", mount, "--workdir", "/workspace",
                "--entrypoint", "/bin/sh", image, "-c", "while :; do sleep 3600; done");
    }

    List<String> executeArguments(String name, Path directory, String command) {
        String relative = directory.toString();
        String workingDirectory = relative.isEmpty() ? "/workspace" : "/workspace/" + relative;
        return List.of("docker", "exec", "--workdir", workingDirectory, name, "/bin/sh", "-c", command);
    }

    private static DockerResult runDocker(List<String> arguments, int seconds, String containerToStop)
            throws IOException {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        var output = new FutureTask<>(() -> {
            try (var input = process.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
        Thread.ofVirtual().start(output);
        boolean completed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new TimeoutException();
            }
            String text = output.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            completed = true;
            return new DockerResult(process.exitValue(), text);
        } catch (TimeoutException error) {
            throw new IllegalStateException("Docker command timed out after " + seconds + " seconds", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Shell execution interrupted");
        } catch (ExecutionException error) {
            throw new IllegalStateException("Could not read Docker output", error.getCause());
        } finally {
            if (!completed) {
                // Killing only the Docker client would leave the command running inside the container.
                if (containerToStop != null) {
                    stopContainer(containerToStop);
                }
                process.destroyForcibly();
                output.cancel(true);
            }
        }
    }

    private static void stopContainer(String name) {
        boolean interrupted = Thread.interrupted();
        try {
            Process cleanup = new ProcessBuilder("docker", "stop", "--time", "0", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
                log.warn("Timed out stopping sandbox {}", name);
            } else if (cleanup.exitValue() != 0) {
                log.warn("Could not stop sandbox {} (exit code {})", name, cleanup.exitValue());
            }
        } catch (InterruptedException error) {
            interrupted = true;
        } catch (IOException error) {
            log.warn("Could not stop sandbox {}", name, error);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record DockerResult(int exitCode, String output) {
        String checkedOutput() {
            if (exitCode != 0) {
                throw new IllegalStateException("Exit code: " + exitCode + "\n" + output);
            }
            return output;
        }
    }
}
