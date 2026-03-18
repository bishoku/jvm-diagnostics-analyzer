package com.heapanalyzer.service;

import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.model.AnalysisType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates analysis pipelines for heap dumps, thread dumps, and GC logs.
 * All heavy work runs on a separate thread via @Async.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final Map<String, AnalysisState> analysisRegistry = new ConcurrentHashMap<>();
    private final MatAnalysisService matAnalysisService;
    private final ThreadDumpAnalysisService threadDumpAnalysisService;
    private final GcLogAnalysisService gcLogAnalysisService;
    private final SpringAiService springAiService;

    public AnalysisService(MatAnalysisService matAnalysisService,
                           ThreadDumpAnalysisService threadDumpAnalysisService,
                           GcLogAnalysisService gcLogAnalysisService,
                           SpringAiService springAiService) {
        this.matAnalysisService = matAnalysisService;
        this.threadDumpAnalysisService = threadDumpAnalysisService;
        this.gcLogAnalysisService = gcLogAnalysisService;
        this.springAiService = springAiService;
    }

    /**
     * Creates a new analysis entry and returns it.
     */
    public AnalysisState createAnalysis(String analysisId, String fileName, AnalysisType type) {
        AnalysisState state = new AnalysisState(analysisId, fileName, type);
        analysisRegistry.put(analysisId, state);
        return state;
    }

    /**
     * Returns the current state of an analysis, or null if not found.
     */
    public AnalysisState getAnalysis(String analysisId) {
        return analysisRegistry.get(analysisId);
    }

    /**
     * Runs the heap dump analysis pipeline asynchronously.
     */
    @Async("analysisExecutor")
    public void runHeapDumpAnalysis(String analysisId, Path filePath) {
        AnalysisState state = analysisRegistry.get(analysisId);
        if (state == null) {
            log.error("Analysis {} not found in registry", analysisId);
            return;
        }

        try {
            // Phase 1: Eclipse MAT Static Analysis
            state.setStatus(AnalysisStatus.ANALYZING);
            log.info("[{}] Running Eclipse MAT analysis on {}", analysisId, filePath);

            String staticReport = matAnalysisService.analyze(filePath);
            state.setStaticReport(staticReport);
            log.info("[{}] Eclipse MAT analysis complete ({} chars)", analysisId, staticReport.length());

            // Phase 2: AI Processing
            state.setStatus(AnalysisStatus.AI_PROCESSING);
            log.info("[{}] Sending heap dump report to AI...", analysisId);

            String aiResponse = springAiService.analyze(staticReport);
            state.setAiResponse(aiResponse);

            // Done
            state.setStatus(AnalysisStatus.COMPLETED);
            log.info("[{}] Heap dump analysis completed successfully", analysisId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage("Analysis was interrupted.");
            log.error("[{}] Analysis interrupted", analysisId, e);
        } catch (Exception e) {
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage(e.getMessage());
            log.error("[{}] Heap dump analysis failed", analysisId, e);
        }
    }

    /**
     * Runs the thread dump analysis pipeline asynchronously.
     */
    @Async("analysisExecutor")
    public void runThreadDumpAnalysis(String analysisId, Path filePath) {
        AnalysisState state = analysisRegistry.get(analysisId);
        if (state == null) {
            log.error("Analysis {} not found in registry", analysisId);
            return;
        }

        try {
            // Phase 1: Parse thread dump
            state.setStatus(AnalysisStatus.ANALYZING);
            log.info("[{}] Parsing thread dump from {}", analysisId, filePath);

            String staticReport = threadDumpAnalysisService.analyze(filePath);
            state.setStaticReport(staticReport);
            log.info("[{}] Thread dump parsing complete ({} chars)", analysisId, staticReport.length());

            // Phase 2: AI Processing
            state.setStatus(AnalysisStatus.AI_PROCESSING);
            log.info("[{}] Sending thread dump report to AI...", analysisId);

            String aiResponse = springAiService.analyzeThreadDump(staticReport);
            state.setAiResponse(aiResponse);

            // Done
            state.setStatus(AnalysisStatus.COMPLETED);
            log.info("[{}] Thread dump analysis completed successfully", analysisId);

        } catch (Exception e) {
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage(e.getMessage());
            log.error("[{}] Thread dump analysis failed", analysisId, e);
        }
    }

    /**
     * Runs the GC log analysis pipeline asynchronously.
     */
    @Async("analysisExecutor")
    public void runGcLogAnalysis(String analysisId, Path filePath) {
        AnalysisState state = analysisRegistry.get(analysisId);
        if (state == null) {
            log.error("Analysis {} not found in registry", analysisId);
            return;
        }

        try {
            // Phase 1: Parse GC log
            state.setStatus(AnalysisStatus.ANALYZING);
            log.info("[{}] Parsing GC log from {}", analysisId, filePath);

            String staticReport = gcLogAnalysisService.analyze(filePath);
            state.setStaticReport(staticReport);
            log.info("[{}] GC log parsing complete ({} chars)", analysisId, staticReport.length());

            // Phase 2: AI Processing
            state.setStatus(AnalysisStatus.AI_PROCESSING);
            log.info("[{}] Sending GC log report to AI...", analysisId);

            String aiResponse = springAiService.analyzeGcLog(staticReport);
            state.setAiResponse(aiResponse);

            // Done
            state.setStatus(AnalysisStatus.COMPLETED);
            log.info("[{}] GC log analysis completed successfully", analysisId);

        } catch (Exception e) {
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage(e.getMessage());
            log.error("[{}] GC log analysis failed", analysisId, e);
        }
    }

    /**
     * @deprecated Use {@link #runHeapDumpAnalysis(String, Path)} instead.
     * Kept for backward compatibility.
     */
    @Deprecated
    @Async("analysisExecutor")
    public void runAnalysis(String analysisId, Path filePath) {
        runHeapDumpAnalysis(analysisId, filePath);
    }
}
