package com.heapanalyzer.controller;

import com.heapanalyzer.model.AnalysisRecord;
import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.model.AnalysisType;
import com.heapanalyzer.model.McpLogEntry;
import com.heapanalyzer.service.McpLogService;
import com.heapanalyzer.service.AnalysisHistoryService;
import com.heapanalyzer.service.AnalysisService;
import com.heapanalyzer.service.ConfigService;
import com.heapanalyzer.service.FileStorageService;
import com.heapanalyzer.service.MatDownloadService;
import com.heapanalyzer.service.McpSessionManager;
import com.heapanalyzer.service.SpringAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.List;

/**
 * Handles the web UI and REST API for JVM diagnostic analysis
 * (heap dumps, thread dumps, GC logs, and MCP server).
 */
@Controller
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final FileStorageService fileStorageService;
    private final AnalysisService analysisService;
    private final AnalysisHistoryService historyService;
    private final ConfigService configService;
    private final SpringAiService springAiService;
    private final MatDownloadService matDownloadService;
    private final McpSessionManager mcpSessionManager;
    private final McpLogService mcpLogService;

    public AnalysisController(FileStorageService fileStorageService,
                              AnalysisService analysisService,
                              AnalysisHistoryService historyService,
                              ConfigService configService,
                              SpringAiService springAiService,
                              MatDownloadService matDownloadService,
                              McpSessionManager mcpSessionManager,
                              McpLogService mcpLogService) {
        this.fileStorageService = fileStorageService;
        this.analysisService = analysisService;
        this.historyService = historyService;
        this.configService = configService;
        this.springAiService = springAiService;
        this.matDownloadService = matDownloadService;
        this.mcpSessionManager = mcpSessionManager;
        this.mcpLogService = mcpLogService;
    }

    // ========================== Page Routes ==========================

    /** Landing page with tool selection + history table. */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("history", historyService.listAll());
        return "index";
    }

    /** Heap dump analysis page. */
    @GetMapping("/heap")
    public String heapDump() {
        return "heap";
    }

    /** Thread dump analysis page. */
    @GetMapping("/thread-dump")
    public String threadDump() {
        return "thread-dump";
    }

    /** GC log analysis page. */
    @GetMapping("/gc-log")
    public String gcLog() {
        return "gc-log";
    }

    /** MCP Server page — upload heap dumps for interactive MCP querying. */
    @GetMapping("/mcp")
    public String mcpPage() {
        return "mcp";
    }

    /** MCP Logs page — view live JSON-RPC request and response streaming. */
    @GetMapping("/mcp/{sessionId}/logs")
    public String mcpLogsPage(@PathVariable("sessionId") String sessionId, Model model) {
        if (mcpSessionManager.getSession(sessionId) == null) {
            return "redirect:/mcp";
        }
        model.addAttribute("sessionId", sessionId);
        return "mcp-logs";
    }

    /** Stream incoming JSON-RPC traffic via SSE for the specified session. */
    @GetMapping("/api/mcp/{sessionId}/logs/stream")
    public SseEmitter streamMcpLogs(@PathVariable("sessionId") String sessionId) {
        return mcpLogService.subscribe(sessionId);
    }

    /** Fetch recent log history for initial UI population. */
    @GetMapping("/api/mcp/{sessionId}/logs/recent")
    @ResponseBody
    public ResponseEntity<List<McpLogEntry>> getRecentLogs(@PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok(mcpLogService.getRecentLogs(sessionId));
    }

    /** View a saved analysis result. */
    @GetMapping("/history/{id}")
    public String viewSavedResult(@PathVariable("id") String id, Model model) {
        var record = historyService.findById(id);
        if (record.isEmpty()) {
            return "redirect:/";
        }
        AnalysisRecord r = record.get();
        model.addAttribute("record", r);

        // Route to the correct template based on analysis type
        return switch (r.getAnalysisType()) {
            case HEAP_DUMP -> "heap";
            case THREAD_DUMP -> "thread-dump";
            case GC_LOG -> "gc-log";
        };
    }

    /** First-run setup page — shown when no API key is configured. */
    @GetMapping("/setup")
    public String setupPage() {
        // If already configured, redirect to home
        if (configService.isConfigured()) {
            return "redirect:/";
        }
        return "setup";
    }

    /** Settings page — accessible anytime from nav. */
    @GetMapping("/settings")
    public String settingsPage() {
        return "setup";
    }

    // ========================== Settings API ==========================

    /** Returns current settings (with masked API key). */
    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(Map.of(
                "configured", configService.isConfigured(),
                "maskedApiKey", configService.getMaskedApiKey(),
                "baseUrl", configService.getBaseUrl(),
                "model", configService.getModel(),
                "temperature", configService.getTemperature(),
                "trustInsecureCerts", configService.isTrustInsecureCerts(),
                "configFile", configService.getConfigFilePath().toString()
        ));
    }

    /** Saves settings, reconfigures the AI client, and returns success. */
    @PostMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "API key is required."));
        }

        configService.save(
                apiKey,
                body.getOrDefault("baseUrl", "https://openrouter.ai/api"),
                body.getOrDefault("model", "openai/gpt-4o"),
                body.getOrDefault("temperature", "0.3"),
                body.getOrDefault("trustInsecureCerts", "false")
        );

        try {
            springAiService.reconfigure();
            return ResponseEntity.ok(Map.of(
                    "message", "Settings saved successfully.",
                    "configured", true
            ));
        } catch (Exception e) {
            log.error("Failed to reconfigure AI client", e);
            return ResponseEntity.ok(Map.of(
                    "message", "Settings saved but AI client configuration failed: " + e.getMessage(),
                    "configured", true,
                    "warning", e.getMessage()
            ));
        }
    }

    /** Tests the AI connection with a minimal prompt. */
    @PostMapping("/api/settings/test")
    @ResponseBody
    public ResponseEntity<?> testConnection() {
        if (!springAiService.isReady()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "AI client is not configured. Save your settings first."));
        }

        try {
            // Tiny prompt to test connectivity — should use minimal tokens
            String response = springAiService.analyze(
                    "Test connection. Respond with exactly: CONNECTION_OK");
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Connection successful!",
                    "response", response != null ? response.substring(0, Math.min(100, response.length())) : ""
            ));
        } catch (IllegalStateException | RestClientException | NonTransientAiException | TransientAiException e) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "Connection failed: " + e.getMessage()
            ));
        }
    }

    // ========================== MAT Endpoints ==========================

    /** Returns MAT availability and download status. */
    @GetMapping("/api/mat/status")
    @ResponseBody
    public ResponseEntity<?> matStatus() {
        return ResponseEntity.ok(Map.of(
                "available", matDownloadService.isAvailable(),
                "downloading", matDownloadService.isDownloading(),
                "status", matDownloadService.getDownloadStatus(),
                "progress", matDownloadService.getDownloadProgress()
        ));
    }

    /** Triggers MAT download (if not already installed). */
    @PostMapping("/api/mat/download")
    @ResponseBody
    public ResponseEntity<?> downloadMat() {
        if (matDownloadService.isAvailable()) {
            return ResponseEntity.ok(Map.of("message", "MAT is already installed."));
        }
        if (matDownloadService.isDownloading()) {
            return ResponseEntity.ok(Map.of("message", "Download already in progress."));
        }

        // Run download in background
        Thread.startVirtualThread(() -> {
            try {
                matDownloadService.downloadAndInstall();
            } catch (Exception e) {
                log.error("MAT download failed", e);
            }
        });

        return ResponseEntity.ok(Map.of("message", "MAT download started."));
    }

    // ========================== Prompt Endpoints ==========================

    /** Returns the current and default prompt for the given type. */
    @GetMapping("/api/prompts/{type}")
    @ResponseBody
    public ResponseEntity<?> getPrompt(@PathVariable String type) {
        try {
            return ResponseEntity.ok(Map.of(
                    "type", type,
                    "current", springAiService.getEffectivePrompt(type),
                    "default", springAiService.getDefaultPrompt(type),
                    "isCustom", configService.hasCustomPrompt(type)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Saves a custom prompt. */
    @PostMapping("/api/prompts/{type}")
    @ResponseBody
    public ResponseEntity<?> savePrompt(@PathVariable String type, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Prompt content is required."));
        }
        try {
            springAiService.getDefaultPrompt(type); // validate type
            configService.savePrompt(type, content);
            return ResponseEntity.ok(Map.of("message", "Custom prompt saved.", "isCustom", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Resets a prompt to default. */
    @DeleteMapping("/api/prompts/{type}")
    @ResponseBody
    public ResponseEntity<?> resetPrompt(@PathVariable String type) {
        try {
            springAiService.getDefaultPrompt(type); // validate type
            configService.resetPrompt(type);
            return ResponseEntity.ok(Map.of(
                    "message", "Prompt reset to default.",
                    "isCustom", false,
                    "current", springAiService.getDefaultPrompt(type)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ========================== Upload Endpoints ==========================

    /** Upload a heap dump (.hprof) file for analysis. */
    @PostMapping("/api/heap/upload")
    @ResponseBody
    public ResponseEntity<?> uploadHeapDump(@RequestParam("file") MultipartFile file) {
        return handleUpload(file, AnalysisType.HEAP_DUMP, ".hprof", "unknown.hprof");
    }

    /** Upload a thread dump (.txt, .tdump, .log) file for analysis. */
    @PostMapping("/api/thread-dump/upload")
    @ResponseBody
    public ResponseEntity<?> uploadThreadDump(@RequestParam("file") MultipartFile file) {
        return handleUpload(file, AnalysisType.THREAD_DUMP, null, "thread-dump.txt");
    }

    /** Upload a GC log (.log, .txt) file for analysis. */
    @PostMapping("/api/gc-log/upload")
    @ResponseBody
    public ResponseEntity<?> uploadGcLog(@RequestParam("file") MultipartFile file) {
        return handleUpload(file, AnalysisType.GC_LOG, null, "gc.log");
    }

    // ========================== Status Endpoint ==========================

    /**
     * Returns the current status and results of any analysis type.
     * The frontend polls this endpoint every ~2 seconds.
     */
    @GetMapping("/api/analysis/{id}/status")
    @ResponseBody
    public ResponseEntity<?> getStatus(@PathVariable("id") String id) {
        AnalysisState state = analysisService.getAnalysis(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "id", state.getId(),
                "fileName", state.getFileName(),
                "analysisType", state.getAnalysisType().name(),
                "status", state.getStatus().name(),
                "staticReport", state.getStaticReport() != null ? state.getStaticReport() : "",
                "aiResponse", state.getAiResponse() != null ? state.getAiResponse() : "",
                "errorMessage", state.getErrorMessage() != null ? state.getErrorMessage() : "",
                "createdAt", state.getCreatedAt().toString(),
                "fileSizeBytes", state.getFileSizeBytes()
        ));
    }

    // ========================== History Endpoints ==========================

    /** Save a completed analysis to persistent history. */
    @PostMapping("/api/analysis/{id}/save")
    @ResponseBody
    public ResponseEntity<?> saveAnalysis(@PathVariable("id") String id) {
        AnalysisState state = analysisService.getAnalysis(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        if (state.getStatus() != AnalysisStatus.COMPLETED) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Can only save completed analyses."));
        }

        AnalysisRecord record = AnalysisRecord.from(state);
        historyService.save(record);

        return ResponseEntity.ok(Map.of(
                "message", "Analysis saved successfully.",
                "id", record.getId()
        ));
    }

    /** List all saved analysis records. */
    @GetMapping("/api/history")
    @ResponseBody
    public ResponseEntity<?> listHistory() {
        return ResponseEntity.ok(historyService.listAll());
    }

    /** Get a single saved analysis record. */
    @GetMapping("/api/history/{id}")
    @ResponseBody
    public ResponseEntity<?> getHistoryRecord(@PathVariable("id") String id) {
        return historyService.findById(id)
                .map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Delete a saved analysis record. */
    @DeleteMapping("/api/history/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteHistoryRecord(@PathVariable("id") String id) {
        if (historyService.delete(id)) {
            return ResponseEntity.ok(Map.of("message", "Record deleted."));
        }
        return ResponseEntity.notFound().build();
    }

    // ========================== MCP Endpoints ==========================

    /** Upload a heap dump (.hprof) to start an MCP interactive session. */
    @PostMapping("/api/mcp/upload")
    @ResponseBody
    public ResponseEntity<?> uploadForMcp(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided."));
        }

        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "unknown.hprof";

        if (!fileName.toLowerCase().endsWith(".hprof")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Expected a .hprof file."));
        }

        String sessionId = UUID.randomUUID().toString();

        try {
            Path savedPath = fileStorageService.store(file, "mcp-" + sessionId);
            mcpSessionManager.createSession(sessionId, fileName, savedPath, file.getSize());

            return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "fileName", fileName,
                    "message", "Heap dump uploaded. Parsing started — tools will be available once parsing completes."
            ));
        } catch (Exception e) {
            log.error("MCP upload failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /** Returns MCP server and session status. */
    @GetMapping("/api/mcp/status")
    @ResponseBody
    public ResponseEntity<?> mcpStatus() {
        var sessionInfo = mcpSessionManager.getStatusInfo();

        // Build connection info
        String serverUrl = "http://localhost:" + System.getProperty("server.port", "8080");
        Map<String, Object> response = new java.util.LinkedHashMap<>(sessionInfo);
        response.put("mcpEnabled", true);
        response.put("mcpProtocol", "SSE");
        response.put("mcpEndpoint", serverUrl + "/sse");
        response.put("mcpMessageEndpoint", serverUrl + "/mcp/message");

        return ResponseEntity.ok(response);
    }

    /** Closes the active MCP session and cleans up files. */
    @DeleteMapping("/api/mcp/session")
    @ResponseBody
    public ResponseEntity<?> closeMcpSession() {
        var session = mcpSessionManager.getActiveSession();
        if (session == null) {
            return ResponseEntity.ok(Map.of("message", "No active session."));
        }

        mcpSessionManager.closeSession(session.getSessionId());
        return ResponseEntity.ok(Map.of("message", "Session closed and files cleaned up."));
    }

    // ========================== Internals ==========================

    private ResponseEntity<?> handleUpload(MultipartFile file, AnalysisType type,
                                           String requiredExtension, String defaultFilename) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file provided."));
        }

        String analysisId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : defaultFilename;

        // Validate file extension if required
        if (requiredExtension != null && !fileName.toLowerCase().endsWith(requiredExtension)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Expected a " + requiredExtension + " file."));
        }

        log.info("Received {} upload: {} ({} bytes), analysisId={}",
                type, fileName, file.getSize(), analysisId);

        try {
            // 1. Create the analysis tracking entry
            AnalysisState state = analysisService.createAnalysis(analysisId, fileName, type);
            state.setFileSizeBytes(file.getSize());

            // 2. Stream file to disk
            Path savedPath = fileStorageService.store(file, analysisId);

            // 3. Mark as analyzing, kick off async analysis
            state.setStatus(AnalysisStatus.ANALYZING);

            switch (type) {
                case HEAP_DUMP -> analysisService.runHeapDumpAnalysis(analysisId, savedPath);
                case THREAD_DUMP -> analysisService.runThreadDumpAnalysis(analysisId, savedPath);
                case GC_LOG -> analysisService.runGcLogAnalysis(analysisId, savedPath);
            }

            return ResponseEntity.ok(Map.of(
                    "analysisId", analysisId,
                    "fileName", fileName,
                    "analysisType", type.name(),
                    "message", "Upload complete. Analysis started."
            ));

        } catch (Exception e) {
            log.error("Upload failed for analysisId={}", analysisId, e);

            AnalysisState state = analysisService.getAnalysis(analysisId);
            if (state != null) {
                state.setStatus(AnalysisStatus.FAILED);
                state.setErrorMessage("Upload failed: " + e.getMessage());
            }

            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }
}
