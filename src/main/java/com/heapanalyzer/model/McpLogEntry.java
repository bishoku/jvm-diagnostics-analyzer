package com.heapanalyzer.model;

import java.time.Instant;

/**
 * Represents a single JSON-RPC request or response intercepted during an MCP session.
 */
public record McpLogEntry(
        String id,
        String type, // "REQUEST" or "RESPONSE"
        Instant timestamp,
        String payload,
        Long durationMs // only populated for responses
) {}
