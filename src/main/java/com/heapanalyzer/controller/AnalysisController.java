package com.heapanalyzer.controller;

import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.service.AnalysisService;
import com.heapanalyzer.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the web UI and REST API for heap dump analysis.
 */
@Controller
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final FileStorageService fileStorageService;
    private final AnalysisService analysisService;

    public AnalysisController(FileStorageService fileStorageService,
                              AnalysisService analysisService) {
        this.fileStorageService = fileStorageService;
        this.analysisService = analysisService;
    }

    /**
     * Serves the main UI page.
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * Accepts a heap dump file upload. Streams the file to disk,
     * creates an analysis entry, and kicks off async processing.
     * Returns the analysisId immediately so the frontend can poll for status.
     */
    @PostMapping("/api/analysis/upload")
    @ResponseBody
    public ResponseEntity<?> uploadHeapDump(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file provided."));
        }

        String analysisId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.hprof";

        log.info("Received upload: {} ({} bytes), analysisId={}", fileName, file.getSize(), analysisId);

        try {
            // 1. Create the analysis tracking entry
            AnalysisState state = analysisService.createAnalysis(analysisId, fileName);
            state.setFileSizeBytes(file.getSize());

            // 2. Stream file to disk
            Path savedPath = fileStorageService.store(file, analysisId);

            // 3. Mark as uploaded, kick off async analysis
            state.setStatus(AnalysisStatus.ANALYZING);
            analysisService.runAnalysis(analysisId, savedPath);

            return ResponseEntity.ok(Map.of(
                    "analysisId", analysisId,
                    "fileName", fileName,
                    "message", "Upload complete. Analysis started."
            ));

        } catch (Exception e) {
            log.error("Upload failed for analysisId={}", analysisId, e);

            // Mark as failed if we already created the entry
            AnalysisState state = analysisService.getAnalysis(analysisId);
            if (state != null) {
                state.setStatus(AnalysisStatus.FAILED);
                state.setErrorMessage("Upload failed: " + e.getMessage());
            }

            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Returns the current status and results of an analysis.
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
                "status", state.getStatus().name(),
                "staticReport", state.getStaticReport() != null ? state.getStaticReport() : "",
                "aiResponse", state.getAiResponse() != null ? state.getAiResponse() : "",
                "errorMessage", state.getErrorMessage() != null ? state.getErrorMessage() : "",
                "createdAt", state.getCreatedAt().toString(),
                "fileSizeBytes", state.getFileSizeBytes()
        ));
    }
}
