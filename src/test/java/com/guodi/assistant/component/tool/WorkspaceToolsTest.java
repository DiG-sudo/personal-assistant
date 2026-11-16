package com.guodi.assistant.component.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceToolsTest {
    @TempDir Path directory;

    @Test
    void filesSupportPaginationLiteralSearchAndUnambiguousEdits() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        var tools = new WorkspaceFiles(directory.resolve("data"));
        var context = new ToolContext(Map.of("projectPath", project));
        tools.writeFile("Main.java", "one\r\na.b\r\na.b\r\n", context);
        tools.writeFile("src/Other.java", "axb", context);

        assertThat(tools.listFiles(".", context)).contains("[FILE] Main.java", "[DIR]  src");
        assertThat(tools.readFile("Main.java", 1, 1, null, context)).isEqualTo("2: a.b");
        assertThat(tools.globFiles("**/*.java", null, 10, context)).contains("Main.java", "src/Other.java");
        assertThat(tools.grepFiles("a.b", null, "*.java", 1, context)).isEqualTo("Main.java:2:a.b");
        assertThatThrownBy(() -> tools.editFile("Main.java", "a.b", "x", false, context)).hasMessageContaining("ambiguous");
        assertThat(tools.editFile("Main.java", "a.b", "x", true, context)).contains("2 replacement(s)");
        assertThat(Files.readString(project.resolve("Main.java"))).isEqualTo("one\r\nx\r\nx\r\n");
    }

    @Test
    void readsLargeFilesAndContinuesLongUnicodeLinesWithoutDroppingCharacters() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        var tools = new WorkspaceFiles(directory.resolve("data"));
        var context = new ToolContext(Map.of("projectPath", project));
        String line = "中😀".repeat(400000);
        Files.writeString(project.resolve("huge.txt"), "first\r\n" + line + "\r\nlast\n");
        assertThat(tools.readFile("huge.txt", 2, 1, null, context)).isEqualTo("3: last");
        String first = tools.readFile("huge.txt", 1, 1, null, context);
        var continuation = java.util.regex.Pattern.compile("\\[Continue with read_file: offset=(\\d+), char_offset=(\\d+), limit=(\\d+)\\]")
                .matcher(first);
        assertThat(continuation.find()).isTrue();
        int position = Integer.parseInt(continuation.group(2));
        String second = tools.readFile("huge.txt", Integer.parseInt(continuation.group(1)),
                Integer.parseInt(continuation.group(3)), position, context);
        String prefix = first.substring("2: ".length(), first.lastIndexOf("\n[Continue"));
        assertThat(prefix + second.substring("2: ".length())).isEqualTo(line);
        assertThat(prefix.codePointCount(0, prefix.length())).isEqualTo(position);
        assertThatThrownBy(() -> tools.readFile("huge.txt", 0, 1, -1, context)).hasMessageContaining("non-negative");
        // The existing edit/write size limit is independent of paginated reading.
        assertThatThrownBy(() -> tools.editFile("huge.txt", "first", "changed", false, context)).hasMessageContaining("1 MiB");
    }

    @Test
    void filesRejectOutsidePathsAndSkipSymlinksDuringSearch() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(project.resolve("escape"), outside);
        Files.createSymbolicLink(project.resolve("dangling"), outside.resolve("new.txt"));
        Files.createLink(project.resolve("hardlink.txt"), secret);
        var tools = new WorkspaceFiles(directory.resolve("data"));
        var context = new ToolContext(Map.of("projectPath", project));

        assertThatThrownBy(() -> tools.readFile(secret.toString(), null, null, null, context)).hasMessageContaining("relative");
        assertThatThrownBy(() -> tools.readFile("escape/secret.txt", null, null, null, context)).hasMessageContaining("Symbolic");
        assertThatThrownBy(() -> tools.writeFile("escape/new.txt", "changed", context)).hasMessageContaining("Symbolic");
        assertThatThrownBy(() -> tools.writeFile("dangling", "changed", context)).hasMessageContaining("Symbolic");
        assertThatThrownBy(() -> tools.writeFile("../outside/new.txt", "changed", context)).hasMessageContaining("traversal");
        assertThatThrownBy(() -> tools.editFile("escape/secret.txt", "secret", "changed", false, context))
                .hasMessageContaining("Symbolic");
        tools.editFile("hardlink.txt", "secret", "edited locally", false, context);
        assertThat(tools.grepFiles("secret", null, null, null, context)).isEqualTo("No matches found");
        assertThat(tools.globFiles("**/*.txt", null, null, context)).isEqualTo("hardlink.txt");
        assertThat(Files.readString(secret)).isEqualTo("secret");
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThatThrownBy(() -> new WorkspaceFiles(project.resolve("data")).listFiles(".", context))
                .hasMessageContaining("must be separate");
    }

    @Test
    void oneFileToolInstanceUsesTheProjectFromEachCall() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        var tools = new WorkspaceFiles(directory.resolve("data"));
        var firstContext = new ToolContext(Map.of("projectPath", first));
        var secondContext = new ToolContext(Map.of("projectPath", second));

        tools.writeFile("README.md", "first", firstContext);
        tools.writeFile("README.md", "second", secondContext);

        assertThat(tools.readFile("README.md", null, null, null, firstContext)).isEqualTo("1: first");
        assertThat(tools.readFile("README.md", null, null, null, secondContext)).isEqualTo("1: second");
        assertThat(ToolCallbacks.from(tools)).allSatisfy(callback ->
                assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("projectPath", "context"));
    }

    @Test
    void shellCommandsArePassedOnlyToTheProjectContainer() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project")).toRealPath();
        var shell = new SandboxShell(new WorkspaceFiles(directory.resolve("data")), "local-test-image");
        var context = new ToolContext(Map.of("projectPath", project));
        var definition = ToolCallbacks.from(shell)[0].getToolDefinition();
        assertThat(definition.description()).contains("in a Linux sandbox", "without docker exec or docker run prefixes",
                "{\"command\":\"git status\"}", "{\"command\":\"npm test\"}");
        assertThat(definition.inputSchema()).contains("Timeout in seconds: 1-120 (default: 30)")
                .doesNotContain("projectPath", "context");
        String name = SandboxShell.containerName(project);
        assertThat(SandboxShell.containerName(project)).isEqualTo(name);
        assertThat(SandboxShell.containerName(directory.resolve("another-project"))).isNotEqualTo(name);
        Path mounted = directory.resolve("with,comma and \"quotes\"");
        var create = shell.createArguments(mounted, name);
        assertThat(create).startsWith("docker", "create").contains("--network=bridge", "--pull=missing",
                        "--cpus=2", "--memory=1g", "--memory-swap=1g", "--pids-limit=128")
                .doesNotContain("--rm", "--read-only", "--privileged", "--network=host");
        assertThat(create.get(create.indexOf("--mount") + 1))
                .contains("\"source=" + mounted.toString().replace("\"", "\"\"") + "\"", "target=/workspace")
                .doesNotContain("readonly");
        String command = "printf '%s' \"$HOME\"; echo test > result.txt";
        assertThat(shell.executeArguments(name, Path.of("directory with spaces"), command))
                .containsExactly("docker", "exec", "--workdir", "/workspace/directory with spaces",
                        name, "/bin/sh", "-c", command);
        assertThat(shell.executeArguments(name, Path.of(""), command))
                .containsSequence("--workdir", "/workspace");
        assertThatThrownBy(() -> shell.execute(command, "..", 30, context)).hasMessageContaining("traversal");
        assertThatThrownBy(() -> shell.execute(command, ".", 121, context)).hasMessageContaining("120");
    }
}
