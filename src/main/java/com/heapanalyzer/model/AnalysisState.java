package com.heapanalyzer.model;

import java.time.Instant;

/**
 * Holds the full state of a single heap dump analysis job.
 * This object is stored in the in-memory analysis registry.
 */
public class AnalysisState {

    private final String id;
    private final String fileName;
    private volatile AnalysisStatus status;
    private volatile String staticReport;
    private volatile String aiResponse;
    private volatile String errorMessage;
    private final Instant createdAt;
    private volatile long fileSizeBytes;

    public AnalysisState(String id, String fileName) {
        this.id = id;
        this.fileName = fileName;
        this.status = AnalysisStatus.UPLOADING;
        this.createdAt = Instant.now();
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public String getStaticReport() {
        return staticReport;
    }

    public String getAiResponse() {
        return aiResponse;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    // --- Setters ---

    public void setStatus(AnalysisStatus status) {
        this.status = status;
    }

    public void setStaticReport(String staticReport) {
        this.staticReport = staticReport;
    }

    public void setAiResponse(String aiResponse) {
        this.aiResponse = aiResponse;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }
}
