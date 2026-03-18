package com.heapanalyzer.service;

import com.heapanalyzer.model.AnalysisRecord;
import com.heapanalyzer.model.AnalysisType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnalysisHistoryService}.
 * Uses a temp directory for the JSON file — no Spring context.
 */
class AnalysisHistoryServiceTest {

    @TempDir
    Path tempDir;

    private AnalysisHistoryService historyService;

    @BeforeEach
    void setUp() {
        historyService = new AnalysisHistoryService(tempDir.toString());
        historyService.loadHistory(); // simulate @PostConstruct
    }

    // ========================== Save & Retrieve ==========================

    @Test
    void save_shouldPersistAndReturn() {
        AnalysisRecord record = createRecord("id-1", "dump.hprof", AnalysisType.HEAP_DUMP);

        AnalysisRecord saved = historyService.save(record);

        assertEquals("id-1", saved.getId());
        assertEquals("dump.hprof", saved.getFileName());
    }

    @Test
    void findById_shouldReturnSavedRecord() {
        historyService.save(createRecord("id-2", "threads.txt", AnalysisType.THREAD_DUMP));

        Optional<AnalysisRecord> found = historyService.findById("id-2");

        assertTrue(found.isPresent());
        assertEquals("threads.txt", found.get().getFileName());
        assertEquals(AnalysisType.THREAD_DUMP, found.get().getAnalysisType());
    }

    @Test
    void findById_shouldReturnEmptyForUnknownId() {
        Optional<AnalysisRecord> found = historyService.findById("nonexistent");
        assertTrue(found.isEmpty());
    }

    // ========================== List ==========================

    @Test
    void listAll_shouldReturnAllRecordsSortedByDate() throws Exception {
        AnalysisRecord r1 = createRecord("id-a", "first.hprof", AnalysisType.HEAP_DUMP);
        r1.setSavedAt("2025-01-01T00:00:00Z");
        historyService.save(r1);

        Thread.sleep(10); // ensure different savedAt

        AnalysisRecord r2 = createRecord("id-b", "second.log", AnalysisType.GC_LOG);
        r2.setSavedAt("2025-06-01T00:00:00Z");
        historyService.save(r2);

        List<AnalysisRecord> all = historyService.listAll();

        assertEquals(2, all.size());
        // Newest first
        assertEquals("id-b", all.get(0).getId());
        assertEquals("id-a", all.get(1).getId());
    }

    @Test
    void listAll_shouldReturnEmptyWhenNoRecords() {
        List<AnalysisRecord> all = historyService.listAll();
        assertTrue(all.isEmpty());
    }

    // ========================== Delete ==========================

    @Test
    void delete_shouldRemoveRecord() {
        historyService.save(createRecord("id-del", "todelete.hprof", AnalysisType.HEAP_DUMP));

        boolean deleted = historyService.delete("id-del");

        assertTrue(deleted);
        assertTrue(historyService.findById("id-del").isEmpty());
    }

    @Test
    void delete_shouldReturnFalseForUnknownId() {
        boolean deleted = historyService.delete("nonexistent");
        assertFalse(deleted);
    }

    @Test
    void delete_shouldNotAffectOtherRecords() {
        historyService.save(createRecord("id-keep", "keep.hprof", AnalysisType.HEAP_DUMP));
        historyService.save(createRecord("id-remove", "remove.hprof", AnalysisType.HEAP_DUMP));

        historyService.delete("id-remove");

        assertEquals(1, historyService.listAll().size());
        assertTrue(historyService.findById("id-keep").isPresent());
    }

    // ========================== Persistence ==========================

    @Test
    void persistence_shouldSurviveReload() {
        // Save records
        historyService.save(createRecord("persist-1", "dump1.hprof", AnalysisType.HEAP_DUMP));
        historyService.save(createRecord("persist-2", "gc.log", AnalysisType.GC_LOG));

        // Verify JSON file was created
        Path historyFile = tempDir.resolve("analysis-history.json");
        assertTrue(Files.exists(historyFile), "History JSON file should exist");

        // Create a new service instance pointing to the same directory
        AnalysisHistoryService reloaded = new AnalysisHistoryService(tempDir.toString());
        reloaded.loadHistory();

        // Should have all records
        assertEquals(2, reloaded.listAll().size());
        assertTrue(reloaded.findById("persist-1").isPresent());
        assertTrue(reloaded.findById("persist-2").isPresent());
    }

    @Test
    void persistence_deleteShouldUpdateJsonFile() throws IOException {
        historyService.save(createRecord("del-persist", "x.hprof", AnalysisType.HEAP_DUMP));
        historyService.delete("del-persist");

        // Reload and verify it's gone
        AnalysisHistoryService reloaded = new AnalysisHistoryService(tempDir.toString());
        reloaded.loadHistory();

        assertTrue(reloaded.findById("del-persist").isEmpty());
    }

    @Test
    void loadHistory_shouldHandleMissingFile() {
        // Fresh temp dir with no file — should not throw
        AnalysisHistoryService fresh = new AnalysisHistoryService(tempDir.resolve("subdir").toString());
        assertDoesNotThrow(fresh::loadHistory);
        assertTrue(fresh.listAll().isEmpty());
    }

    @Test
    void loadHistory_shouldHandleCorruptFile() throws IOException {
        // Write corrupt JSON
        Files.writeString(tempDir.resolve("analysis-history.json"), "{{not valid json");

        AnalysisHistoryService corrupt = new AnalysisHistoryService(tempDir.toString());
        // Should not throw, just log warning
        assertDoesNotThrow(corrupt::loadHistory);
        assertTrue(corrupt.listAll().isEmpty());
    }

    // ========================== Overwrite / Idempotent Save ==========================

    @Test
    void save_shouldOverwriteExistingRecord() {
        AnalysisRecord original = createRecord("overwrite-1", "file.hprof", AnalysisType.HEAP_DUMP);
        original.setAiResponse("first response");
        historyService.save(original);

        AnalysisRecord updated = createRecord("overwrite-1", "file.hprof", AnalysisType.HEAP_DUMP);
        updated.setAiResponse("updated response");
        historyService.save(updated);

        assertEquals(1, historyService.listAll().size());
        assertEquals("updated response", historyService.findById("overwrite-1").get().getAiResponse());
    }

    // ========================== Helpers ==========================

    private AnalysisRecord createRecord(String id, String fileName, AnalysisType type) {
        AnalysisRecord record = new AnalysisRecord();
        record.setId(id);
        record.setFileName(fileName);
        record.setAnalysisType(type);
        record.setStatus("COMPLETED");
        record.setStaticReport("static report content");
        record.setAiResponse("ai response content");
        record.setCreatedAt("2025-01-01T00:00:00Z");
        record.setSavedAt("2025-01-01T00:01:00Z");
        return record;
    }
}
