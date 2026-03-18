package com.heapanalyzer.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Streams uploaded multipart files directly to disk,
 * avoiding loading the entire file into JVM heap memory.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path storageLocation;

    public FileStorageService(@Value("${app.storage.location:./heap-dumps}") String storageDir) {
        this.storageLocation = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(storageLocation);
            log.info("Storage directory initialized at: {}", storageLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not create storage directory: " + storageLocation, e);
        }
    }

    /**
     * Streams the multipart file to disk.
     *
     * @param file       the uploaded file
     * @param analysisId unique ID used as the sub-directory name
     * @return the Path where the file was saved
     */
    public Path store(MultipartFile file, String analysisId) throws IOException {
        Path targetDir = storageLocation.resolve(analysisId);
        Files.createDirectories(targetDir);

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "heap-dump.hprof";
        } else {
            Path fileNamePath = Paths.get(originalFilename).getFileName();
            if (fileNamePath == null) {
                originalFilename = "heap-dump.hprof";
            } else {
                originalFilename = fileNamePath.toString();
            }
        }

        Path targetPath = targetDir.resolve(originalFilename);

        // Stream directly to disk — no byte[] buffering
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Stored file {} ({} bytes) at {}", originalFilename, Files.size(targetPath), targetPath);
        return targetPath;
    }
}
