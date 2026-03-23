package com.heapanalyzer.config;

import com.heapanalyzer.mcp.HeapDumpMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers HeapDumpMcpTools methods as MCP tools via the Spring AI ToolCallbackProvider.
 * Without this registration, the annotation scanner won't discover @Tool methods.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider heapDumpToolCallbackProvider(HeapDumpMcpTools heapDumpMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(heapDumpMcpTools)
                .build();
    }
}
