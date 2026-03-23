package com.heapanalyzer.service;

import com.heapanalyzer.model.HeapDumpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages heap dump sessions for MCP interactive querying.
 *
 * <p>Unlike the regular analysis pipeline (which cleans up files after AI processing),
 * MCP sessions keep heap dumps and MAT index files alive so that tools can
 * run queries on-demand. Sessions expire after a configurable timeout.</p>
 */
@Service
public class McpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(McpSessionManager.class);

    private final Map<String, HeapDumpSession> sessions = new ConcurrentHashMap<>();
    private final MatAnalysisService matAnalysisService;
    private final long sessionTimeoutMinutes;

    /** Only one active session at a time for simplicity. */
    private volatile String activeSessionId;

    public McpSessionManager(
            MatAnalysisService matAnalysisService,
            @Value("${app.mcp.session-timeout-minutes:120}") long sessionTimeoutMinutes) {
        this.matAnalysisService = matAnalysisService;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    /**
     * Creates a new MCP session and triggers MAT parsing in the background.
     * Closes any existing active session first.
     */
    public HeapDumpSession createSession(String sessionId, String fileName, Path hprofPath, long fileSizeBytes) {
        // Close previous active session
        if (activeSessionId != null) {
            closeSession(activeSessionId);
        }

        HeapDumpSession session = new HeapDumpSession(sessionId, fileName, hprofPath, fileSizeBytes);
        sessions.put(sessionId, session);
        activeSessionId = sessionId;

        log.info("[MCP] Created session {} for {} ({} bytes)", sessionId, fileName, fileSizeBytes);

        // Parse in background (builds MAT index files for subsequent queries)
        Thread.startVirtualThread(() -> parseHeapDump(session));

        return session;
    }

    /**
     * Returns the currently active session, or null if none.
     */
    public HeapDumpSession getActiveSession() {
        if (activeSessionId == null) return null;
        HeapDumpSession session = sessions.get(activeSessionId);
        if (session != null) {
            session.touch();
        }
        return session;
    }

    /**
     * Returns a session by ID, or null if not found.
     */
    public HeapDumpSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Returns true if there is an active, fully parsed session.
     */
    public boolean hasActiveSession() {
        HeapDumpSession session = getActiveSession();
        return session != null && session.isParsed();
    }

    /**
     * Closes a session and cleans up all associated files (hprof + MAT index).
     */
    public void closeSession(String sessionId) {
        HeapDumpSession session = sessions.remove(sessionId);
        if (session == null) return;

        if (sessionId.equals(activeSessionId)) {
            activeSessionId = null;
        }

        log.info("[MCP] Closing session {} ({})", sessionId, session.getFileName());
        cleanupSessionFiles(session);
    }

    /**
     * Returns session status information for the MCP status page.
     */
    public Map<String, Object> getStatusInfo() {
        HeapDumpSession session = getActiveSession();
        if (session == null) {
            return Map.of(
                    "active", false,
                    "sessionTimeoutMinutes", sessionTimeoutMinutes
            );
        }

        return Map.of(
                "active", true,
                "sessionId", session.getSessionId(),
                "fileName", session.getFileName(),
                "fileSizeBytes", session.getFileSizeBytes(),
                "parsed", session.isParsed(),
                "parseError", session.getParseError() != null ? session.getParseError() : "",
                "createdAt", session.getCreatedAt().toString(),
                "lastAccessedAt", session.getLastAccessedAt().toString(),
                "sessionTimeoutMinutes", sessionTimeoutMinutes
        );
    }

    /**
     * Periodically checks for expired sessions and cleans them up.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(sessionTimeoutMinutes));
        sessions.values().stream()
                .filter(s -> s.getLastAccessedAt().isBefore(cutoff))
                .forEach(s -> {
                    log.info("[MCP] Session {} expired (idle since {})", s.getSessionId(), s.getLastAccessedAt());
                    closeSession(s.getSessionId());
                });
    }

    // ========================== Internal ==========================

    private void parseHeapDump(HeapDumpSession session) {
        try {
            log.info("[MCP] Parsing heap dump for session {}: {}", session.getSessionId(), session.getHprofPath());

            // Running MAT analysis builds the index files we need for queries
            matAnalysisService.analyze(session.getHprofPath());

            session.setParsed(true);
            log.info("[MCP] Heap dump parsed successfully for session {}", session.getSessionId());

        } catch (Exception e) {
            session.setParseError(e.getMessage());
            log.error("[MCP] Failed to parse heap dump for session {}", session.getSessionId(), e);
        }
    }

    private void cleanupSessionFiles(HeapDumpSession session) {
        try {
            Path hprofPath = session.getHprofPath();
            Path directory = hprofPath.getParent();
            String baseName = getBaseName(hprofPath.getFileName().toString());

            // Delete the hprof file and all MAT-generated files (index, threads, ZIPs, etc.)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                    entry -> entry.getFileName().toString().startsWith(baseName))) {
                for (Path file : stream) {
                    try {
                        Files.deleteIfExists(file);
                        log.debug("[MCP] Deleted: {}", file.getFileName());
                    } catch (IOException e) {
                        log.warn("[MCP] Could not delete: {}", file.getFileName());
                    }
                }
            }

            // Remove empty directory
            try (DirectoryStream<Path> remaining = Files.newDirectoryStream(directory)) {
                if (!remaining.iterator().hasNext()) {
                    Files.deleteIfExists(directory);
                }
            }

            log.info("[MCP] Session files cleaned up for {}", session.getSessionId());

        } catch (IOException e) {
            log.warn("[MCP] Error during session cleanup: {}", e.getMessage());
        }
    }

    private String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
