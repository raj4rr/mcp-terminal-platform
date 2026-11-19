package dev.raj.mcpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
