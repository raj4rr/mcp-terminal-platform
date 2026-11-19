package dev.raj.mcpserver;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class TerminalMcpServer {
    private static final int MAX_READ = 256 * 1024;
    private static final Set<String> ALLOWED = Set.of("pwd","date","whoami","uname",
        "java -version","git status","git log -1 --oneline","ls -la");

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(System.getenv().getOrDefault("MCP_WORKSPACE", "./workspace"))
            .toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        var server = McpServer.sync(transport)
            .serverInfo("terminal-mcp-server", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .toolCall(Tool.builder("list_files", schema("path", "Relative directory; . means workspace root"))
                .description("List entries in the configured workspace").build(),
                (exchange, request) -> safeCall(() -> listFiles(workspace, arg(request.arguments(), "path", "."))))
            .toolCall(Tool.builder("read_file", schema("path", "Relative UTF-8 text file path"))
                .description("Read a UTF-8 text file inside workspace, max 256 KB").build(),
                (exchange, request) -> safeCall(() -> readFile(workspace, arg(request.arguments(), "path", ""))))
            .toolCall(Tool.builder("system_info", emptySchema())
                .description("Return OS, Java version, and workspace path").build(),
                (exchange, request) -> safeCall(() -> systemInfo(workspace)))
            .toolCall(Tool.builder("run_command", schema("command", "Exact command from fixed allowlist"))
                .description("Run an allowlisted diagnostic command; no shell or arbitrary commands").build(),
                (exchange, request) -> safeCall(() -> runCommand(workspace, arg(request.arguments(), "command", ""))))
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.err.println("Terminal MCP server ready; workspace=" + workspace);
        Thread.currentThread().join();
    }

    private static Map<String,Object> schema(String key, String description) {
        return Map.of("type","object","properties",Map.of(key,Map.of("type","string","description",description)),
            "required",List.of(key),"additionalProperties",false);
    }
    private static Map<String,Object> emptySchema() {
        return Map.of("type","object","properties",Map.of(),"additionalProperties",false);
    }
    private static String arg(Map<String,Object> args,String key,String fallback) {
        Object v = args == null ? null : args.get(key); return v == null ? fallback : v.toString();
    }
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static CallToolResult safeCall(ThrowingSupplier<String> supplier) {
        try {
            return text(supplier.get());
        } catch (Exception e) {
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                .isError(true)
                .build();
        }
    }

    private static CallToolResult text(String s) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent(s))).build();
    }
    private static Path safePath(Path workspace,String relative) throws Exception {
        Path root = workspace.toRealPath();
        Path target = root.resolve(relative.isBlank()?".":relative).normalize();
        if (!target.startsWith(root)) throw new SecurityException("Path escapes workspace");
        Path probe = target;
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe == null || !probe.toRealPath().startsWith(root))
            throw new SecurityException("Symlink/path resolves outside workspace");
        return target;
    }
    private static String listFiles(Path root,String rel) throws Exception {
        Path dir = safePath(root,rel);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory");
        try (var stream=Files.list(dir)) {
            return stream.sorted().map(p -> (Files.isDirectory(p)?"[DIR]  ":"[FILE] ")+p.getFileName())
                .collect(Collectors.joining("\n"));
        }
    }
    private static String readFile(Path root,String rel) throws Exception {
        if (rel.isBlank()) throw new IllegalArgumentException("path is required");
        Path f=safePath(root,rel);
        if (!Files.isRegularFile(f)) throw new IllegalArgumentException("Not a regular file");
        if (Files.size(f)>MAX_READ) throw new IllegalArgumentException("File exceeds 256 KB");
        return Files.readString(f, StandardCharsets.UTF_8);
    }
    private static String systemInfo(Path root) {
        return "OS: "+System.getProperty("os.name")+" "+System.getProperty("os.version")
            +"\nJava: "+System.getProperty("java.version")+"\nWorkspace: "+root;
    }
    private static String runCommand(Path root,String command) throws Exception {
        if (!ALLOWED.contains(command)) throw new SecurityException("Command is not allowlisted");
        List<String> args = switch(command) {
            case "pwd" -> List.of("pwd");
            case "date" -> List.of("date");
            case "whoami" -> List.of("whoami");
            case "uname" -> List.of("uname","-a");
            case "java -version" -> List.of("java","-version");
            case "git status" -> List.of("git","-C",root.toString(),"status","--short","--branch");
            case "git log -1 --oneline" -> List.of("git","-C",root.toString(),"log","-1","--oneline");
            case "ls -la" -> List.of("ls","-la",root.toString());
            default -> throw new SecurityException("Command is not allowlisted");
        };
        Process p = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
        if (!p.waitFor(8,TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("Command timeout"); }
        return "exitCode="+p.exitValue()+"\n"+new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
    }
}
