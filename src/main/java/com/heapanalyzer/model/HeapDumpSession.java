package com.heapanalyzer.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Holds the state of an active MCP heap dump session.
 * Unlike regular analysis (which cleans up files), MCP sessions
 * keep the heap dump and MAT index files alive for interactive querying.
 */
public class HeapDumpSession {

    private final String sessionId;
    private final String fileName;
    private final Path hprofPath;
    private final Instant createdAt;
    private final long fileSizeBytes;
    private volatile Instant lastAccessedAt;
    private volatile boolean parsed;
    private volatile String parseError;

    public HeapDumpSession(String sessionId, String fileName, Path hprofPath, long fileSizeBytes) {
        this.sessionId = sessionId;
        this.fileName = fileName;
        this.hprofPath = hprofPath;
        this.fileSizeBytes = fileSizeBytes;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.parsed = false;
    }

    public String getSessionId() { return sessionId; }
    public String getFileName() { return fileName; }
    public Path getHprofPath() { return hprofPath; }
    public Instant getCreatedAt() { return createdAt; }
    public long getFileSizeBytes() { return fileSizeBytes; }

    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public void touch() { this.lastAccessedAt = Instant.now(); }

    public boolean isParsed() { return parsed; }
    public void setParsed(boolean parsed) { this.parsed = parsed; }

    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
}
