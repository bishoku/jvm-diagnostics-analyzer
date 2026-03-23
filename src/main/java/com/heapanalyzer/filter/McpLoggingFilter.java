package com.heapanalyzer.filter;

import com.heapanalyzer.model.HeapDumpSession;
import com.heapanalyzer.model.McpLogEntry;
import com.heapanalyzer.service.McpLogService;
import com.heapanalyzer.service.McpSessionManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filter to intercept MCP JSON-RPC messages traversing the HTTP transport.
 * Captures request payloads and their corresponding responses, wrapping them
 * into log entries broadcasted to the frontend via SSE.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class McpLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpLoggingFilter.class);
    private final McpSessionManager sessionManager;
    private final McpLogService logService;
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]+\"|\\d+)");

    public McpLoggingFilter(McpSessionManager sessionManager, McpLogService logService) {
        this.sessionManager = sessionManager;
        this.logService = logService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only intercept the MCP message endpoint (typically POST /mcp/message)
        return !request.getRequestURI().startsWith("/mcp/message");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 5 * 1024 * 1024);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        Instant start = Instant.now();

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            Instant end = Instant.now();
            long durationMs = java.time.Duration.between(start, end).toMillis();

            // At this point we can capture both request and response
            captureAndLog(requestWrapper, responseWrapper, start, durationMs);

            // Important: copy the cached response body back to the real response
            responseWrapper.copyBodyToResponse();
        }
    }

    private void captureAndLog(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, Instant timestamp, long durationMs) {
        HeapDumpSession session = sessionManager.getActiveSession();
        if (session == null) {
            return; // Nowhere to broadcast this log
        }
        String sessionId = session.getSessionId();

        try {
            byte[] reqBody = request.getContentAsByteArray();
            if (reqBody.length > 0) {
                String reqJson = new String(reqBody, StandardCharsets.UTF_8);
                String id = extractId(reqJson);
                McpLogEntry reqEntry = new McpLogEntry(id, "REQUEST", timestamp, reqJson, null);
                logService.addLog(sessionId, reqEntry);
            }

            byte[] resBody = response.getContentAsByteArray();
            if (resBody.length > 0) {
                String resJson = new String(resBody, StandardCharsets.UTF_8);
                String id = extractId(resJson);
                McpLogEntry resEntry = new McpLogEntry(id, "RESPONSE", Instant.now(), resJson, durationMs);
                logService.addLog(sessionId, resEntry);
            }
        } catch (Exception e) {
            log.warn("[MCP] Failed to parse and log JSON-RPC message: {}", e.getMessage());
        }
    }

    private String extractId(String json) {
        Matcher m = ID_PATTERN.matcher(json);
        if (m.find()) {
            return m.group(1).replace("\"", "");
        }
        return UUID.randomUUID().toString(); // Fallback for notifications lacking an ID
    }
}
