package com.heapanalyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcLogAnalysisServiceTest {

    private final GcLogAnalysisService service = new GcLogAnalysisService();

    @TempDir
    Path tempDir;

    @Test
    void testAnalyze() throws IOException {
        Path gcLog = tempDir.resolve("gc.log");
        Files.writeString(gcLog, """
                [0.010s][info][gc] Using G1
                [0.011s][info][gc] Periodic GC disabled
                [0.012s][info][gc] Heap region size: 1M
                [0.020s][info][gc] Pause Young (G1 Evacuation Pause) 512M->256M(1024M) 12.3ms
                [0.030s][info][gc] Pause Full (G1 Evacuation Pause) 1024M->512M(1024M) 100.5ms
                """);

        String report = service.analyze(gcLog);
        assertNotNull(report);
        assertTrue(report.contains("G1 Garbage Collector (G1GC)"));
        assertTrue(report.contains("Total GC events: 2"));
        assertTrue(report.contains("Average pause:    56.40 ms"));
        assertTrue(report.contains("Max heap used:       1.00 GB"));
    }
}
