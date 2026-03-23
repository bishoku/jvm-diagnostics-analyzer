package com.heapanalyzer.service;

import com.heapanalyzer.model.McpLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class McpLogService {

    private static final Logger log = LoggerFactory.getLogger(McpLogService.class);
    private static final int MAX_LOGS_PER_SESSION = 500;

    // session ID -> bounded history of logs
    private final Map<String, Queue<McpLogEntry>> sessionLogs = new ConcurrentHashMap<>();
    
    // session ID -> list of connected SSE clients watching logs
    private final Map<String, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();

    /**
     * Subscribe a client to a specific MCP session's live logs.
     */
    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        
        List<SseEmitter> emitters = sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable onCompletion = () -> {
            emitters.remove(emitter);
            log.debug("[MCP-SSE] Emitter closed for session {}", sessionId);
        };
        
        emitter.onCompletion(onCompletion);
        emitter.onTimeout(() -> { emitter.complete(); onCompletion.run(); });
        emitter.onError(e -> { log.debug("[MCP-SSE] Emitter error: {}", e.getMessage()); onCompletion.run(); });

        log.info("[MCP-SSE] New log subscription for session {}", sessionId);

        return emitter;
    }

    /**
     * Gets recent history for a session to populate UI on connect.
     */
    public List<McpLogEntry> getRecentLogs(String sessionId) {
        Queue<McpLogEntry> logs = sessionLogs.get(sessionId);
        if (logs == null) return List.of();
        return new ArrayList<>(logs);
    }

    /**
     * Add a log payload and broadcast to connected UI clients.
     */
    public void addLog(String sessionId, McpLogEntry entry) {
        Queue<McpLogEntry> logs = sessionLogs.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());
        logs.add(entry);
        
        // Maintain bounds
        while (logs.size() > MAX_LOGS_PER_SESSION) {
            logs.poll();
        }

        // Broadcast to SSE clients
        List<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null && !emitters.isEmpty()) {
            List<SseEmitter> deadEmitters = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("mcpLog").data(entry));
                } catch (IOException e) {
                    deadEmitters.add(emitter);
                }
            }
            emitters.removeAll(deadEmitters);
        }
    }

    /**
     * Complete emitters and clear logs for close sessions.
     */
    public void clearSession(String sessionId) {
        log.info("[MCP-SSE] Clearing logs and emitters for closed session {}", sessionId);
        sessionLogs.remove(sessionId);
        
        List<SseEmitter> emitters = sessionEmitters.remove(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    // Send an EOF/close event if wanted, then complete.
                    emitter.send(SseEmitter.event().name("sessionClosed").data("closed"));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }
    }
}
