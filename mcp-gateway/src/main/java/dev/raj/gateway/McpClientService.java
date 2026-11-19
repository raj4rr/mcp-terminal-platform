package dev.raj.gateway;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class McpClientService {
 private final McpSyncClient client;
 public McpClientService(@Value("${app.mcp-server-jar}") String jar,
                         @Value("${app.workspace}") String workspace) {
  try {
   Path jarPath=Path.of(jar).toAbsolutePath().normalize();
   ServerParameters params=ServerParameters.builder("java").args("-jar",jarPath.toString())
    .env(Map.of("MCP_WORKSPACE",Path.of(workspace).toAbsolutePath().normalize().toString())).build();
   var transport=new StdioClientTransport(params,McpJsonDefaults.getMapper());
   client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(12)).build();
   client.initialize();
   client.listTools();
  } catch(Exception e) { throw new IllegalStateException("Cannot start MCP server. Build its JAR first.",e); }
 }
 public List<Map<String,Object>> tools() {
  return client.listTools().tools().stream().map(t -> {
   Map<String,Object> m=new LinkedHashMap<>(); m.put("name",t.name());
   m.put("description",t.description()); return m;
  }).toList();
 }
 public String call(String name,Map<String,Object> args) {
  CallToolResult result=client.callTool(CallToolRequest.builder(name)
    .arguments(args==null?Map.of():args).build());
  return result.content().stream().filter(c->c instanceof TextContent)
    .map(c->((TextContent)c).text()).reduce((a,b)->a+"\n"+b).orElse("(empty result)");
 }
 @PreDestroy public void close() { try { client.closeGracefully(); } catch(Exception ignored) {} }
}
