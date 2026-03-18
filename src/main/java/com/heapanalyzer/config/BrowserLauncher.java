package com.heapanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.net.URI;

/**
 * Opens the default browser when the application is fully started.
 *
 * <p>Respects the {@code app.browser.auto-open} property — set to {@code false}
 * to disable (useful for Docker, CI, or running behind a reverse proxy).</p>
 */
@Component
public class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final Environment environment;

    public BrowserLauncher(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        boolean autoOpen = environment.getProperty("app.browser.auto-open", Boolean.class, true);
        if (!autoOpen) {
            log.info("Browser auto-open disabled (app.browser.auto-open=false)");
            return;
        }

        // Don't try in headless environments (Docker, CI)
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Headless environment detected — skipping browser launch");
            return;
        }

        String port = environment.getProperty("server.port", "8080");
        String url = "http://localhost:" + port;

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                log.info("Browser opened: {}", url);
            } else {
                // Fallback: try OS-specific commands
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
                log.info("Browser opened via OS command: {}", url);
            }
        } catch (Exception e) {
            log.warn("Could not open browser automatically: {}. Visit {} manually.", e.getMessage(), url);
        }
    }
}
