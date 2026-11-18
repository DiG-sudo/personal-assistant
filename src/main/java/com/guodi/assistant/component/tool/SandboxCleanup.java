package com.guodi.assistant.component.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Resets an existing project sandbox without deleting its files or installed dependencies. */
public final class SandboxCleanup {
    public void reset(Path projectPath) {
        String name = SandboxShell.containerName(projectPath);
        List<String> command = List.of("docker", "container", "ls", "--all",
                "--filter", "name=^/" + name + "$",
                "--filter", "label=personal-assistant.project-path=" + projectPath,
                "--format", "{{.ID}}");
        for (int step = 0; step < 2; step++) {
            Process process = null;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                process.getOutputStream().close();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Sandbox cleanup timed out for " + projectPath);
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("Sandbox cleanup failed (exit code " + process.exitValue() + "): " + output);
                }
                if (step == 0) {
                    if (output.isEmpty()) return;
                    command = List.of("docker", "restart", "--time", "0", output);
                }
            } catch (IOException error) {
                throw new UncheckedIOException("Could not reset sandbox for " + projectPath, error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Sandbox cleanup interrupted");
            } finally {
                if (process != null) {
                    if (process.isAlive()) process.destroyForcibly();
                    try {
                        process.getInputStream().close();
                    } catch (IOException ignored) {
                        // Preserve the cleanup result or original failure.
                    }
                }
            }
        }
    }
}
