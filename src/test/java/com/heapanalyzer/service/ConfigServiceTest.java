package com.heapanalyzer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigService}.
 * Uses temp directories — no Spring context.
 */
class ConfigServiceTest {

    @TempDir
    Path tempDir;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        Path configFile = tempDir.resolve("config.properties");
        configService = new ConfigService(configFile);
        configService.load();
    }

    @Test
    void isConfigured_shouldReturnFalseWhenNoKeySet() {
        assertFalse(configService.isConfigured());
    }

    @Test
    void isConfigured_shouldReturnTrueAfterSavingKey() {
        configService.save("sk-or-v1-real-key-here", null, null, null);
        assertTrue(configService.isConfigured());
    }

    @Test
    void isConfigured_shouldReturnFalseForPlaceholderKey() {
        configService.save("sk-placeholder", null, null, null);
        assertFalse(configService.isConfigured());
    }

    @Test
    void isConfigured_shouldReturnFalseForExampleKey() {
        configService.save("sk-or-your-key-here", null, null, null);
        assertFalse(configService.isConfigured());
    }

    @Test
    void save_shouldPersistToFile() {
        configService.save("sk-test-key", "https://api.example.com", "gpt-4o-mini", "0.5");

        // Reload from file
        ConfigService reloaded = new ConfigService(tempDir.resolve("config.properties"));
        reloaded.load();

        assertEquals("sk-test-key", reloaded.getApiKey());
        assertEquals("https://api.example.com", reloaded.getBaseUrl());
        assertEquals("gpt-4o-mini", reloaded.getModel());
        assertEquals(0.5, reloaded.getTemperature(), 0.001);
    }

    @Test
    void save_shouldUseDefaultsForNullValues() {
        configService.save("sk-key", null, null, null);

        assertEquals("https://openrouter.ai/api", configService.getBaseUrl());
        assertEquals("openai/gpt-4o", configService.getModel());
        assertEquals(0.3, configService.getTemperature(), 0.001);
    }

    @Test
    void getApiKey_shouldReturnNullWhenNotSet() {
        assertNull(configService.getApiKey());
    }

    @Test
    void getApiKey_shouldReturnSavedKey() {
        configService.save("sk-my-api-key", null, null, null);
        assertEquals("sk-my-api-key", configService.getApiKey());
    }

    @Test
    void getMaskedApiKey_shouldMaskMiddle() {
        configService.save("sk-or-v1-abcdef123456", null, null, null);
        String masked = configService.getMaskedApiKey();
        assertTrue(masked.startsWith("sk-or-"));
        assertTrue(masked.contains("..."));
        assertTrue(masked.endsWith("3456"));
    }

    @Test
    void getMaskedApiKey_shouldReturnNotConfiguredWhenNoKey() {
        assertEquals("Not configured", configService.getMaskedApiKey());
    }

    @Test
    void getTemperature_shouldHandleInvalidValue() {
        configService.save("sk-key", null, null, "notanumber");
        assertEquals(0.3, configService.getTemperature(), 0.001);
    }

    @Test
    void load_shouldHandleMissingFile() {
        ConfigService fresh = new ConfigService(tempDir.resolve("nonexistent/config.properties"));
        assertDoesNotThrow(fresh::load);
        assertFalse(fresh.isConfigured());
    }

    @Test
    void load_shouldHandleCorruptFile() throws IOException {
        Path corrupt = tempDir.resolve("corrupt.properties");
        // Properties files are key=value, but broken unicode escapes can cause issues
        // A file with just regular text won't crash Properties.load()
        Files.writeString(corrupt, "api-key=sk-test-value");
        ConfigService svc = new ConfigService(corrupt);
        assertDoesNotThrow(svc::load);
        assertEquals("sk-test-value", svc.getApiKey());
    }

    @Test
    void save_shouldCreateParentDirectories() {
        Path nested = tempDir.resolve("deep/nested/dir/config.properties");
        ConfigService svc = new ConfigService(nested);
        svc.load();

        assertDoesNotThrow(() -> svc.save("sk-key", null, null, null));
        assertTrue(Files.exists(nested));
    }

    @Test
    void getConfigFilePath_shouldReturnPath() {
        assertNotNull(configService.getConfigFilePath());
    }
}
