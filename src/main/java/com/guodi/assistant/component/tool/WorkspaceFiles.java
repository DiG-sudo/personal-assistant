/*
 * Tool descriptions adapted from AgentScope Java's FilesystemTool.
 * Copyright 2024-2026 the original author or authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.guodi.assistant.component.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.READ;

/** File tools rooted in the project directory supplied by each execution. */
public final class WorkspaceFiles {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private final Path dataDirectory;

    public WorkspaceFiles(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    Path root(ToolContext context) throws IOException {
        if (!(context.getContext().get("projectPath") instanceof Path projectPath)) {
            throw new IllegalArgumentException("projectPath is required in ToolContext");
        }
        Path root = projectPath.toRealPath();
        if (!Files.isDirectory(root) || root.getParent() == null) {
            throw new IllegalArgumentException("Configure a project directory as the workspace");
        }
        Path data = dataDirectory;
        Path existing = data;
        while (!Files.exists(existing, NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (containsDirectory(existing.toRealPath(), root)
                || (data.equals(existing) && containsDirectory(root, data.toRealPath()))) {
            throw new IllegalArgumentException("The assistant data directory and workspace must be separate");
        }
        return root;
    }

    private static boolean containsDirectory(Path path, Path directory) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSameFile(current, directory)) {
                return true;
            }
        }
        return false;
    }

