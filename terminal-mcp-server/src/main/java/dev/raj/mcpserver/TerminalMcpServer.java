package dev.raj.mcpserver;

import java.nio.file.Path;

public final class TerminalMcpServer {
    private TerminalMcpServer() {}

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(System.getenv().getOrDefault("MCP_WORKSPACE", "./workspace"))
            .toAbsolutePath().normalize();
        new McpServerApp(workspace).start();
    }
}
