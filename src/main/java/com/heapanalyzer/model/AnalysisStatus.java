package com.heapanalyzer.model;

/**
 * Represents the lifecycle states of a heap dump analysis.
 */
public enum AnalysisStatus {
    UPLOADING,
    ANALYZING,
    AI_PROCESSING,
    COMPLETED,
    FAILED
}
