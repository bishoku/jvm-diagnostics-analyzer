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
 * Runs MAT headless commands against an already-parsed heap dump
 * and returns structured text results suitable for LLM consumption.
 *
 * <p>When MAT has already created index files (from the initial parse),
 * subsequent commands are fast (seconds, not minutes).</p>
 */
@Service
public class MatQueryService {

    private static final Logger log = LoggerFactory.getLogger(MatQueryService.class);

    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "^\\s*\\d+[.,].*|^.*\\|.*\\|.*$"
    );

    private static final Pattern JVM_FLAG_PATTERN = Pattern.compile(
            "(?i).*(Xmx|Xms|Xss|MaxMetaspace|GC|heap|NewRatio|SurvivorRatio|" +
                    "MaxRAM|InitialRAM|CompressedOops|UseG1|UseZGC|UseShenandoah|" +
                    "UseCMS|UseParallel|MaxGCPause|GCTimeRatio|PrintGC).*"
    );

    private final MatDownloadService matDownloadService;
    private final long queryTimeoutMinutes;

    public MatQueryService(
            MatDownloadService matDownloadService,
            @Value("${app.mat.timeout-minutes:30}") long queryTimeoutMinutes) {
        this.matDownloadService = matDownloadService;
        this.queryTimeoutMinutes = Math.min(queryTimeoutMinutes, 10); // Queries should be fast
    }

    // ========================== Public Query Methods ==========================

    /**
     * Returns heap overview: total heap, object count, class count, JVM info.
     */
    public String getHeapSummary(Path hprofPath) throws IOException, InterruptedException {
        // Extract from the System Overview ZIP that MAT already generated
        Path directory = hprofPath.getParent();
        StringBuilder result = new StringBuilder();

        result.append("=== HEAP DUMP SUMMARY ===\n\n");

        // Get heap size from index files or overview report
        Path overviewZip = findZip(directory, "*_System_Overview.zip");
        if (overviewZip != null) {
            try (ZipFile zip = new ZipFile(overviewZip.toFile())) {
                // Index page has heap size and overview stats
                ZipEntry index = findEntry(zip, "index.html");
                if (index != null) {
                    String text = htmlToText(readEntry(zip, index));
                    result.append(text).append("\n\n");
                }

                // System properties for JVM info
                ZipEntry sysprops = findEntryContaining(zip, "system_properties");
                if (sysprops == null) sysprops = findEntryContaining(zip, "environment");
                if (sysprops != null) {
                    String propsText = htmlToText(readEntry(zip, sysprops));
                    result.append("--- JVM System Properties ---\n");
                    result.append(filterJvmProperties(propsText));
                }
            }
        }

        // Fallback: run MAT command if no overview ZIP
        if (result.length() < 50) {
            String output = runMatCommand(hprofPath, "histogram");
            result.append("--- Class Histogram (top entries) ---\n");
            result.append(truncateLines(output, 15));
        }

        return truncate(result.toString(), 8000);
    }

    /**
     * Returns memory leak suspects with accumulation points and paths to GC roots.
     */
    public String getLeakSuspects(Path hprofPath) throws IOException, InterruptedException {
        Path directory = hprofPath.getParent();
        StringBuilder result = new StringBuilder();

        result.append("=== LEAK SUSPECTS ===\n\n");

        Path suspectsZip = findZip(directory, "*_Leak_Suspects.zip");
        if (suspectsZip != null) {
            try (ZipFile zip = new ZipFile(suspectsZip.toFile())) {
                // Main summary page
                ZipEntry index = findEntry(zip, "index.html");
                if (index != null) {
                    result.append(htmlToText(readEntry(zip, index)));
                    result.append("\n\n");
                }

                // Detail pages for each suspect (top 5)
                List<? extends ZipEntry> detailPages = zip.stream()
                        .filter(e -> !e.isDirectory())
                        .filter(e -> e.getName().matches("(?:pages/)?\\d+\\.html"))
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .limit(5)
                        .collect(Collectors.toList());

                int suspectNum = 0;
                for (ZipEntry page : detailPages) {
                    suspectNum++;
                    result.append("--- Suspect #").append(suspectNum).append(" Detail ---\n");
                    result.append(htmlToText(readEntry(zip, page)));
                    result.append("\n\n");
                }
            }
        } else {
            result.append("No leak suspects report found. Run a full heap analysis first.\n");
        }

        return truncate(result.toString(), 12000);
    }

    /**
     * Returns class histogram sorted by retained heap.
     *
     * @param topN    max classes to return (default 30)
     * @param pattern optional class name regex filter
     */
    public String getClassHistogram(Path hprofPath, int topN, String pattern)
            throws IOException, InterruptedException {

        String output = runMatCommand(hprofPath, "histogram");

        StringBuilder result = new StringBuilder();
        result.append("=== CLASS HISTOGRAM (Top ").append(topN).append(") ===\n\n");

        String[] lines = output.split("\n");
        int count = 0;
        Pattern filterPattern = (pattern != null && !pattern.isBlank())
                ? Pattern.compile(pattern, Pattern.CASE_INSENSITIVE) : null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Keep header lines
            if (count == 0 && (trimmed.contains("Class Name") || trimmed.contains("Shallow Heap")
                    || trimmed.startsWith("---") || trimmed.startsWith("==="))) {
                result.append(trimmed).append("\n");
                continue;
            }

            // Apply filter
            if (filterPattern != null && !filterPattern.matcher(trimmed).find()) {
                continue;
            }

            result.append(trimmed).append("\n");
            count++;
            if (count >= topN) break;
        }

        if (count == 0) {
            result.append("No matching classes found.\n");
        }

        return result.toString();
    }

    /**
     * Returns dominator tree — objects dominating the most retained memory.
     *
     * @param topN max dominator objects to return (default 20)
     */
    public String getDominatorTree(Path hprofPath, int topN)
            throws IOException, InterruptedException {

        String output = runMatCommand(hprofPath, "dominator_tree");

        StringBuilder result = new StringBuilder();
        result.append("=== DOMINATOR TREE (Top ").append(topN).append(") ===\n\n");
        result.append(truncateLines(output, topN + 5)); // +5 for headers

        return result.toString();
    }

    /**
     * Returns top memory consumers grouped by class, classloader, and package.
     */
    public String getTopConsumers(Path hprofPath) throws IOException, InterruptedException {
        Path directory = hprofPath.getParent();
        StringBuilder result = new StringBuilder();

        result.append("=== TOP MEMORY CONSUMERS ===\n\n");

        // Try to extract from System Overview ZIP first (has top_components)
        Path overviewZip = findZip(directory, "*_System_Overview.zip");
        if (overviewZip != null) {
            try (ZipFile zip = new ZipFile(overviewZip.toFile())) {
                ZipEntry entry = findEntryContaining(zip, "top_component");
                if (entry == null) entry = findEntryContaining(zip, "biggest_objects");
                if (entry == null) entry = findEntryContaining(zip, "package");

                if (entry != null) {
                    result.append(htmlToText(readEntry(zip, entry)));
                    return truncate(result.toString(), 8000);
                }
            }
        }

        // Fallback: run histogram and group
        String output = runMatCommand(hprofPath, "histogram");
        result.append(truncateLines(output, 30));

        return truncate(result.toString(), 8000);
    }

    /**
     * Executes an OQL query against the heap dump.
     *
     * @param query OQL query string (SQL-like syntax)
     * @return query results (max 100 rows)
     */
    public String runOqlQuery(Path hprofPath, String query)
            throws IOException, InterruptedException {

        // Sanitize query — prevent command injection
        if (query == null || query.isBlank()) {
            return "Error: OQL query cannot be empty.";
        }

        // Validate it looks like a SELECT query
        String trimmedQuery = query.trim();
        if (!trimmedQuery.toUpperCase().startsWith("SELECT")) {
            return "Error: OQL queries must start with SELECT. " +
                    "Example: SELECT * FROM java.lang.String WHERE @retainedHeapSize > 1000000";
        }

        String output = runMatOqlCommand(hprofPath, trimmedQuery);

        StringBuilder result = new StringBuilder();
        result.append("=== OQL QUERY RESULTS ===\n");
        result.append("Query: ").append(trimmedQuery).append("\n\n");

        // Limit to 100 result rows
        String[] lines = output.split("\n");
        int count = 0;
        for (String line : lines) {
            result.append(line).append("\n");
            if (!line.trim().isEmpty() && !line.startsWith("---") && !line.startsWith("===")) {
                count++;
            }
            if (count >= 100) {
                result.append("\n[... truncated at 100 results ...]\n");
                break;
            }
        }

        return result.toString();
    }

    /**
     * Returns thread stack traces with local variables and retained sizes.
     */
    public String getThreadStacks(Path hprofPath) throws IOException, InterruptedException {
        String output = runMatCommand(hprofPath, "thread_overview");

        StringBuilder result = new StringBuilder();
        result.append("=== THREAD STACKS ===\n\n");

        // Also check for thread detail in overview ZIP
        Path directory = hprofPath.getParent();
        Path overviewZip = findZip(directory, "*_System_Overview.zip");
        if (overviewZip != null) {
            try (ZipFile zip = new ZipFile(overviewZip.toFile())) {
                ZipEntry entry = findEntryContaining(zip, "thread");
                if (entry != null) {
                    result.append(htmlToText(readEntry(zip, entry)));
                    result.append("\n\n");
                }
            }
        }

        if (!output.isBlank()) {
            result.append("--- Thread Overview Command Output ---\n");
            result.append(truncateLines(output, 100));
        }

        return truncate(result.toString(), 10000);
    }

    // ========================== MAT Command Execution ==========================

    /**
     * Runs a MAT built-in command on the heap dump.
     * If index files already exist, this is fast (seconds).
     */
    private String runMatCommand(Path hprofPath, String command)
            throws IOException, InterruptedException {

        String matHome = matDownloadService.getEffectiveMatHome();
        Path parseScript = Paths.get(matHome, "ParseHeapDump.sh");

        if (!Files.isExecutable(parseScript)) {
            throw new IOException("MAT ParseHeapDump.sh not found at: " + parseScript);
        }

        ProcessBuilder pb = new ProcessBuilder(
                parseScript.toString(),
                hprofPath.toAbsolutePath().toString(),
                "-command=" + command
        );
        pb.directory(hprofPath.getParent().toFile());
        pb.redirectErrorStream(true);

        return executeProcess(pb, command);
    }

    /**
     * Runs an OQL query via MAT.
     */
    private String runMatOqlCommand(Path hprofPath, String query)
            throws IOException, InterruptedException {

        String matHome = matDownloadService.getEffectiveMatHome();
        Path parseScript = Paths.get(matHome, "ParseHeapDump.sh");

        if (!Files.isExecutable(parseScript)) {
            throw new IOException("MAT ParseHeapDump.sh not found at: " + parseScript);
        }

        ProcessBuilder pb = new ProcessBuilder(
                parseScript.toString(),
                hprofPath.toAbsolutePath().toString(),
                "-command=oql \"" + query.replace("\"", "\\\"") + "\""
        );
        pb.directory(hprofPath.getParent().toFile());
        pb.redirectErrorStream(true);

        return executeProcess(pb, "oql");
    }

    private String executeProcess(ProcessBuilder pb, String commandName)
            throws IOException, InterruptedException {

        log.info("[MatQuery] Running command: {}", commandName);
        long startTime = System.currentTimeMillis();

        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines()
                    .filter(line -> !isNoiseLine(line))
                    .collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(queryTimeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("MAT query timed out after " + queryTimeoutMinutes + " minutes");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int exitCode = process.exitValue();
        log.info("[MatQuery] Command '{}' completed in {}ms (exit code {})", commandName, elapsed, exitCode);

        // MAT often returns non-zero even on success for queries, check output
        if (output.isBlank() && exitCode != 0) {
            throw new RuntimeException("MAT command '" + commandName + "' failed (exit code " + exitCode + ")");
        }

        return output;
    }

    /**
     * Returns true for lines that are JVM warnings, MAT progress indicators,
     * or other Eclipse runtime noise — not useful for LLM consumption.
     */
    private boolean isNoiseLine(String line) {
        if (line == null) return true;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false; // keep blank lines for formatting
        return trimmed.startsWith("WARNING:")
                || trimmed.startsWith("Task:")
                || trimmed.matches("^\\[[\\.]+\\]$")           // progress bars like [.........]
                || trimmed.startsWith("Subtask:")
                || trimmed.startsWith("Please consider reporting")
                || trimmed.startsWith("Use --enable-native-access");
    }

    // ========================== Utility methods ==========================

    private Path findZip(Path directory, String glob) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path p : stream) return p;
        }
        return null;
    }

    private ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry != null) return entry;
        return zip.stream()
                .filter(e -> e.getName().endsWith("/" + name) && !e.isDirectory())
                .findFirst().orElse(null);
    }

    private ZipEntry findEntryContaining(ZipFile zip, String keyword) {
        return zip.stream()
                .filter(e -> !e.isDirectory() && e.getName().endsWith(".html"))
                .filter(e -> e.getName().toLowerCase().contains(keyword.toLowerCase()))
                .findFirst().orElse(null);
    }

    private String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (var is = zip.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String htmlToText(String html) {
        return html
                .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("</th>\\s*<th", "  |  </th><th")
                .replaceAll("</td>\\s*<td", "  |  </td><td")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("</tr>", "\n")
                .replaceAll("</li>", "\n")
                .replaceAll("</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n[ \\t]+", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String filterJvmProperties(String text) {
        Set<String> relevantKeys = Set.of(
                "sun.java.command", "java.vm.name", "java.version",
                "java.runtime.version", "sun.arch.data.model", "os.name", "os.arch"
        );
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            boolean relevant = relevantKeys.stream().anyMatch(trimmed::contains)
                    || JVM_FLAG_PATTERN.matcher(trimmed).matches()
                    || trimmed.startsWith("-");
            if (relevant) sb.append(trimmed).append("\n");
        }
        return sb.isEmpty() ? text.lines().limit(10).collect(Collectors.joining("\n")) : sb.toString();
    }

    private String truncateLines(String text, int maxLines) {
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

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... truncated at " + maxChars + " chars ...]";
    }
}
