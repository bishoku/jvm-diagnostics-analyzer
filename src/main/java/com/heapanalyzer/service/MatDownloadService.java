package com.heapanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and installs Eclipse MAT automatically on first run if it's not
 * already available at the configured {@code app.mat.home} path.
 *
 * <p>Download URLs are for MAT 1.16.1 standalone (requires Java 17+).</p>
 *
 * <p>The download target defaults to {@code ~/.jvm-diagnostics/mat/} and
 * is only attempted when the configured mat.home doesn't exist.</p>
 */
@Service
public class MatDownloadService {

    private static final Logger log = LoggerFactory.getLogger(MatDownloadService.class);

    private static final String MAT_VERSION = "1.16.1";
    private static final String MAT_DATE = "20250109";

    private static final String DOWNLOAD_BASE =
            "https://download.eclipse.org/mat/" + MAT_VERSION + "/rcp/MemoryAnalyzer-" + MAT_VERSION + "." + MAT_DATE;

    private static final String LINUX_URL = DOWNLOAD_BASE + "-linux.gtk.x86_64.zip";
    private static final String WINDOWS_URL = DOWNLOAD_BASE + "-win32.win32.x86_64.zip";
    private static final String MAC_URL = DOWNLOAD_BASE + "-macosx.cocoa.x86_64.zip";
    private static final String MAC_ARM_URL = DOWNLOAD_BASE + "-macosx.cocoa.aarch64.zip";

    private final String configuredMatHome;
    private final Path autoDownloadDir;

    private volatile boolean available = false;
    private volatile boolean downloading = false;
    private volatile String downloadStatus = "";
    private volatile int downloadProgress = 0;

    public MatDownloadService(
            @Value("${app.mat.home:/opt/mat}") String matHome) {
        this.configuredMatHome = matHome;
        this.autoDownloadDir = Path.of(System.getProperty("user.home"), ".jvm-diagnostics", "mat");
    }

    @PostConstruct
    public void checkAvailability() {
        // Check configured MAT home
        Path configuredScript = resolveParseScript(Path.of(configuredMatHome));
        if (configuredScript != null && Files.exists(configuredScript)) {
            available = true;
            log.info("Eclipse MAT found at configured location: {}", configuredMatHome);
            return;
        }

        // Check auto-download location
        Path autoScript = resolveParseScript(autoDownloadDir);
        if (autoScript != null && Files.exists(autoScript)) {
            available = true;
            log.info("Eclipse MAT found at auto-download location: {}", autoDownloadDir);
            return;
        }

        log.info("Eclipse MAT not found. Use /setup or upload a heap dump to trigger auto-download.");
    }

    /**
     * Returns the effective MAT home directory (configured or auto-downloaded).
     */
    public String getEffectiveMatHome() {
        Path configuredScript = resolveParseScript(Path.of(configuredMatHome));
        if (configuredScript != null && Files.exists(configuredScript)) {
            return configuredMatHome;
        }

        // Check for MAT unpacked inside a subdirectory (e.g. mat/mat/)
        Path autoScript = resolveParseScript(autoDownloadDir);
        if (autoScript != null && Files.exists(autoScript)) {
            return autoScript.getParent().toString();
        }

        return configuredMatHome;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public String getDownloadStatus() {
        return downloadStatus;
    }

    public int getDownloadProgress() {
        return downloadProgress;
    }

    /**
     * Downloads and extracts Eclipse MAT to ~/.jvm-diagnostics/mat/.
     * This is a blocking operation (~150MB download).
     */
    public void downloadAndInstall() throws IOException, InterruptedException {
        if (available) {
            log.info("MAT is already available, skipping download");
            return;
        }
        if (downloading) {
            log.info("MAT download already in progress");
            return;
        }

        downloading = true;
        downloadStatus = "Determining platform...";
        downloadProgress = 0;

        try {
            String url = getDownloadUrl();
            downloadStatus = "Downloading Eclipse MAT " + MAT_VERSION + " (~150 MB)...";
            log.info("Downloading Eclipse MAT from: {}", url);

            Files.createDirectories(autoDownloadDir);
            Path zipFile = autoDownloadDir.resolve("mat-download.zip");

            // Download with progress tracking
            downloadFile(url, zipFile);

            // Extract
            downloadStatus = "Extracting Eclipse MAT...";
            downloadProgress = 90;
            log.info("Extracting MAT to {}", autoDownloadDir);
            extractZip(zipFile, autoDownloadDir);

            // Make ParseHeapDump.sh executable on Linux/Mac
            makeScriptsExecutable(autoDownloadDir);

            // Cleanup zip
            Files.deleteIfExists(zipFile);

            downloadStatus = "Eclipse MAT installed successfully!";
            downloadProgress = 100;
            available = true;
            log.info("Eclipse MAT {} installed at {}", MAT_VERSION, autoDownloadDir);

        } catch (Exception e) {
            downloadStatus = "Download failed: " + e.getMessage();
            log.error("Failed to download MAT", e);
            throw e;
        } finally {
            downloading = false;
        }
    }

    private String getDownloadUrl() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("win")) {
            return WINDOWS_URL;
        } else if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm") ? MAC_ARM_URL : MAC_URL;
        } else {
            return LINUX_URL;
        }
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }

        long contentLength = response.headers()
                .firstValueAsLong("content-length").orElse(-1);

        try (InputStream is = response.body();
             OutputStream os = Files.newOutputStream(target)) {

            byte[] buffer = new byte[65536];
            long totalRead = 0;
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (contentLength > 0) {
                    downloadProgress = (int) (80.0 * totalRead / contentLength);
                    if (totalRead % (5 * 1024 * 1024) < buffer.length) {
                        downloadStatus = String.format("Downloading... %d MB / %d MB",
                                totalRead / (1024 * 1024), contentLength / (1024 * 1024));
                    }
                }
            }
        }

        log.info("Download complete: {} ({} bytes)", target.getFileName(), Files.size(target));
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();

                // Security: prevent zip slip
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void makeScriptsExecutable(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".sh"))
                    .forEach(p -> {
                        try {
                            p.toFile().setExecutable(true);
                        } catch (Exception e) {
                            log.warn("Could not make {} executable", p.getFileName());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not make scripts executable: {}", e.getMessage());
        }
    }

    /**
     * Finds ParseHeapDump.sh (or .bat) in the given directory or its subdirectories.
     */
    private Path resolveParseScript(Path dir) {
        if (!Files.exists(dir)) return null;

        String scriptName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "ParseHeapDump.bat" : "ParseHeapDump.sh";

        // Direct child
        Path direct = dir.resolve(scriptName);
        if (Files.exists(direct)) return direct;

        // One level down (common after extraction: mat/mat/ParseHeapDump.sh)
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(sub -> sub.resolve(scriptName))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
