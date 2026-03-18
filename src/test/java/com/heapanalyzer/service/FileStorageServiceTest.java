package com.heapanalyzer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileStorageServiceTest {

    @TempDir
    Path tempStorageDir;

    private FileStorageService fileStorageService;

    @BeforeEach
    public void setup() {
        fileStorageService = new FileStorageService(tempStorageDir.toString());
        fileStorageService.init();
    }

    @Test
    public void testStore_NormalFile() throws IOException {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "normal-file.hprof",
                "application/octet-stream",
                "test data".getBytes()
        );

        String analysisId = "test-analysis-1";
        Path savedPath = fileStorageService.store(mockFile, analysisId);

        assertTrue(Files.exists(savedPath));
        assertEquals("normal-file.hprof", savedPath.getFileName().toString());
        assertEquals(analysisId, savedPath.getParent().getFileName().toString());
    }

    @Test
    public void testStore_NullFilename() throws IOException {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                (String) null,
                "application/octet-stream",
                "test data".getBytes()
        );

        String analysisId = "test-analysis-2";
        Path savedPath = fileStorageService.store(mockFile, analysisId);

        assertTrue(Files.exists(savedPath));
        assertEquals("heap-dump.hprof", savedPath.getFileName().toString());
    }

    @Test
    public void testStore_PathTraversalAttempt() throws IOException {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "../../etc/passwd",
                "application/octet-stream",
                "malicious data".getBytes()
        );

        String analysisId = "test-analysis-3";
        Path savedPath = fileStorageService.store(mockFile, analysisId);

        // Ensure the file is saved inside the analysisId directory
        assertTrue(Files.exists(savedPath));
        assertEquals("passwd", savedPath.getFileName().toString());
        assertEquals(analysisId, savedPath.getParent().getFileName().toString());
        assertEquals(tempStorageDir.resolve(analysisId).resolve("passwd").toAbsolutePath(), savedPath.toAbsolutePath());
    }

    @Test
    public void testStore_RootPathOnly() throws IOException {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "/",
                "application/octet-stream",
                "data".getBytes()
        );

        String analysisId = "test-analysis-4";
        Path savedPath = fileStorageService.store(mockFile, analysisId);

        assertTrue(Files.exists(savedPath));
        assertEquals("heap-dump.hprof", savedPath.getFileName().toString());
    }
}
