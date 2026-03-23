package com.heapanalyzer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService = new FileStorageService(tempDir.toString());
    }

    @Test
    void init_shouldCreateStorageDirectory() {
        // Given
        Path nonExistentDir = tempDir.resolve("new-storage-dir");
        FileStorageService service = new FileStorageService(nonExistentDir.toString());

        // When
        service.init();

        // Then
        assertTrue(Files.exists(nonExistentDir), "Storage directory should be created");
        assertTrue(Files.isDirectory(nonExistentDir), "Storage path should be a directory");
    }

    @Test
    void store_shouldSaveFileToDisk() throws IOException {
        // Given
        fileStorageService.init();
        String analysisId = "test-analysis-id";
        String originalFilename = "test-file.txt";
        byte[] content = "Hello, World!".getBytes();
        MultipartFile file = new MockMultipartFile("file", originalFilename, "text/plain", content);

        // When
        Path savedPath = fileStorageService.store(file, analysisId);

        // Then
        assertNotNull(savedPath);
        assertTrue(Files.exists(savedPath));
        assertEquals(originalFilename, savedPath.getFileName().toString());
        assertEquals("Hello, World!", Files.readString(savedPath));
        assertTrue(savedPath.startsWith(tempDir.resolve(analysisId)));
    }

    @Test
    void store_shouldUseDefaultFilenameWhenOriginalIsNull() throws IOException {
        // Given
        fileStorageService.init();
        String analysisId = "test-analysis-id-null";
        byte[] content = "Heap dump content".getBytes();
        MultipartFile file = new MockMultipartFile("file", null, "application/octet-stream", content);

        // When
        Path savedPath = fileStorageService.store(file, analysisId);

        // Then
        assertNotNull(savedPath);
        assertTrue(Files.exists(savedPath));
        assertEquals("heap-dump.hprof", savedPath.getFileName().toString());
        assertEquals("Heap dump content", Files.readString(savedPath));
    }

    @Test
    void store_shouldUseDefaultFilenameWhenOriginalIsBlank() throws IOException {
        // Given
        fileStorageService.init();
        String analysisId = "test-analysis-id-blank";
        byte[] content = "Heap dump content".getBytes();
        MultipartFile file = new MockMultipartFile("file", "   ", "application/octet-stream", content);

        // When
        Path savedPath = fileStorageService.store(file, analysisId);

        // Then
        assertNotNull(savedPath);
        assertTrue(Files.exists(savedPath));
        assertEquals("heap-dump.hprof", savedPath.getFileName().toString());
        assertEquals("Heap dump content", Files.readString(savedPath));
    }
}
