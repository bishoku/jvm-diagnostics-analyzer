package com.heapanalyzer.service;

import com.heapanalyzer.model.AnalysisRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists completed analysis results to a JSON file on disk.
 * On startup, loads any previously saved results into memory.
 */
@Service
public class AnalysisHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisHistoryService.class);

    private final Path historyFile;
    private final JsonMapper jsonMapper;
    private final Map<String, AnalysisRecord> records = new ConcurrentHashMap<>();

    public AnalysisHistoryService(
            @Value("${app.storage.location:./heap-dumps}") String storageLocation) {
        this.historyFile = Path.of(storageLocation).resolve("analysis-history.json");
        this.jsonMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    @PostConstruct
    public void loadHistory() {
        if (Files.exists(historyFile)) {
            try {
                File file = historyFile.toFile();
                List<AnalysisRecord> loaded = jsonMapper.readValue(
                        file,
                        new TypeReference<List<AnalysisRecord>>() {});
                loaded.forEach(r -> records.put(r.getId(), r));
                log.info("Loaded {} saved analysis records from {}", records.size(), historyFile);
            } catch (Exception e) {
                log.warn("Failed to load analysis history from {}: {}", historyFile, e.getMessage());
            }
        } else {
            log.info("No analysis history file found at {}, starting fresh", historyFile);
        }
    }

    /**
     * Saves an analysis record and persists to disk.
     */
    public AnalysisRecord save(AnalysisRecord record) {
        records.put(record.getId(), record);
        persistToDisk();
        log.info("Saved analysis record: id={}, type={}, file={}",
                record.getId(), record.getAnalysisType(), record.getFileName());
        return record;
    }

    /**
     * Returns all saved records, newest first.
     */
    public List<AnalysisRecord> listAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(AnalysisRecord::getSavedAt).reversed())
                .toList();
    }

    /**
     * Returns a single saved record by ID, or empty.
     */
    public Optional<AnalysisRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    /**
     * Deletes a record by ID.
     */
    public boolean delete(String id) {
        if (records.remove(id) != null) {
            persistToDisk();
            log.info("Deleted analysis record: id={}", id);
            return true;
        }
        return false;
    }

    private synchronized void persistToDisk() {
        try {
            Files.createDirectories(historyFile.getParent());
            jsonMapper.writeValue(historyFile.toFile(), records.values());
        } catch (Exception e) {
            log.error("Failed to persist analysis history to {}: {}", historyFile, e.getMessage());
        }
    }
}
