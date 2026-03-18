package com.heapanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs Eclipse Memory Analyzer (MAT) headlessly via ParseHeapDump.sh.
 *
 * <p>Generates both Leak Suspects and Overview reports, then extracts and
 * structures the most actionable data within a token budget suitable for LLM analysis.</p>
 *
 * <p>Token budget allocation (~15K chars total):
 * <ul>
 *   <li>Leak Suspects summary: ~4,000 chars</li>
 *   <li>Per-suspect detail (top 3): ~3,000 chars</li>
 *   <li>Top 20 class histogram: ~2,000 chars</li>
 *   <li>Top 10 packages retained: ~1,500 chars</li>
 *   <li>System properties (filtered): ~500 chars</li>
 *   <li>Thread overview: ~500 chars</li>
 * </ul>
 */
@Service
public class MatAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MatAnalysisService.class);

    /** Total char budget for the combined report sent to the LLM */
    private static final int MAX_REPORT_CHARS = 15_000;

    /** Budget per section to keep things balanced */
    private static final int SUSPECTS_SUMMARY_BUDGET = 4_000;
    private static final int SUSPECTS_DETAIL_BUDGET = 3_000;
    private static final int CLASS_HISTOGRAM_BUDGET = 2_000;
    private static final int TOP_PACKAGES_BUDGET = 1_500;
    private static final int SYSTEM_PROPS_BUDGET = 500;
    private static final int THREAD_OVERVIEW_BUDGET = 500;

    private final String matHome;
    private final long timeoutMinutes;

    public MatAnalysisService(
            @Value("${app.mat.home:/opt/mat}") String matHome,
            @Value("${app.mat.timeout-minutes:30}") long timeoutMinutes) {
        this.matHome = matHome;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Runs Eclipse MAT on the given heap dump file and returns a structured
     * analysis report combining Leak Suspects + Overview data.
     */
    public String analyze(Path heapDumpPath) throws IOException, InterruptedException {
        Path parseScript = Paths.get(matHome, "ParseHeapDump.sh");

        if (!Files.isExecutable(parseScript)) {
            throw new IOException("MAT ParseHeapDump.sh not found or not executable at: " + parseScript
                    + ". Make sure Eclipse MAT is installed at " + matHome);
        }

        log.info("Starting MAT analysis on {} (timeout: {} min)", heapDumpPath, timeoutMinutes);

        // Run both Leak Suspects and Overview reports in a single MAT invocation
        ProcessBuilder pb = new ProcessBuilder(
                parseScript.toString(),
                heapDumpPath.toAbsolutePath().toString(),
                "org.eclipse.mat.api:suspects",
                "org.eclipse.mat.api:overview"
        );
        pb.directory(heapDumpPath.getParent().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        String processOutput;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            processOutput = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("MAT analysis timed out after " + timeoutMinutes + " minutes");
        }

        int exitCode = process.exitValue();
        log.info("MAT process exited with code {}. Output:\n{}", exitCode, processOutput);

        if (exitCode != 0) {
            throw new RuntimeException("MAT analysis failed (exit code " + exitCode + "):\n" + processOutput);
        }

        return buildStructuredReport(heapDumpPath.getParent());
    }

    // ========================== Report Builder ==========================

    /**
     * Combines data from both report ZIPs into a single structured report.
     */
    private String buildStructuredReport(Path directory) throws IOException {
        StringBuilder report = new StringBuilder();

        report.append("═══════════════════════════════════════════════════════════\n");
        report.append("  ECLIPSE MAT — STRUCTURED HEAP DUMP ANALYSIS\n");
        report.append("═══════════════════════════════════════════════════════════\n\n");

        // Section 1: Leak Suspects Summary
        String suspectsSummary = extractLeakSuspectsSummary(directory);
        if (!suspectsSummary.isEmpty()) {
            report.append("━━━ SECTION 1: LEAK SUSPECTS ━━━\n");
            report.append(truncate(suspectsSummary, SUSPECTS_SUMMARY_BUDGET));
            report.append("\n\n");
        }

        // Section 2: Suspect Detail Pages (accumulation points, GC root paths)
        String suspectsDetail = extractLeakSuspectsDetail(directory);
        if (!suspectsDetail.isEmpty()) {
            report.append("━━━ SECTION 2: SUSPECT DETAILS (accumulation points, paths to GC roots) ━━━\n");
            report.append(truncate(suspectsDetail, SUSPECTS_DETAIL_BUDGET));
            report.append("\n\n");
        }

        // Section 3: Class Histogram (Top N by retained heap)
        String histogram = extractClassHistogram(directory);
        if (!histogram.isEmpty()) {
            report.append("━━━ SECTION 3: TOP CLASSES BY RETAINED HEAP ━━━\n");
            report.append(truncate(histogram, CLASS_HISTOGRAM_BUDGET));
            report.append("\n\n");
        }

        // Section 4: Top Packages / Components
        String packages = extractTopPackages(directory);
        if (!packages.isEmpty()) {
            report.append("━━━ SECTION 4: TOP PACKAGES BY RETAINED HEAP ━━━\n");
            report.append(truncate(packages, TOP_PACKAGES_BUDGET));
            report.append("\n\n");
        }

        // Section 5: System Properties (JVM flags)
        String sysProps = extractSystemProperties(directory);
        if (!sysProps.isEmpty()) {
            report.append("━━━ SECTION 5: JVM SYSTEM PROPERTIES ━━━\n");
            report.append(truncate(sysProps, SYSTEM_PROPS_BUDGET));
            report.append("\n\n");
        }

        // Section 6: Thread Overview
        String threads = extractThreadOverview(directory);
        if (!threads.isEmpty()) {
            report.append("━━━ SECTION 6: THREAD OVERVIEW ━━━\n");
            report.append(truncate(threads, THREAD_OVERVIEW_BUDGET));
            report.append("\n");
        }

        if (report.length() < 100) {
            throw new IOException("No MAT report data found in " + directory);
        }

        String result = report.toString();
        if (result.length() > MAX_REPORT_CHARS) {
            result = result.substring(0, MAX_REPORT_CHARS)
                    + "\n\n[... REPORT TRUNCATED at " + MAX_REPORT_CHARS + " chars ...]";
        }

        log.info("Built structured report: {} chars ({} sections)",
                result.length(), countSections(result));
        return result;
    }

    // ========================== Leak Suspects ==========================

    /** Extracts the main summary page from the Leak Suspects ZIP. */
    private String extractLeakSuspectsSummary(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_Leak_Suspects.zip");
        if (zipPath == null) return "";

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry index = findEntry(zip, "index.html");
            if (index == null) return "";
            return htmlToStructuredText(readEntry(zip, index));
        }
    }

    /** Extracts detail pages for each suspect (accumulation points, paths to GC roots). */
    private String extractLeakSuspectsDetail(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_Leak_Suspects.zip");
        if (zipPath == null) return "";

        StringBuilder details = new StringBuilder();
        int suspectCount = 0;
        int maxSuspects = 3; // Only top 3 suspects to save tokens

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // Detail pages are typically named like "1.html", "2.html", etc.
            // or "pages/1.html" in some MAT versions
            List<? extends ZipEntry> detailPages = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().matches("(?:pages/)?\\d+\\.html"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .collect(Collectors.toList());

            for (ZipEntry page : detailPages) {
                if (suspectCount >= maxSuspects) break;
                suspectCount++;

                String text = htmlToStructuredText(readEntry(zip, page));
                details.append("--- Suspect #").append(suspectCount).append(" Detail ---\n");
                details.append(text).append("\n\n");
            }
        }

        return details.toString();
    }

    // ========================== Overview Report ==========================

    /** Extracts class histogram data from the Overview ZIP. */
    private String extractClassHistogram(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_System_Overview.zip");
        if (zipPath == null) return "";

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // Look for histogram page
            ZipEntry entry = findEntryContaining(zip, "histogram");
            if (entry == null) entry = findEntryContaining(zip, "class_statistics");
            if (entry == null) {
                // Fallback: try the index which often has top consumers
                entry = findEntry(zip, "index.html");
            }
            if (entry == null) return "";

            String text = htmlToStructuredText(readEntry(zip, entry));
            return filterTopLines(text, 25); // Top 25 lines for class data
        }
    }

    /** Extracts top packages/components by retained heap from the Overview ZIP. */
    private String extractTopPackages(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_System_Overview.zip");
        if (zipPath == null) return "";

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = findEntryContaining(zip, "top_component");
            if (entry == null) entry = findEntryContaining(zip, "package");
            if (entry == null) entry = findEntryContaining(zip, "biggest_objects");
            if (entry == null) return "";

            String text = htmlToStructuredText(readEntry(zip, entry));
            return filterTopLines(text, 15);
        }
    }

    /** Extracts JVM system properties (heap flags, GC type, etc.) from the Overview ZIP. */
    private String extractSystemProperties(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_System_Overview.zip");
        if (zipPath == null) return "";

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = findEntryContaining(zip, "system_properties");
            if (entry == null) entry = findEntryContaining(zip, "environment");
            if (entry == null) return "";

            String text = htmlToStructuredText(readEntry(zip, entry));
            return filterJvmProperties(text);
        }
    }

    /** Extracts thread overview (count and states) from the Overview ZIP. */
    private String extractThreadOverview(Path directory) throws IOException {
        Path zipPath = findZip(directory, "*_System_Overview.zip");
        if (zipPath == null) return "";

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = findEntryContaining(zip, "thread");
            if (entry == null) return "";

            String text = htmlToStructuredText(readEntry(zip, entry));
            return filterTopLines(text, 15);
        }
    }

    // ========================== ZIP Utilities ==========================

    private Path findZip(Path directory, String glob) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path p : stream) return p;
        }
        return null;
    }

    private ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry != null) return entry;
        // Try with subdirectory
        return zip.stream()
                .filter(e -> e.getName().endsWith("/" + name) && !e.isDirectory())
                .findFirst()
                .orElse(null);
    }

    private ZipEntry findEntryContaining(ZipFile zip, String keyword) {
        return zip.stream()
                .filter(e -> !e.isDirectory() && e.getName().endsWith(".html"))
                .filter(e -> e.getName().toLowerCase().contains(keyword.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    private String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (var is = zip.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ========================== Text Processing ==========================

    /**
     * Converts HTML to clean structured text, preserving table structure
     * and removing noise. Better than raw strip for MAT reports.
     */
    private String htmlToStructuredText(String html) {
        return html
                .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                // Preserve table structure
                .replaceAll("</th>\\s*<th", "  |  </th><th")
                .replaceAll("</td>\\s*<td", "  |  </td><td")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("</tr>", "\n")
                .replaceAll("</li>", "\n")
                .replaceAll("</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", " ")
                // HTML entities
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", "")
                // Clean whitespace
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n[ \\t]+", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * Keeps only the first N non-empty lines of text.
     * Used to limit histogram / package data to top entries.
     */
    private String filterTopLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                sb.append(line.trim()).append("\n");
                count++;
                if (count >= maxLines) break;
            }
        }
        return sb.toString();
    }

    /**
     * Filters system properties to only JVM-relevant settings.
     * Removes noise like file paths, locale, etc.
     */
    private String filterJvmProperties(String text) {
        Set<String> relevantKeys = Set.of(
                "sun.java.command",
                "java.vm.name", "java.version", "java.runtime.version",
                "sun.arch.data.model",
                "os.name", "os.arch"
        );

        // Also match anything with Xmx, Xms, GC, heap, memory
        Pattern jvmFlag = Pattern.compile(
                "(?i).*(Xmx|Xms|Xss|MaxMetaspace|GC|heap|NewRatio|SurvivorRatio|" +
                        "MaxRAM|InitialRAM|CompressedOops|UseG1|UseZGC|UseShenandoah|" +
                        "UseCMS|UseParallel|MaxGCPause|GCTimeRatio|PrintGC).*");

        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            // Check if line contains a relevant property
            boolean relevant = relevantKeys.stream().anyMatch(k -> trimmed.contains(k));
            if (!relevant) {
                Matcher m = jvmFlag.matcher(trimmed);
                relevant = m.matches();
            }
            if (!relevant && trimmed.startsWith("-")) {
                relevant = true; // JVM flags start with -
            }

            if (relevant) {
                sb.append(trimmed).append("\n");
            }
        }

        return sb.isEmpty() ? text.lines().limit(10).collect(Collectors.joining("\n")) : sb.toString();
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... truncated ...]";
    }

    private int countSections(String text) {
        return (int) text.lines().filter(l -> l.startsWith("━━━")).count();
    }
}
