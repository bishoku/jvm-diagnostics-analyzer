package com.heapanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses JVM GC log files and extracts structured statistics
 * for AI consumption.
 *
 * <p>Supports Unified JVM Logging (JDK 9+) and legacy GC log formats.
 * Handles G1GC, ZGC, Shenandoah, CMS, and Parallel collector outputs.</p>
 */
@Service
public class GcLogAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(GcLogAnalysisService.class);

    /** Max characters to send to the LLM */
    private static final int MAX_REPORT_CHARS = 30_000;

    // Unified logging patterns (JDK 9+)
    private static final Pattern UNIFIED_PAUSE_PATTERN =
            Pattern.compile("Pause (Young|Full|Mixed|Initial Mark|Remark|Cleanup).*?(\\d+\\.\\d+)ms");

    private static final Pattern UNIFIED_HEAP_PATTERN =
            Pattern.compile("(\\d+)M->(\\d+)M\\((\\d+)M\\)");

    // Legacy patterns (JDK 8 and earlier)
    private static final Pattern LEGACY_GC_PATTERN =
            Pattern.compile("\\[GC.*?real=(\\d+\\.\\d+) secs\\]");

    private static final Pattern LEGACY_FULL_GC_PATTERN =
            Pattern.compile("\\[Full GC.*?real=(\\d+\\.\\d+) secs\\]");

    private static final Pattern HEAP_SIZE_PATTERN =
            Pattern.compile("(\\d+)K->(\\d+)K\\((\\d+)K\\)");

    /**
     * Parses the GC log file and produces a structured summary.
     */
    public String analyze(Path gcLogPath) throws IOException {
        String content = Files.readString(gcLogPath, StandardCharsets.UTF_8);
        log.info("Parsing GC log: {} ({} chars)", gcLogPath.getFileName(), content.length());

        StringBuilder report = new StringBuilder();
        report.append("GC Log Analysis Report\n");
        report.append("=".repeat(60)).append("\n\n");

        String[] lines = content.split("\n");

        // Detect GC collector type
        String collectorType = detectCollector(content);
        report.append("## GC Configuration\n\n");
        report.append(String.format("Detected Collector: %s\n", collectorType));
        report.append(String.format("Total log lines: %d\n\n", lines.length));

        // Parse GC events
        List<Double> pauseTimes = new ArrayList<>();
        List<Double> fullGcPauseTimes = new ArrayList<>();
        List<String> gcEventTypes = new ArrayList<>();
        int youngGcCount = 0;
        int fullGcCount = 0;
        int mixedGcCount = 0;
        long maxHeapUsedKB = 0;
        long maxHeapCapacityKB = 0;
        long minHeapAfterGcKB = Long.MAX_VALUE;

        for (String line : lines) {
            // Unified logging format
            Matcher unifiedPause = UNIFIED_PAUSE_PATTERN.matcher(line);
            if (unifiedPause.find()) {
                String type = unifiedPause.group(1);
                double pauseMs = Double.parseDouble(unifiedPause.group(2));
                pauseTimes.add(pauseMs);

                switch (type) {
                    case "Young" -> youngGcCount++;
                    case "Full" -> {
                        fullGcCount++;
                        fullGcPauseTimes.add(pauseMs);
                    }
                    case "Mixed" -> mixedGcCount++;
                }
                gcEventTypes.add(type);
            }

            // Legacy format
            Matcher legacyGc = LEGACY_GC_PATTERN.matcher(line);
            if (legacyGc.find() && !line.contains("Full GC")) {
                double pauseSecs = Double.parseDouble(legacyGc.group(1));
                pauseTimes.add(pauseSecs * 1000);
                youngGcCount++;
            }

            Matcher legacyFullGc = LEGACY_FULL_GC_PATTERN.matcher(line);
            if (legacyFullGc.find()) {
                double pauseSecs = Double.parseDouble(legacyFullGc.group(1));
                double pauseMs = pauseSecs * 1000;
                pauseTimes.add(pauseMs);
                fullGcPauseTimes.add(pauseMs);
                fullGcCount++;
            }

            // Heap size tracking (unified)
            Matcher unifiedHeap = UNIFIED_HEAP_PATTERN.matcher(line);
            while (unifiedHeap.find()) {
                long beforeMB = Long.parseLong(unifiedHeap.group(1));
                long afterMB = Long.parseLong(unifiedHeap.group(2));
                long capacityMB = Long.parseLong(unifiedHeap.group(3));
                maxHeapUsedKB = Math.max(maxHeapUsedKB, beforeMB * 1024);
                maxHeapCapacityKB = Math.max(maxHeapCapacityKB, capacityMB * 1024);
                minHeapAfterGcKB = Math.min(minHeapAfterGcKB, afterMB * 1024);
            }

            // Heap size tracking (legacy)
            Matcher heapSize = HEAP_SIZE_PATTERN.matcher(line);
            while (heapSize.find()) {
                long beforeKB = Long.parseLong(heapSize.group(1));
                long afterKB = Long.parseLong(heapSize.group(2));
                long capacityKB = Long.parseLong(heapSize.group(3));
                maxHeapUsedKB = Math.max(maxHeapUsedKB, beforeKB);
                maxHeapCapacityKB = Math.max(maxHeapCapacityKB, capacityKB);
                minHeapAfterGcKB = Math.min(minHeapAfterGcKB, afterKB);
            }
        }

        // GC Event Summary
        report.append("## GC Event Summary\n\n");
        int totalGcEvents = youngGcCount + fullGcCount + mixedGcCount;
        report.append(String.format("Total GC events: %d\n", totalGcEvents));
        report.append(String.format("  Young GC:  %d\n", youngGcCount));
        report.append(String.format("  Full GC:   %d\n", fullGcCount));
        if (mixedGcCount > 0) {
            report.append(String.format("  Mixed GC:  %d\n", mixedGcCount));
        }
        report.append("\n");

        // Pause Time Analysis
        if (!pauseTimes.isEmpty()) {
            report.append("## Pause Time Analysis\n\n");
            java.util.DoubleSummaryStatistics stats = pauseTimes.stream().mapToDouble(d -> d).summaryStatistics();
            double totalPause = stats.getSum();
            double avgPause = stats.getAverage();
            double maxPause = stats.getMax();
            double minPause = stats.getMin();

            long above200ms = 0;
            long above1000ms = 0;
            for (Double p : pauseTimes) {
                if (p > 200) above200ms++;
                if (p > 1000) above1000ms++;
            }

            // Sort for percentile calculation
            List<Double> sorted = new ArrayList<>(pauseTimes);
            sorted.sort(Double::compareTo);
            double p50 = sorted.get(sorted.size() / 2);
            double p95 = sorted.get((int) (sorted.size() * 0.95));
            double p99 = sorted.get((int) (sorted.size() * 0.99));

            report.append(String.format("Total pause time: %.2f ms (%.2f sec)\n", totalPause, totalPause / 1000));
            report.append(String.format("Average pause:    %.2f ms\n", avgPause));
            report.append(String.format("Min pause:        %.2f ms\n", minPause));
            report.append(String.format("Max pause:        %.2f ms\n", maxPause));
            report.append(String.format("P50 pause:        %.2f ms\n", p50));
            report.append(String.format("P95 pause:        %.2f ms\n", p95));
            report.append(String.format("P99 pause:        %.2f ms\n", p99));
            report.append(String.format("Pauses > 200ms:   %d\n", above200ms));
            report.append(String.format("Pauses > 1s:      %d\n", above1000ms));
            report.append("\n");

            // Warnings
            if (above1000ms > 0) {
                report.append("⚠️ WARNING: ").append(above1000ms)
                        .append(" pause(s) exceeded 1 second!\n\n");
            }
            if (fullGcCount > totalGcEvents * 0.1 && fullGcCount > 5) {
                report.append("⚠️ WARNING: High ratio of Full GC events (")
                        .append(String.format("%.1f%%", fullGcCount * 100.0 / totalGcEvents))
                        .append(") — possible memory pressure!\n\n");
            }
        }

        // Heap Usage
        if (maxHeapCapacityKB > 0) {
            report.append("## Heap Usage\n\n");
            report.append(String.format("Max heap used:       %s\n", formatKB(maxHeapUsedKB)));
            report.append(String.format("Max heap capacity:   %s\n", formatKB(maxHeapCapacityKB)));
            if (minHeapAfterGcKB < Long.MAX_VALUE) {
                report.append(String.format("Min heap after GC:   %s\n", formatKB(minHeapAfterGcKB)));
                double liveDataPct = (minHeapAfterGcKB * 100.0) / maxHeapCapacityKB;
                report.append(String.format("Estimated live data: %.1f%% of capacity\n", liveDataPct));
            }
            report.append("\n");
        }

        // Append raw content (truncated) for AI context
        report.append("## Raw GC Log (for detailed analysis)\n\n");
        report.append("```\n");

        String rawSection = content;
        int remainingChars = MAX_REPORT_CHARS - report.length() - 100;
        if (rawSection.length() > remainingChars && remainingChars > 0) {
            rawSection = rawSection.substring(0, remainingChars)
                    + "\n\n[... GC LOG TRUNCATED ...]";
        }
        report.append(rawSection);
        report.append("\n```\n");

        String result = report.toString();
        log.info("GC log analysis complete: {} events, max pause {:.2f}ms, {} chars",
                totalGcEvents,
                pauseTimes.isEmpty() ? 0 : pauseTimes.stream().mapToDouble(d -> d).max().orElse(0),
                result.length());
        return result;
    }

    /**
     * Detects the garbage collector type from the log content.
     */
    private String detectCollector(String content) {
        if (content.contains("Using G1") || content.contains("G1 Evacuation Pause")
                || content.contains("Pause Young (G1")) {
            return "G1 Garbage Collector (G1GC)";
        }
        if (content.contains("Using ZGC") || content.contains("ZGC")) {
            return "Z Garbage Collector (ZGC)";
        }
        if (content.contains("Using Shenandoah") || content.contains("Shenandoah")) {
            return "Shenandoah GC";
        }
        if (content.contains("CMS") || content.contains("Concurrent Mark Sweep")) {
            return "Concurrent Mark Sweep (CMS)";
        }
        if (content.contains("PSYoungGen") || content.contains("ParOldGen")) {
            return "Parallel GC";
        }
        if (content.contains("DefNew") || content.contains("Tenured")) {
            return "Serial GC";
        }
        return "Unknown (could not auto-detect)";
    }

    private String formatKB(long kb) {
        if (kb >= 1_048_576) {
            return String.format("%.2f GB", kb / 1_048_576.0);
        } else if (kb >= 1024) {
            return String.format("%.1f MB", kb / 1024.0);
        }
        return kb + " KB";
    }
}
