package com.heapanalyzer.controller;

import com.heapanalyzer.model.AnalysisState;
import com.heapanalyzer.model.AnalysisStatus;
import com.heapanalyzer.model.AnalysisType;
import com.heapanalyzer.service.AnalysisHistoryService;
import com.heapanalyzer.service.AnalysisService;
import com.heapanalyzer.service.ConfigService;
import com.heapanalyzer.service.FileStorageService;
import com.heapanalyzer.service.HeapDumpChatService;
import com.heapanalyzer.service.MatDownloadService;
import com.heapanalyzer.service.McpLogService;
import com.heapanalyzer.service.McpSessionManager;
import com.heapanalyzer.service.SpringAiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileStorageService fileStorageService;

    @MockitoBean
    private AnalysisService analysisService;

    @MockitoBean
    private AnalysisHistoryService historyService;

    @MockitoBean
    private ConfigService configService;

    @MockitoBean
    private SpringAiService springAiService;

    @MockitoBean
    private MatDownloadService matDownloadService;

    @MockitoBean
    private McpSessionManager mcpSessionManager;

    @MockitoBean
    private McpLogService mcpLogService;

    @MockitoBean
    private HeapDumpChatService chatService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(configService.isConfigured()).thenReturn(true);
    }

    @Test
    void getStatus_WhenFound_Returns200AndStatus() throws Exception {
        // Arrange
        String analysisId = "test-id";
        AnalysisState state = new AnalysisState(analysisId, "test.hprof", AnalysisType.HEAP_DUMP);
        state.setStatus(AnalysisStatus.COMPLETED);
        state.setStaticReport("Test static report");
        state.setAiResponse("Test AI response");
        state.setFileSizeBytes(1024L);

        when(analysisService.getAnalysis(analysisId)).thenReturn(state);

        // Act & Assert
        mockMvc.perform(get("/api/analysis/{id}/status", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(analysisId))
                .andExpect(jsonPath("$.fileName").value("test.hprof"))
                .andExpect(jsonPath("$.analysisType").value(AnalysisType.HEAP_DUMP.name()))
                .andExpect(jsonPath("$.status").value(AnalysisStatus.COMPLETED.name()))
                .andExpect(jsonPath("$.staticReport").value("Test static report"))
                .andExpect(jsonPath("$.aiResponse").value("Test AI response"))
                .andExpect(jsonPath("$.fileSizeBytes").value(1024L));
    }

    @Test
    void getStatus_WhenNotFound_Returns404() throws Exception {
        // Arrange
        String analysisId = "unknown-id";
        when(analysisService.getAnalysis(analysisId)).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/api/analysis/{id}/status", analysisId))
                .andExpect(status().isNotFound());
    }
}
