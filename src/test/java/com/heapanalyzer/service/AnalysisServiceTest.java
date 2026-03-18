package com.heapanalyzer.service;

import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.model.AnalysisType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AnalysisService}.
 * MAT, thread dump, GC log, and AI services are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock private MatAnalysisService matAnalysisService;
    @Mock private ThreadDumpAnalysisService threadDumpAnalysisService;
    @Mock private GcLogAnalysisService gcLogAnalysisService;
    @Mock private SpringAiService springAiService;

    private AnalysisService analysisService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(
                matAnalysisService, threadDumpAnalysisService,
                gcLogAnalysisService, springAiService
        );
    }

    // ========================== Registry Tests ==========================

    @Test
    void createAnalysis_shouldRegisterAndReturnState() {
        AnalysisState state = analysisService.createAnalysis("test-1", "dump.hprof", AnalysisType.HEAP_DUMP);

        assertNotNull(state);
        assertEquals("test-1", state.getId());
        assertEquals("dump.hprof", state.getFileName());
        assertEquals(AnalysisType.HEAP_DUMP, state.getAnalysisType());
        assertEquals(AnalysisStatus.UPLOADING, state.getStatus());
    }

    @Test
    void getAnalysis_shouldReturnRegisteredState() {
        analysisService.createAnalysis("test-2", "threads.txt", AnalysisType.THREAD_DUMP);

        AnalysisState retrieved = analysisService.getAnalysis("test-2");

        assertNotNull(retrieved);
        assertEquals("threads.txt", retrieved.getFileName());
    }

    @Test
    void getAnalysis_shouldReturnNullForUnknownId() {
        assertNull(analysisService.getAnalysis("nonexistent"));
    }

    // ========================== Heap Dump Pipeline ==========================

    @Test
    void runHeapDumpAnalysis_success_shouldCompleteAndCleanup() throws Exception {
        // Arrange
        Path hprofFile = tempDir.resolve("test.hprof");
        Files.writeString(hprofFile, "fake hprof data");

        analysisService.createAnalysis("heap-1", "test.hprof", AnalysisType.HEAP_DUMP);

        when(matAnalysisService.analyze(any(Path.class)))
                .thenReturn("Leak Suspects Report: byte[] is leaking");
        when(springAiService.analyze(anyString()))
                .thenReturn("## Analysis\nMemory leak in byte[] allocations.");

        // Act
        analysisService.runHeapDumpAnalysis("heap-1", hprofFile);

        // Assert
        AnalysisState state = analysisService.getAnalysis("heap-1");
        assertEquals(AnalysisStatus.COMPLETED, state.getStatus());
        assertEquals("Leak Suspects Report: byte[] is leaking", state.getStaticReport());
        assertEquals("## Analysis\nMemory leak in byte[] allocations.", state.getAiResponse());
        assertNull(state.getErrorMessage());

        verify(matAnalysisService).analyze(hprofFile);
        verify(springAiService).analyze(anyString());

        // File should be cleaned up
        assertFalse(Files.exists(hprofFile), "Uploaded file should be deleted after analysis");
    }

    @Test
    void runHeapDumpAnalysis_matFailure_shouldFailAndCleanup() throws Exception {
        Path hprofFile = tempDir.resolve("bad.hprof");
        Files.writeString(hprofFile, "corrupt data");

        analysisService.createAnalysis("heap-2", "bad.hprof", AnalysisType.HEAP_DUMP);

        when(matAnalysisService.analyze(any(Path.class)))
                .thenThrow(new RuntimeException("MAT crashed: out of memory"));

        analysisService.runHeapDumpAnalysis("heap-2", hprofFile);

        AnalysisState state = analysisService.getAnalysis("heap-2");
        assertEquals(AnalysisStatus.FAILED, state.getStatus());
        assertEquals("MAT crashed: out of memory", state.getErrorMessage());
        assertNull(state.getAiResponse());

        // AI should not be called on MAT failure
        verify(springAiService, never()).analyze(anyString());

        // File should still be cleaned up
        assertFalse(Files.exists(hprofFile), "Uploaded file should be deleted even on failure");
    }

    @Test
    void runHeapDumpAnalysis_aiFailure_shouldFailWithStaticReport() throws Exception {
        Path hprofFile = tempDir.resolve("ok.hprof");
        Files.writeString(hprofFile, "good data");

        analysisService.createAnalysis("heap-3", "ok.hprof", AnalysisType.HEAP_DUMP);

        when(matAnalysisService.analyze(any(Path.class)))
                .thenReturn("MAT report content");
        when(springAiService.analyze(anyString()))
                .thenThrow(new RuntimeException("API rate limit exceeded"));

        analysisService.runHeapDumpAnalysis("heap-3", hprofFile);

        AnalysisState state = analysisService.getAnalysis("heap-3");
        assertEquals(AnalysisStatus.FAILED, state.getStatus());
        assertEquals("MAT report content", state.getStaticReport()); // Static report preserved
        assertEquals("API rate limit exceeded", state.getErrorMessage());
    }

    @Test
    void runHeapDumpAnalysis_unknownId_shouldNotThrow() {
        Path fakePath = tempDir.resolve("fake.hprof");
        // Should handle gracefully, not throw
        assertDoesNotThrow(() -> analysisService.runHeapDumpAnalysis("unknown-id", fakePath));
    }

    // ========================== Thread Dump Pipeline ==========================

    @Test
    void runThreadDumpAnalysis_success_shouldComplete() throws Exception {
        Path threadFile = tempDir.resolve("threads.txt");
        Files.writeString(threadFile, "\"main\" #1 prio=5 RUNNABLE");

        analysisService.createAnalysis("thread-1", "threads.txt", AnalysisType.THREAD_DUMP);

        when(threadDumpAnalysisService.analyze(any(Path.class)))
                .thenReturn("42 threads, 0 deadlocks");
        when(springAiService.analyzeThreadDump(anyString()))
                .thenReturn("## Thread Analysis\nNo deadlocks found.");

        analysisService.runThreadDumpAnalysis("thread-1", threadFile);

        AnalysisState state = analysisService.getAnalysis("thread-1");
        assertEquals(AnalysisStatus.COMPLETED, state.getStatus());
        assertEquals("42 threads, 0 deadlocks", state.getStaticReport());
        assertNotNull(state.getAiResponse());

        // File should be cleaned up
        assertFalse(Files.exists(threadFile));
    }

    @Test
    void runThreadDumpAnalysis_failure_shouldFail() throws Exception {
        Path threadFile = tempDir.resolve("bad_thread.txt");
        Files.writeString(threadFile, "garbage");

        analysisService.createAnalysis("thread-2", "bad_thread.txt", AnalysisType.THREAD_DUMP);

        when(threadDumpAnalysisService.analyze(any(Path.class)))
                .thenThrow(new IOException("Cannot parse thread dump"));

        analysisService.runThreadDumpAnalysis("thread-2", threadFile);

        AnalysisState state = analysisService.getAnalysis("thread-2");
        assertEquals(AnalysisStatus.FAILED, state.getStatus());
        assertEquals("Cannot parse thread dump", state.getErrorMessage());
    }

    // ========================== GC Log Pipeline ==========================

    @Test
    void runGcLogAnalysis_success_shouldComplete() throws Exception {
        Path gcFile = tempDir.resolve("gc.log");
        Files.writeString(gcFile, "[GC pause (G1 Evacuation Pause) 512M->256M(1024M) 12.3ms]");

        analysisService.createAnalysis("gc-1", "gc.log", AnalysisType.GC_LOG);

        when(gcLogAnalysisService.analyze(any(Path.class)))
                .thenReturn("G1GC, avg pause 12ms, no full GCs");
        when(springAiService.analyzeGcLog(anyString()))
                .thenReturn("## GC Analysis\nHealthy G1GC configuration.");

        analysisService.runGcLogAnalysis("gc-1", gcFile);

        AnalysisState state = analysisService.getAnalysis("gc-1");
        assertEquals(AnalysisStatus.COMPLETED, state.getStatus());
        assertEquals("G1GC, avg pause 12ms, no full GCs", state.getStaticReport());
        assertNotNull(state.getAiResponse());

        assertFalse(Files.exists(gcFile));
    }

    @Test
    void runGcLogAnalysis_failure_shouldFail() throws Exception {
        Path gcFile = tempDir.resolve("bad_gc.log");
        Files.writeString(gcFile, "not a gc log");

        analysisService.createAnalysis("gc-2", "bad_gc.log", AnalysisType.GC_LOG);

        when(gcLogAnalysisService.analyze(any(Path.class)))
                .thenThrow(new RuntimeException("Unrecognized GC format"));

        analysisService.runGcLogAnalysis("gc-2", gcFile);

        AnalysisState state = analysisService.getAnalysis("gc-2");
        assertEquals(AnalysisStatus.FAILED, state.getStatus());
    }

    // ========================== Cleanup Tests ==========================

    @Test
    void cleanup_shouldDeleteMatGeneratedArtifacts() throws Exception {
        // Simulate MAT-generated files alongside the .hprof
        Path subDir = tempDir.resolve("analysis");
        Files.createDirectories(subDir);

        Path hprofFile = subDir.resolve("myapp.hprof");
        Files.writeString(hprofFile, "hprof data");

        // MAT generates these files with the same base name
        Files.writeString(subDir.resolve("myapp.0001.index"), "index data");
        Files.writeString(subDir.resolve("myapp.dominator.index"), "dominator");
        Files.writeString(subDir.resolve("myapp.threads"), "threads");
        Files.writeString(subDir.resolve("myapp_Leak_Suspects.zip"), "zip data");
        Files.writeString(subDir.resolve("myapp_System_Overview.zip"), "zip data");

        analysisService.createAnalysis("cleanup-1", "myapp.hprof", AnalysisType.HEAP_DUMP);

        when(matAnalysisService.analyze(any(Path.class))).thenReturn("report");
        when(springAiService.analyze(anyString())).thenReturn("ai response");

        analysisService.runHeapDumpAnalysis("cleanup-1", hprofFile);

        // All files with baseName "myapp" should be deleted
        assertFalse(Files.exists(hprofFile));
        assertFalse(Files.exists(subDir.resolve("myapp.0001.index")));
        assertFalse(Files.exists(subDir.resolve("myapp.dominator.index")));
        assertFalse(Files.exists(subDir.resolve("myapp.threads")));
        assertFalse(Files.exists(subDir.resolve("myapp_Leak_Suspects.zip")));
        assertFalse(Files.exists(subDir.resolve("myapp_System_Overview.zip")));

        // Empty directory should also be removed
        assertFalse(Files.exists(subDir));
    }

    @Test
    void cleanup_shouldNotDeleteUnrelatedFiles() throws Exception {
        Path subDir = tempDir.resolve("shared");
        Files.createDirectories(subDir);

        Path hprofFile = subDir.resolve("myapp.hprof");
        Files.writeString(hprofFile, "hprof");

        // Unrelated file with different base name
        Path unrelatedFile = subDir.resolve("other_file.txt");
        Files.writeString(unrelatedFile, "keep me");

        analysisService.createAnalysis("cleanup-2", "myapp.hprof", AnalysisType.HEAP_DUMP);

        when(matAnalysisService.analyze(any(Path.class))).thenReturn("report");
        when(springAiService.analyze(anyString())).thenReturn("ai");

        analysisService.runHeapDumpAnalysis("cleanup-2", hprofFile);

        // Unrelated file should survive
        assertTrue(Files.exists(unrelatedFile));
        // Directory not empty, so it should survive too
        assertTrue(Files.exists(subDir));
    }

    // ========================== Status Transitions ==========================

    @Test
    void statusTransitions_heapDump_shouldFollowPipeline() throws Exception {
        Path hprofFile = tempDir.resolve("transition.hprof");
        Files.writeString(hprofFile, "data");

        analysisService.createAnalysis("trans-1", "transition.hprof", AnalysisType.HEAP_DUMP);

        // Track status transitions
        when(matAnalysisService.analyze(any(Path.class))).thenAnswer(invocation -> {
            // During MAT analysis, status should be ANALYZING
            assertEquals(AnalysisStatus.ANALYZING, analysisService.getAnalysis("trans-1").getStatus());
            return "report";
        });
        when(springAiService.analyze(anyString())).thenAnswer(invocation -> {
            // During AI processing, status should be AI_PROCESSING
            assertEquals(AnalysisStatus.AI_PROCESSING, analysisService.getAnalysis("trans-1").getStatus());
            return "ai result";
        });

        // Before: UPLOADING
        assertEquals(AnalysisStatus.UPLOADING, analysisService.getAnalysis("trans-1").getStatus());

        analysisService.runHeapDumpAnalysis("trans-1", hprofFile);

        // After: COMPLETED
        assertEquals(AnalysisStatus.COMPLETED, analysisService.getAnalysis("trans-1").getStatus());
    }
}
