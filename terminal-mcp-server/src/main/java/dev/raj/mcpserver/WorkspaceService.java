package dev.raj.mcpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Collectors;

public class WorkspaceService {
    private static final int MAX_READ = 256 * 1024;
    private final Path root;

    public WorkspaceService(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public Path getRoot() {
        return root;
    }

    public Path safePath(String relative) throws Exception {
        Path realRoot = root.toRealPath();
        Path target = realRoot.resolve(relative == null || relative.isBlank() ? "." : relative).normalize();
        if (!target.startsWith(realRoot)) {
            throw new SecurityException("Path escapes workspace");
        }
        Path probe = target;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null || !probe.toRealPath().startsWith(realRoot)) {
            throw new SecurityException("Symlink/path resolves outside workspace");
        }
        return target;
    }

    public String listFiles(String rel) throws Exception {
        Path dir = safePath(rel);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory");
        }
        try (var stream = Files.list(dir)) {
            return stream.sorted()
                .map(p -> (Files.isDirectory(p) ? "[DIR]  " : "[FILE] ") + p.getFileName())
                .collect(Collectors.joining("\n"));
        }
    }

    public String readFile(String rel) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path f = safePath(rel);
        if (!Files.isRegularFile(f)) {
            throw new IllegalArgumentException("Not a regular file");
        }
        if (Files.size(f) > MAX_READ) {
            throw new IllegalArgumentException("File exceeds 256 KB");
        }
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    public String writeFile(String rel, String content) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        Path f = safePath(rel);
        if (f.equals(root.toRealPath())) {
            throw new IllegalArgumentException("Cannot overwrite workspace root");
        }
        if (Files.isDirectory(f)) {
            throw new IllegalArgumentException("Target is a directory");
        }
        if (f.getParent() != null && !Files.exists(f.getParent())) {
            Files.createDirectories(f.getParent());
        }
        Files.writeString(f, content == null ? "" : content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "Saved " + f.getFileName() + " (" + (content == null ? 0 : content.length()) + " bytes)";
    }

    public String createDirectory(String rel) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalArgumentException("Folder path is required");
        }
        Path dir = safePath(rel);
        Files.createDirectories(dir);
        return "Directory created: " + dir.getFileName();
    }

    public String deletePath(String rel) throws Exception {
        if (rel == null || rel.isBlank() || rel.trim().equals(".") || rel.trim().equals("./")) {
            throw new SecurityException("Cannot delete workspace root directory");
        }
        Path target = safePath(rel);
        if (target.equals(root.toRealPath())) {
            throw new SecurityException("Cannot delete workspace root directory");
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Path does not exist: " + rel);
        }
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
            return "Deleted directory: " + target.getFileName();
        } else {
            Files.delete(target);
            return "Deleted file: " + target.getFileName();
        }
    }
}
