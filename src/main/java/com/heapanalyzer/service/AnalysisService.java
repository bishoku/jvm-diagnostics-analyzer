package com.heapanalyzer.service;

import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the full analysis pipeline:
 *   1. Run Eclipse MAT static analysis (via MatAnalysisService)
 *   2. Send report to Spring AI / OpenAI
 *   3. Update state at each step
 *
 * All heavy work runs on a separate thread via @Async.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final Map<String, AnalysisState> analysisRegistry = new ConcurrentHashMap<>();
    private final MatAnalysisService matAnalysisService;
    private final SpringAiService springAiService;

    public AnalysisService(MatAnalysisService matAnalysisService, SpringAiService springAiService) {
        this.matAnalysisService = matAnalysisService;
        this.springAiService = springAiService;
    }

    /**
     * Creates a new analysis entry and returns it.
     */
    public AnalysisState createAnalysis(String analysisId, String fileName) {
        AnalysisState state = new AnalysisState(analysisId, fileName);
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
     * Runs the full analysis pipeline asynchronously.
     */
    @Async("analysisExecutor")
    public void runAnalysis(String analysisId, Path filePath) {
        AnalysisState state = analysisRegistry.get(analysisId);
        if (state == null) {
            log.error("Analysis {} not found in registry", analysisId);
            return;
        }

        try {
            // ------- Phase 1: Eclipse MAT Static Analysis -------
            state.setStatus(AnalysisStatus.ANALYZING);
            log.info("[{}] Running Eclipse MAT analysis on {}", analysisId, filePath);

            String staticReport = matAnalysisService.analyze(filePath);
            state.setStaticReport(staticReport);
            log.info("[{}] Eclipse MAT analysis complete ({} chars)", analysisId, staticReport.length());

            // ------- Phase 2: AI Processing -------
            state.setStatus(AnalysisStatus.AI_PROCESSING);
            log.info("[{}] Sending report to AI...", analysisId);

            String aiResponse = springAiService.analyze(staticReport);
            state.setAiResponse(aiResponse);

            // ------- Done -------
            state.setStatus(AnalysisStatus.COMPLETED);
            log.info("[{}] Analysis completed successfully", analysisId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage("Analysis was interrupted.");
            log.error("[{}] Analysis interrupted", analysisId, e);

        } catch (Exception e) {
            state.setStatus(AnalysisStatus.FAILED);
            state.setErrorMessage(e.getMessage());
            log.error("[{}] Analysis failed", analysisId, e);
        }
    }
}
