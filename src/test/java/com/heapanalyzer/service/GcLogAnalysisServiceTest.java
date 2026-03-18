package com.heapanalyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GcLogAnalysisServiceTest {

    private final GcLogAnalysisService service = new GcLogAnalysisService();

    @TempDir
    Path tempDir;

    @Test
    void analyze_g1gcUnified_shouldParseCorrectly() throws IOException {
        Path path = new ClassPathResource("gc-logs/g1gc-unified.log").getFile().toPath();
        String report = service.analyze(path);

        assertTrue(report.contains("Detected Collector: G1 Garbage Collector (G1GC)"));
        assertTrue(report.contains("Total GC events: 4"));
        assertTrue(report.contains("Young GC:  2"));
        assertTrue(report.contains("Full GC:   1"));
        assertTrue(report.contains("Mixed GC:  1"));
        assertTrue(report.contains("Max heap capacity:   1.00 GB"));
        assertTrue(report.contains("Max heap used:       900.0 MB"));
        assertTrue(report.contains("Min heap after GC:   100.0 MB"));
        assertTrue(report.contains("Average pause:    113.00 ms"));
    }

    @Test
    void analyze_zgcUnified_shouldParseCorrectly() throws IOException {
        Path path = new ClassPathResource("gc-logs/zgc-unified.log").getFile().toPath();
        String report = service.analyze(path);

        assertTrue(report.contains("Detected Collector: Z Garbage Collector (ZGC)"));
        assertTrue(report.contains("Total GC events: 2"));
        assertTrue(report.contains("Max heap capacity:   2.00 GB"));
        assertTrue(report.contains("Average pause:    7.50 ms"));
    }

    @Test
    void analyze_legacyParallel_shouldParseCorrectly() throws IOException {
        Path path = new ClassPathResource("gc-logs/legacy-parallel.log").getFile().toPath();
        String report = service.analyze(path);

        assertTrue(report.contains("Detected Collector: Parallel GC"));
        assertTrue(report.contains("Total GC events: 2"));
        assertTrue(report.contains("Young GC:  1"));
        assertTrue(report.contains("Full GC:   1"));
        assertTrue(report.contains("Average pause:    65.00 ms")); // (10ms + 120ms) / 2
        assertTrue(report.contains("Max heap capacity:   1.92 GB")); // 2010112K
    }

    @Test
    void analyze_emptyLog_shouldHandleGracefully() throws IOException {
        Path path = tempDir.resolve("empty.log");
        Files.writeString(path, "");

        String report = service.analyze(path);

        assertTrue(report.contains("Detected Collector: Unknown"));
        assertTrue(report.contains("Total GC events: 0"));
        assertFalse(report.contains("Pause Time Analysis"));
        assertFalse(report.contains("Heap Usage"));
    }

    @Test
    void analyze_largeLog_shouldTruncateRawSection() throws IOException {
        Path path = tempDir.resolve("large.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("[0.150s][info][gc,heap     ] GC(0) 512M->256M(1024M)\n");
        }
        Files.writeString(path, sb.toString());

        String report = service.analyze(path);

        assertTrue(report.contains("[... GC LOG TRUNCATED ...]"));
        assertTrue(report.length() <= 31000); // 30_000 + some buffer for headers
    }

    @Test
    void analyze_longPauses_shouldShowWarnings() throws IOException {
        Path path = tempDir.resolve("warning.log");
        Files.writeString(path, "[1.0s][info][gc] Pause Young (G1 Evacuation Pause) 1200.0ms\n");

        String report = service.analyze(path);

        assertTrue(report.contains("⚠️ WARNING: 1 pause(s) exceeded 1 second!"));
    }

    @Test
    void analyze_highFullGcRatio_shouldShowWarnings() throws IOException {
        Path path = tempDir.resolve("fullgc-warning.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("[1.0s][info][gc] Pause Full (System.gc()) 100.0ms\n");
        }
        Files.writeString(path, sb.toString());

        String report = service.analyze(path);

        assertTrue(report.contains("⚠️ WARNING: High ratio of Full GC events"));
    }
}
