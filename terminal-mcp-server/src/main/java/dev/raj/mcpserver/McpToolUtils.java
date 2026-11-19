package dev.raj.mcpserver;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.List;
import java.util.Map;

public final class McpToolUtils {
    private McpToolUtils() {}

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public static Map<String, Object> schema(String key, String description) {
        return Map.of(
            "type", "object",
            "properties", Map.of(key, Map.of("type", "string", "description", description)),
            "required", List.of(key),
            "additionalProperties", false
        );
    }

    public static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    public static String arg(Map<String, Object> args, String key, String fallback) {
        Object v = args == null ? null : args.get(key);
        return v == null ? fallback : v.toString();
    }

    public static CallToolResult safeCall(ThrowingSupplier<String> supplier) {
        try {
            return text(supplier.get());
        } catch (Exception e) {
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                .isError(true)
                .build();
        }
    }

    public static CallToolResult text(String s) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent(s))).build();
    }
}
