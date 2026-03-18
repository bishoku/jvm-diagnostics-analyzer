package com.heapanalyzer.controller;

import com.heapanalyzer.model.AnalysisRecord;
import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.model.AnalysisType;
import com.heapanalyzer.service.AnalysisHistoryService;
import com.heapanalyzer.service.AnalysisService;
import com.heapanalyzer.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the web UI and REST API for JVM diagnostic analysis
 * (heap dumps, thread dumps, and GC logs).
 */
@Controller
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final FileStorageService fileStorageService;
    private final AnalysisService analysisService;
    private final AnalysisHistoryService historyService;

    public AnalysisController(FileStorageService fileStorageService,
                              AnalysisService analysisService,
                              AnalysisHistoryService historyService) {
        this.fileStorageService = fileStorageService;
        this.analysisService = analysisService;
        this.historyService = historyService;
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

    /** Legacy endpoint — kept for backward compatibility. */
    @PostMapping("/api/analysis/upload")
    @ResponseBody
    public ResponseEntity<?> uploadLegacy(@RequestParam("file") MultipartFile file) {
        return uploadHeapDump(file);
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
