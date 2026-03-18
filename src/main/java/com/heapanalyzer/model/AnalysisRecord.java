package com.heapanalyzer.model;

import java.time.Instant;

/**
 * Lightweight record stored in the history JSON file.
 * Contains the analysis metadata and results, but no volatile fields.
 */
public class AnalysisRecord {

    private String id;
    private String fileName;
    private AnalysisType analysisType;
    private String status;
    private String staticReport;
    private String aiResponse;
    private String errorMessage;
    private String createdAt;
    private long fileSizeBytes;
    private String savedAt;

    public AnalysisRecord() {}

    /** Create from a live AnalysisState. */
    public static AnalysisRecord from(AnalysisState state) {
        AnalysisRecord r = new AnalysisRecord();
        r.id = state.getId();
        r.fileName = state.getFileName();
        r.analysisType = state.getAnalysisType();
        r.status = state.getStatus().name();
        r.staticReport = state.getStaticReport();
        r.aiResponse = state.getAiResponse();
        r.errorMessage = state.getErrorMessage();
        r.createdAt = state.getCreatedAt().toString();
        r.fileSizeBytes = state.getFileSizeBytes();
        r.savedAt = Instant.now().toString();
        return r;
    }

    // --- Getters & Setters (needed for JSON serialization) ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public AnalysisType getAnalysisType() { return analysisType; }
    public void setAnalysisType(AnalysisType analysisType) { this.analysisType = analysisType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStaticReport() { return staticReport; }
    public void setStaticReport(String staticReport) { this.staticReport = staticReport; }

    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getSavedAt() { return savedAt; }
    public void setSavedAt(String savedAt) { this.savedAt = savedAt; }
}