    Path resolve(Path root, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A workspace-relative path is required");
        }
        Path relative = Path.of(path);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Use a path relative to the workspace");
        }
        Path current = root;
        for (Path segment : relative) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("Parent traversal is forbidden in workspace paths");
            }
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are forbidden in workspace paths");
            }
        }
        return current.normalize();
    }

    @Tool(name = "list_files", description = "List files and directories at the given path.")
    public String listFiles(@ToolParam(description = "Directory relative to the project root; use '.'. Absolute paths, '..', and symlinks are not allowed") String path, ToolContext context)
            throws IOException {
        Path root = root(context);
        try (var entries = Files.list(resolve(root, path))) {
            var listing = entries.filter(entry -> Files.isDirectory(entry, NOFOLLOW_LINKS) || isFile(entry))
                    .sorted()
                    .limit(201)
                    .map(entry -> (Files.isDirectory(entry, NOFOLLOW_LINKS) ? "[DIR]  " : "[FILE] ")
                            + root.relativize(entry))
                    .toList();
            if (listing.size() > 200) {
                return String.join("\n", listing.subList(0, 200)) + "\n[Listing truncated]";
            }
            return listing.isEmpty() ? "Empty directory: " + path : String.join("\n", listing);
        }
    }

    @Tool(name = "read_file", description = "Read a UTF-8 file up to 1 MiB with line numbers."
            + " Supports pagination via offset and limit. Output may be truncated.")
    public String readFile(
            @ToolParam(description = "File path relative to the project root, e.g., src/App.java. Absolute paths, '..', and symlinks are not allowed") String path,
            @ToolParam(description = "Start line (0-indexed). Default: 0 (from beginning)", required = false) Integer offset,
            @ToolParam(description = "Max lines to return. Default: 0 (all lines)", required = false) Integer limit, ToolContext context)
            throws IOException {
        Path root = root(context);
        int start = offset == null ? 0 : offset;
        int count = limit == null ? 0 : limit;
        if (start < 0 || count < 0) {
            throw new IllegalArgumentException("offset and limit must be non-negative");
        }
        String content = readText(resolve(root, path));
        var lines = content.lines().skip(start).iterator();
        StringBuilder result = new StringBuilder();
        for (int read = 0; lines.hasNext() && (count == 0 || read < count); read++) {
            if (read > 0) {
                result.append('\n');
            }
            result.append(start + read + 1).append(": ").append(lines.next());
        }
        return result.toString();
    }

    @Tool(name = "glob_files", description = "Find files matching a glob pattern. Returns at most 200 files by default to keep tool output bounded.")
    public String globFiles(
            @ToolParam(description = "Glob pattern (e.g., **/*.java)") String pattern,
            @ToolParam(description = "Directory relative to the project root (default: '.'). Absolute paths, '..', and symlinks are not allowed", required = false) String path,
            @ToolParam(description = "Maximum files to return (default/recommended: 200; hard maximum: 1000)", required = false)
            Integer limit, ToolContext context) throws IOException {
        Path root = root(context);
        Path base = resolve(root, path == null ? "." : path);
        if (!Files.isDirectory(base, NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("glob_files expects a directory");
        }
        int maximum = searchLimit(limit, 200);
        PathMatcher matcher = glob(pattern);
        try (var paths = Files.walk(base)) {
            var matches = paths.filter(WorkspaceFiles::isFile)
                    .filter(file -> matcher.matches(base.relativize(file)))
                    .limit(maximum).map(file -> root.relativize(file).toString()).toList();
            return matches.isEmpty() ? "No matching files found" : String.join("\n", matches);
        }
    }

    @Tool(name = "grep_files", description = "Search file contents for a literal text pattern. Returns at most 100 matches by default to keep tool output bounded.")
    public String grepFiles(
            @ToolParam(description = "Literal text pattern to search for") String pattern,
            @ToolParam(description = "Directory or file relative to the project root (default: '.'). Absolute paths, '..', and symlinks are not allowed", required = false) String path,
            @ToolParam(description = "Optional file glob filter (e.g., *.java)", required = false) String glob,
            @ToolParam(description = "Maximum matches to return (default/recommended: 100; hard maximum: 1000)", required = false)
            Integer limit, ToolContext context) throws IOException {
        Path root = root(context);
        requireText(pattern, "pattern");
        int maximum = searchLimit(limit, 100);
        Path base = resolve(root, path == null ? "." : path);
        PathMatcher matcher = glob == null ? ignored -> true : glob(glob);
        Path relativeTo = Files.isDirectory(base, NOFOLLOW_LINKS) ? base : base.getParent();
        var matches = new ArrayList<String>();
        try (var paths = Files.walk(base)) {
            var files = paths.filter(WorkspaceFiles::isFile)
                    .filter(file -> matcher.matches(relativeTo.relativize(file))).iterator();
            while (matches.size() < maximum && files.hasNext()) {
                Path file = files.next();
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                String content;
                try {
                    content = readText(file);
                } catch (CharacterCodingException binary) {
                    continue;
                }
                var lines = content.lines().iterator();
                for (int number = 1; matches.size() < maximum && lines.hasNext(); number++) {
                    String line = lines.next();
                    int match = line.indexOf(pattern);
                    if (match >= 0) {
                        int start = Math.max(0, match - 100);
                        String excerpt = line.substring(start, Math.min(line.length(), start + 500));
                        matches.add(root.relativize(file) + ":" + number + ":" + excerpt);
                    }
                }
            }
        }
        return matches.isEmpty() ? "No matches found" : String.join("\n", matches);
    }

    @Tool(name = "write_file", description = "Create a new UTF-8 file up to 1 MiB, creating parent directories if needed."
            + " Existing files are rejected; use edit_file to modify them.")
    public synchronized String writeFile(
            @ToolParam(description = "File path relative to the project root, e.g., index.html. Absolute paths, '..', and symlinks are not allowed") String path,
            @ToolParam(description = "File content to write") String content, ToolContext context) throws IOException {
        Path root = root(context);
        Path target = resolve(root, path);
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File already exists. Use edit_file: " + path);
        }
        save(target, content, false);
        return "Written to " + path;
    }

    @Tool(name = "edit_file", description = "Perform exact string replacement in a file. The old_string must be unique unless replace_all is true.")
    public synchronized String editFile(
            @ToolParam(description = "File path relative to the project root, e.g., src/App.java. Absolute paths, '..', and symlinks are not allowed") String path,
            @ToolParam(description = "Text to find") String old_string,
            @ToolParam(description = "Replacement text") String new_string,
            @ToolParam(description = "Replace all occurrences (default: false)", required = false) Boolean replace_all, ToolContext context)
            throws IOException {
        Path root = root(context);
        requireText(old_string, "old_string");
        Path target = resolve(root, path);
        String content = readText(target);
        int first = content.indexOf(old_string);
        if (first < 0) {
            throw new IllegalArgumentException("old_string was not found in " + path);
        }
        int occurrences = 1;
        int next = content.indexOf(old_string, first + old_string.length());
        if (next >= 0 && !Boolean.TRUE.equals(replace_all)) {
            throw new IllegalArgumentException("old_string is ambiguous. Supply more surrounding text or set replace_all");
        }
        while (next >= 0) {
            occurrences++;
            next = content.indexOf(old_string, next + old_string.length());
        }
        long length = content.length() + (long) occurrences * (new_string.length() - old_string.length());
        if (length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Edited file exceeds the 1 MiB limit");
        }
        save(target, content.replace(old_string, new_string), true);
        return "Edited " + path + " (" + occurrences + " replacement(s))";
    }

    private static boolean isFile(Path path) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Workspace operation interrupted");
        }
        return Files.isRegularFile(path, NOFOLLOW_LINKS);
    }

    private static String readText(Path path) throws IOException {
        if (!isFile(path)) {
            throw new IllegalArgumentException("Expected a regular file: " + path.getFileName());
        }
        try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("File exceeds the 1 MiB limit");
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static void save(Path target, String content, boolean replace) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File exceeds the 1 MiB limit");
        }
        if (!replace) {
            Files.createDirectories(target.getParent());
        }
        Path temporary = Files.createTempFile(target.getParent(), ".assistant-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) {
                if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target, NOFOLLOW_LINKS));
                }
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
            } else {
                Files.createLink(target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    private static int searchLimit(Integer limit, int defaultLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(limit, 1000);
    }

    private static PathMatcher glob(String pattern) {
        requireText(pattern, "pattern");
        var filesystem = FileSystems.getDefault();
        String nested = pattern.startsWith("**/") ? pattern : "**/" + pattern;
        String direct = pattern.startsWith("**/") ? pattern.substring(3) : pattern;
        var nestedMatcher = filesystem.getPathMatcher("glob:" + nested);
        var directMatcher = filesystem.getPathMatcher("glob:" + direct);
        return path -> directMatcher.matches(path) || nestedMatcher.matches(path);
    }
}
