package dev.raj.mcpserver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CommandService {
    private static final Set<String> ALLOWED = Set.of(
        "pwd", "date", "whoami", "uname",
        "java -version", "git status", "git log -1 --oneline", "ls -la"
    );

    public String runCommand(Path root, String command) throws Exception {
        if (!ALLOWED.contains(command)) {
            throw new SecurityException("Command is not allowlisted");
        }
        List<String> args = switch (command) {
            case "pwd" -> List.of("pwd");
            case "date" -> List.of("date");
            case "whoami" -> List.of("whoami");
            case "uname" -> List.of("uname", "-a");
            case "java -version" -> List.of("java", "-version");
            case "git status" -> List.of("git", "-C", root.toString(), "status", "--short", "--branch");
            case "git log -1 --oneline" -> List.of("git", "-C", root.toString(), "log", "-1", "--oneline");
            case "ls -la" -> List.of("ls", "-la", root.toString());
            default -> throw new SecurityException("Command is not allowlisted");
        };

        Process p = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timeout");
        }
        return "exitCode=" + p.exitValue() + "\n" + new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
