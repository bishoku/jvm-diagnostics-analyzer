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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs Eclipse Memory Analyzer (MAT) headlessly via its ParseHeapDump.sh script.
 *
 * <p>MAT is expected to be installed at the path configured by {@code app.mat.home}.
 * The {@code ParseHeapDump.sh} script generates report ZIP files alongside the heap dump,
 * which this service then extracts to produce the leak suspects text report.</p>
 */
@Service
public class MatAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MatAnalysisService.class);

    /** Max characters to send to the LLM — keeps token usage reasonable */
    private static final int MAX_REPORT_CHARS = 15_000;

    private final String matHome;
    private final long timeoutMinutes;

    public MatAnalysisService(
            @Value("${app.mat.home:/opt/mat}") String matHome,
            @Value("${app.mat.timeout-minutes:30}") long timeoutMinutes) {
        this.matHome = matHome;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Runs Eclipse MAT on the given heap dump file and returns the
     * Leak Suspects report text.
     *
     * @param heapDumpPath absolute path to the .hprof file
     * @return the text content of the leak suspects report
     * @throws IOException          if file I/O fails
     * @throws InterruptedException if the process is interrupted
     * @throws RuntimeException     if MAT exits with a non-zero code
     */
    public String analyze(Path heapDumpPath) throws IOException, InterruptedException {
        Path parseScript = Paths.get(matHome, "ParseHeapDump.sh");

        if (!Files.isExecutable(parseScript)) {
            throw new IOException("MAT ParseHeapDump.sh not found or not executable at: " + parseScript
                    + ". Make sure Eclipse MAT is installed at " + matHome);
        }

        log.info("Starting MAT analysis on {} (timeout: {} min)", heapDumpPath, timeoutMinutes);

        // Only generate the Leak Suspects report (most actionable, smallest output)
        ProcessBuilder pb = new ProcessBuilder(
                parseScript.toString(),
                heapDumpPath.toAbsolutePath().toString(),
                "org.eclipse.mat.api:suspects"
        );
        pb.directory(heapDumpPath.getParent().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Capture stdout/stderr for logging
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

        // MAT generates <name>_Leak_Suspects.zip alongside the heap dump
        return extractLeakSuspectsReport(heapDumpPath.getParent());
    }

    /**
     * Extracts the Leak Suspects report from the MAT-generated ZIP.
     * Only reads the main index.html page (the summary) to keep output concise.
     */
    private String extractLeakSuspectsReport(Path directory) throws IOException {
        StringBuilder report = new StringBuilder();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*_Leak_Suspects.zip")) {
            for (Path zipPath : stream) {
                log.info("Extracting Leak Suspects from: {}", zipPath);
                report.append(extractMainPageFromZip(zipPath));
            }
        }

        if (report.isEmpty()) {
            // Fallback: try .txt files MAT may have generated
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.txt")) {
                for (Path txtPath : stream) {
                    report.append("\n=== ").append(txtPath.getFileName()).append(" ===\n");
                    report.append(Files.readString(txtPath, StandardCharsets.UTF_8));
                }
            }
        }

        if (report.isEmpty()) {
            throw new IOException("No MAT Leak Suspects report found in " + directory);
        }

        String result = report.toString();

        // Truncate if too large for the LLM context
        if (result.length() > MAX_REPORT_CHARS) {
            log.warn("Report truncated from {} to {} chars", result.length(), MAX_REPORT_CHARS);
            result = result.substring(0, MAX_REPORT_CHARS)
                    + "\n\n[... REPORT TRUNCATED — showing first " + MAX_REPORT_CHARS + " characters ...]";
        }

        log.info("Extracted report: {} chars", result.length());
        return result;
    }

    /**
     * Opens a MAT ZIP report and extracts only the main index.html page,
     * stripping HTML to plain text. This gives the problem suspects summary
     * without all the sub-page detail.
     */
    private String extractMainPageFromZip(Path zipPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Eclipse MAT — Leak Suspects Report\n");
        sb.append("=".repeat(60)).append("\n\n");

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            // Prefer index.html (main summary page), fall back to any .html
            ZipEntry mainEntry = zipFile.getEntry("index.html");
            if (mainEntry == null) {
                // Try to find it in a subdirectory
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("index.html") && !e.isDirectory()) {
                        mainEntry = e;
                        break;
                    }
                }
            }

            if (mainEntry != null) {
                sb.append(htmlToPlainText(zipFile, mainEntry));
            } else {
                // No index.html — extract first .html file found
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith(".html") && !e.isDirectory()) {
                        sb.append(htmlToPlainText(zipFile, e));
                        break;
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Reads an HTML ZipEntry and strips tags to produce clean plain text.
     */
    private String htmlToPlainText(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (var is = zipFile.getInputStream(entry)) {
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return html
                    .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                    .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                    .replaceAll("<br\\s*/?>", "\n")
                    .replaceAll("</p>", "\n")
                    .replaceAll("</tr>", "\n")
                    .replaceAll("</li>", "\n")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&#\\d+;", "")
                    .replaceAll("[ \t]+", " ")
                    .replaceAll("\n[ \t]+", "\n")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();
        }
    }
}
