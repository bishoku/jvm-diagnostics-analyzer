package com.heapanalyzer.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnalysisRecord} and {@link AnalysisState}.
 */
class AnalysisRecordTest {

    @Test
    void from_shouldCopyAllFieldsFromState() {
        AnalysisState state = new AnalysisState("state-1", "heap.hprof", AnalysisType.HEAP_DUMP);
        state.setStatus(AnalysisStatus.COMPLETED);
        state.setStaticReport("Eclipse MAT report...");
        state.setAiResponse("AI analysis result...");
        state.setFileSizeBytes(1024 * 1024 * 500L); // 500 MB

        AnalysisRecord record = AnalysisRecord.from(state);

        assertEquals("state-1", record.getId());
        assertEquals("heap.hprof", record.getFileName());
        assertEquals(AnalysisType.HEAP_DUMP, record.getAnalysisType());
        assertEquals("COMPLETED", record.getStatus());
        assertEquals("Eclipse MAT report...", record.getStaticReport());
        assertEquals("AI analysis result...", record.getAiResponse());
        assertEquals(500 * 1024 * 1024L, record.getFileSizeBytes());
        assertNotNull(record.getCreatedAt());
        assertNotNull(record.getSavedAt());
        assertNull(record.getErrorMessage());
    }

    @Test
    void from_shouldPreserveErrorMessage() {
        AnalysisState state = new AnalysisState("err-1", "bad.hprof", AnalysisType.HEAP_DUMP);
        state.setStatus(AnalysisStatus.FAILED);
        state.setErrorMessage("MAT out of memory");

        AnalysisRecord record = AnalysisRecord.from(state);

        assertEquals("FAILED", record.getStatus());
        assertEquals("MAT out of memory", record.getErrorMessage());
    }

    @Test
    void from_threadDump_shouldPreserveType() {
        AnalysisState state = new AnalysisState("td-1", "threads.txt", AnalysisType.THREAD_DUMP);
        state.setStatus(AnalysisStatus.COMPLETED);

        AnalysisRecord record = AnalysisRecord.from(state);

        assertEquals(AnalysisType.THREAD_DUMP, record.getAnalysisType());
    }

    @Test
    void from_gcLog_shouldPreserveType() {
        AnalysisState state = new AnalysisState("gc-1", "gc.log", AnalysisType.GC_LOG);
        state.setStatus(AnalysisStatus.COMPLETED);

        AnalysisRecord record = AnalysisRecord.from(state);

        assertEquals(AnalysisType.GC_LOG, record.getAnalysisType());
    }

    @Test
    void savedAt_shouldBeSetToCurrentTime() {
        AnalysisState state = new AnalysisState("time-1", "test.hprof", AnalysisType.HEAP_DUMP);
        state.setStatus(AnalysisStatus.COMPLETED);

        Instant before = Instant.now();
        AnalysisRecord record = AnalysisRecord.from(state);
        Instant after = Instant.now();

        Instant savedAt = Instant.parse(record.getSavedAt());
        assertFalse(savedAt.isBefore(before));
        assertFalse(savedAt.isAfter(after));
    }

    @Test
    void defaultConstructor_shouldCreateEmptyRecord() {
        AnalysisRecord record = new AnalysisRecord();

        assertNull(record.getId());
        assertNull(record.getFileName());
        assertNull(record.getAnalysisType());
        assertNull(record.getStatus());
        assertNull(record.getStaticReport());
        assertNull(record.getAiResponse());
        assertNull(record.getErrorMessage());
        assertNull(record.getCreatedAt());
        assertNull(record.getSavedAt());
        assertEquals(0, record.getFileSizeBytes());
    }

    @Test
    void setters_shouldWork() {
        AnalysisRecord record = new AnalysisRecord();
        record.setId("set-1");
        record.setFileName("test.log");
        record.setAnalysisType(AnalysisType.GC_LOG);
        record.setStatus("COMPLETED");
        record.setStaticReport("report");
        record.setAiResponse("ai");
        record.setErrorMessage(null);
        record.setCreatedAt("2025-01-01T00:00:00Z");
        record.setSavedAt("2025-01-01T00:01:00Z");
        record.setFileSizeBytes(42);

        assertEquals("set-1", record.getId());
        assertEquals("test.log", record.getFileName());
        assertEquals(AnalysisType.GC_LOG, record.getAnalysisType());
        assertEquals("COMPLETED", record.getStatus());
        assertEquals("report", record.getStaticReport());
        assertEquals("ai", record.getAiResponse());
        assertNull(record.getErrorMessage());
        assertEquals("2025-01-01T00:00:00Z", record.getCreatedAt());
        assertEquals("2025-01-01T00:01:00Z", record.getSavedAt());
        assertEquals(42, record.getFileSizeBytes());
    }
}
