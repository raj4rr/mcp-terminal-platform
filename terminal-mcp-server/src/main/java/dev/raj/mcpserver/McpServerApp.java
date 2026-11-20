package dev.raj.mcpserver;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.file.Path;

import static dev.raj.mcpserver.McpToolUtils.*;

public class McpServerApp {
    private final WorkspaceService workspaceService;
    private final CommandService commandService;
    private final SystemInfoService systemInfoService;

    public McpServerApp(Path workspace) throws Exception {
        this.workspaceService = new WorkspaceService(workspace);
        this.commandService = new CommandService();
        this.systemInfoService = new SystemInfoService();
    }

    public void start() throws Exception {
        Path workspace = workspaceService.getRoot();
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("terminal-mcp-server", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .toolCall(Tool.builder("list_files", schema("path", "Relative directory; . means workspace root"))
                .description("List entries in the configured workspace").build(),
                (exchange, request) -> safeCall(() -> workspaceService.listFiles(arg(request.arguments(), "path", "."))))
            .toolCall(Tool.builder("read_file", schema("path", "Relative UTF-8 text file path"))
                .description("Read a UTF-8 text file inside workspace, max 256 KB").build(),
                (exchange, request) -> safeCall(() -> workspaceService.readFile(arg(request.arguments(), "path", ""))))
            .toolCall(Tool.builder("write_file", schema2("path", "Relative file path to create or edit", "content", "File text content"))
                .description("Create or edit a text file inside the workspace").build(),
                (exchange, request) -> safeCall(() -> workspaceService.writeFile(arg(request.arguments(), "path", ""), arg(request.arguments(), "content", ""))))
            .toolCall(Tool.builder("create_folder", schema("path", "Relative folder path to create"))
                .description("Create a directory in the workspace").build(),
                (exchange, request) -> safeCall(() -> workspaceService.createDirectory(arg(request.arguments(), "path", ""))))
            .toolCall(Tool.builder("delete_file", schema("path", "Relative file or directory path to delete"))
                .description("Delete a file or folder within the workspace").build(),
                (exchange, request) -> safeCall(() -> workspaceService.deletePath(arg(request.arguments(), "path", ""))))
            .toolCall(Tool.builder("system_info", emptySchema())
                .description("Return OS, Java version, and workspace path").build(),
                (exchange, request) -> safeCall(() -> systemInfoService.getSystemInfo(workspace)))
            .toolCall(Tool.builder("run_command", schema("command", "Command to execute within workspace (e.g. ls, mkdir, touch, cat, rm, pwd, git)"))
                .description("Run an allowlisted command in the workspace; no arbitrary shell injection").build(),
                (exchange, request) -> safeCall(() -> commandService.runCommand(workspace, arg(request.arguments(), "command", ""))))
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.err.println("Terminal MCP server ready; workspace=" + workspace);
        Thread.currentThread().join();
    }
}
