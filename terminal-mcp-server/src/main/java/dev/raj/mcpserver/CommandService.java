package dev.raj.mcpserver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CommandService {
    private static final Set<String> ALLOWED_BINARIES = Set.of(
        "ls", "mkdir", "touch", "cat", "head", "tail", "rm", "rmdir",
        "cp", "mv", "echo", "pwd", "date", "whoami", "uname", "java", "git"
    );

    public String runCommand(Path root, String commandLine) throws Exception {
        if (commandLine == null || commandLine.isBlank()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        List<String> rawTokens = tokenize(commandLine.trim());
        if (rawTokens.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        String binary = rawTokens.get(0);
        if (!ALLOWED_BINARIES.contains(binary)) {
            throw new SecurityException("Command '" + binary + "' is not permitted. Allowed commands: "
                + String.join(", ", ALLOWED_BINARIES.stream().sorted().toList()));
        }

        String redirectTarget = null;
        boolean append = false;
        List<String> commandTokens = new ArrayList<>();

        for (int i = 0; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);
            if (token.equals(">") || token.equals(">>")) {
                append = token.equals(">>");
                if (i + 1 < rawTokens.size()) {
                    redirectTarget = rawTokens.get(i + 1);
                    i++;
                } else {
                    throw new IllegalArgumentException("Missing target file for redirection " + token);
                }
            } else {
                commandTokens.add(token);
            }
        }

        Path realRoot = root.toRealPath();

        // Validate non-flag arguments to enforce workspace confinement
        for (int i = 1; i < commandTokens.size(); i++) {
            String arg = commandTokens.get(i);
            if (!arg.startsWith("-")) {
                validatePathSafety(realRoot, arg, binary);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(commandTokens);
        pb.directory(root.toFile());

        if (redirectTarget != null) {
            validatePathSafety(realRoot, redirectTarget, binary);
            Path targetFile = safeResolve(realRoot, redirectTarget);
            if (targetFile.getParent() != null && !Files.exists(targetFile.getParent())) {
                Files.createDirectories(targetFile.getParent());
            }
            if (append) {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(targetFile.toFile()));
            } else {
                pb.redirectOutput(targetFile.toFile());
            }
            pb.redirectErrorStream(true);
        } else {
            pb.redirectErrorStream(true);
        }

        Process p = pb.start();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timed out after 10 seconds");
        }

        String output = "";
        if (redirectTarget == null) {
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } else {
            output = "Output redirected to " + redirectTarget;
        }

        return "exitCode=" + p.exitValue() + (output.isBlank() ? "" : "\n" + output);
    }

    private void validatePathSafety(Path realRoot, String arg, String binary) throws Exception {
        if (arg == null || arg.isBlank()) return;
        if (arg.equals("/") || arg.equals("~") || arg.startsWith("/etc") || arg.startsWith("/var")
                || arg.startsWith("/usr") || arg.startsWith("/bin") || arg.startsWith("/root") || arg.startsWith("/home")) {
            throw new SecurityException("Access to system path '" + arg + "' is denied. Commands are confined to workspace.");
        }
        if ((binary.equals("rm") || binary.equals("rmdir")) && (arg.equals(".") || arg.equals("./") || arg.equals("*"))) {
            throw new SecurityException("Deleting the workspace root itself is forbidden.");
        }
        Path resolved = safeResolve(realRoot, arg);
        if (!resolved.startsWith(realRoot)) {
            throw new SecurityException("Path '" + arg + "' resolves outside the workspace.");
        }
    }

    private Path safeResolve(Path realRoot, String rel) throws Exception {
        Path target = realRoot.resolve(rel).normalize();
        Path probe = target;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null && !probe.toRealPath().startsWith(realRoot)) {
            throw new SecurityException("Symlink/path resolves outside workspace");
        }
        return target;
    }

    public static List<String> tokenize(String cmd) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens;
    }
}
