package com.heapanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Manages application configuration persisted to ~/.jvm-diagnostics/config.properties.
 *
 * <p>Config file is created on first save. On startup, env vars take precedence
 * over file values, which take precedence over defaults.</p>
 *
 * <p>Priority: ENV variable → config file → application.yml defaults</p>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private static final String CONFIG_DIR = ".jvm-diagnostics";
    private static final String CONFIG_FILE = "config.properties";

    // Property keys
    public static final String KEY_API_KEY = "api-key";
    public static final String KEY_BASE_URL = "base-url";
    public static final String KEY_MODEL = "model";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_TRUST_INSECURE = "trust-insecure-certs";

    // Defaults
    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api";
    private static final String DEFAULT_MODEL = "openai/gpt-4o";
    private static final String DEFAULT_TEMPERATURE = "0.3";

    private final Path configFile;
    private final Path promptsDir;
    private final Properties properties = new Properties();

    public ConfigService() {
        Path configDir = Path.of(System.getProperty("user.home"), CONFIG_DIR);
        this.configFile = configDir.resolve(CONFIG_FILE);
        this.promptsDir = configDir.resolve("prompts");
    }

    // Visible for testing
    ConfigService(Path configFile) {
        this.configFile = configFile;
        this.promptsDir = configFile.getParent().resolve("prompts");
    }

    @PostConstruct
    public void load() {
        if (Files.exists(configFile)) {
            try (InputStream is = Files.newInputStream(configFile)) {
                properties.load(is);
                log.info("Loaded config from {}", configFile);
            } catch (IOException e) {
                log.warn("Failed to load config from {}: {}", configFile, e.getMessage());
            }
        } else {
            log.info("No config file found at {}, will use defaults/env vars", configFile);
        }
    }

    /**
     * Returns true if an API key is available (from env or config file).
     */
    public boolean isConfigured() {
        String key = getApiKey();
        return key != null && !key.isBlank()
                && !key.equals("sk-placeholder")
                && !key.equals("sk-or-your-key-here");
    }

    public String getApiKey() {
        // ENV var takes precedence
        String envKey = System.getenv("OPENROUTER_API_KEY");
        if (envKey != null && !envKey.isBlank()
                && !envKey.equals("sk-placeholder")
                && !envKey.equals("sk-or-your-key-here")) {
            return envKey;
        }
        return properties.getProperty(KEY_API_KEY);
    }

    public String getBaseUrl() {
        String env = System.getenv("OPENROUTER_BASE_URL");
        if (env != null && !env.isBlank()) return env;
        return properties.getProperty(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public String getModel() {
        String env = System.getenv("AI_MODEL");
        if (env != null && !env.isBlank()) return env;
        return properties.getProperty(KEY_MODEL, DEFAULT_MODEL);
    }

    public double getTemperature() {
        String env = System.getenv("AI_TEMPERATURE");
        if (env != null && !env.isBlank()) {
            try { return Double.parseDouble(env); } catch (NumberFormatException ignored) {}
        }
        try {
            return Double.parseDouble(properties.getProperty(KEY_TEMPERATURE, DEFAULT_TEMPERATURE));
        } catch (NumberFormatException e) {
            return 0.3;
        }
    }

    /**
     * Returns true if insecure TLS certificates should be trusted.
     * For on-prem models with self-signed certificates.
     */
    public boolean isTrustInsecureCerts() {
        String env = System.getenv("AI_TRUST_INSECURE_CERTS");
        if (env != null && !env.isBlank()) return Boolean.parseBoolean(env);
        return Boolean.parseBoolean(properties.getProperty(KEY_TRUST_INSECURE, "false"));
    }

    /**
     * Saves settings to the config file and updates in-memory properties.
     */
    public void save(String apiKey, String baseUrl, String model, String temperature) {
        save(apiKey, baseUrl, model, temperature, null);
    }

    public void save(String apiKey, String baseUrl, String model, String temperature, String trustInsecure) {
        properties.setProperty(KEY_API_KEY, apiKey);
        properties.setProperty(KEY_BASE_URL, baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        properties.setProperty(KEY_MODEL, model != null ? model : DEFAULT_MODEL);
        properties.setProperty(KEY_TEMPERATURE, temperature != null ? temperature : DEFAULT_TEMPERATURE);
        properties.setProperty(KEY_TRUST_INSECURE, trustInsecure != null ? trustInsecure : "false");

        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream os = Files.newOutputStream(configFile)) {
                properties.store(os, "JVM Diagnostics Analyzer — AI Configuration");
            }
            log.info("Config saved to {}", configFile);
        } catch (IOException e) {
            log.error("Failed to save config to {}: {}", configFile, e.getMessage());
        }
    }

    /**
     * Returns a masked version of the API key for display.
     */
    public String getMaskedApiKey() {
        String key = getApiKey();
        if (key == null || key.length() < 8) return "Not configured";
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
    }

    public Path getConfigFilePath() {
        return configFile;
    }

    // ========================== Prompt Management ==========================

    /**
     * Returns the custom prompt for the given type, or null if using default.
     */
    public String getCustomPrompt(String promptType) {
        Path promptFile = promptsDir.resolve(promptType + ".txt");
        if (Files.exists(promptFile)) {
            try {
                return Files.readString(promptFile);
            } catch (IOException e) {
                log.warn("Failed to read custom prompt {}: {}", promptType, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Saves a custom prompt for the given type.
     */
    public void savePrompt(String promptType, String content) {
        try {
            Files.createDirectories(promptsDir);
            Files.writeString(promptsDir.resolve(promptType + ".txt"), content);
            log.info("Custom prompt saved: {}", promptType);
        } catch (IOException e) {
            log.error("Failed to save custom prompt {}: {}", promptType, e.getMessage());
        }
    }

    /**
     * Resets a prompt to default by deleting the custom file.
     */
    public void resetPrompt(String promptType) {
        try {
            Files.deleteIfExists(promptsDir.resolve(promptType + ".txt"));
            log.info("Prompt reset to default: {}", promptType);
        } catch (IOException e) {
            log.warn("Failed to reset prompt {}: {}", promptType, e.getMessage());
        }
    }

    /**
     * Returns true if the user has set a custom prompt.
     */
    public boolean hasCustomPrompt(String promptType) {
        return Files.exists(promptsDir.resolve(promptType + ".txt"));
    }
}
