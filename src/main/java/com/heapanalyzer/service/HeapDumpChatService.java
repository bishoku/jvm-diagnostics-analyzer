package com.heapanalyzer.service;

import com.heapanalyzer.mcp.HeapDumpMcpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Chat service that enables conversational heap dump analysis.
 *
 * <p>
 * Uses the same ChatClient as the rest of the app, but binds the MCP tools
 * directly (no SSE round-trip) so the LLM can invoke heap analysis tools
 * as part of the conversation.
 * </p>
 *
 * <p>
 * Conversation history is managed by Spring AI's
 * {@link MessageChatMemoryAdvisor}
 * with an {@link InMemoryChatMemory} backend, keyed by MCP session ID.
 * </p>
 */
@Service
public class HeapDumpChatService {

    private static final Logger log = LoggerFactory.getLogger(HeapDumpChatService.class);

    static final String SYSTEM_PROMPT = """
            You are an expert JVM Performance Engineer. You help users analyze heap dump files
            that have been uploaded to this application.

            CRITICAL: You MUST always respond in English. All your responses must be in English language
            and formatted in Markdown (use headers, bullet points, code blocks, tables, etc.).

            CORE RULES:
            - Always respond in English with Markdown formatting.
            - Base ALL analysis ONLY on data retrieved from the tools — never guess or fabricate.
            - Never invent class names, package names, byte counts, or percentages not in tool output.
            - If no data is available for a section, state that explicitly.
            - Use EXACT values from tool results for any numerical data (retained heap, object count, percentages).
            - Clearly separate observation from interpretation:
              use "The data shows..." (observation) vs "The reason for this situation is..." (interpretation).

            AVAILABLE TOOLS:
            - get_heap_summary: Heap overview (size, object count, class count, JVM properties)
            - get_leak_suspects: Memory leak suspects with accumulation points and GC root paths
            - get_class_histogram: Per-class memory consumption (supports topN and pattern parameters)
            - get_dominator_tree: Objects dominating the most retained memory
            - get_top_consumers: Biggest consumers grouped by class, classloader, and package
            - run_oql_query: Execute OQL (Object Query Language) queries against the heap dump
            - get_thread_stacks: Thread stack traces with local variables and retained sizes

            STRATEGY:
            1. Understand the user's question.
            2. Call the necessary tools to answer it. If multiple tools are needed, use all of them.
            3. Analyze tool results and provide a clear, structured response.
            4. If initial tool results are insufficient, don't hesitate to call additional tools.
            5. Always provide concrete, actionable recommendations.

            FIRST MESSAGE:
            If the user sends a general greeting like "hello", briefly introduce yourself and
            explain how you can help analyze the heap dump. Suggest example questions they can ask.
            """;

    private final SpringAiService springAiService;
    private final HeapDumpMcpTools heapDumpMcpTools;
    private final McpSessionManager mcpSessionManager;

    /** Spring AI chat memory — stores conversation per session ID. */
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    public HeapDumpChatService(SpringAiService springAiService,
            HeapDumpMcpTools heapDumpMcpTools,
            McpSessionManager mcpSessionManager) {
        this.springAiService = springAiService;
        this.heapDumpMcpTools = heapDumpMcpTools;
        this.mcpSessionManager = mcpSessionManager;
    }

    /**
     * Streams a chat response to the given SseEmitter.
     *
     * <p>
     * SSE event types:
     * <ul>
     * <li>{@code text} — A chunk of the assistant's text response</li>
     * <li>{@code tool_call} — A tool invocation:
     * {@code {"name":"...", "args":"..."}}</li>
     * <li>{@code done} — Stream finished</li>
     * <li>{@code error} — An error occurred</li>
     * </ul>
     */
    public void streamChat(String userMessage, SseEmitter emitter) {
        Thread.startVirtualThread(() -> doStreamChat(userMessage, emitter));
    }

    private void doStreamChat(String userMessage, SseEmitter emitter) {
        try {
            ChatClient chatClient = springAiService.getChatClient();
            if (chatClient == null) {
                sendEvent(emitter, "error", Map.of("message",
                        "AI istemcisi yapılandırılmamış. Lütfen Ayarlar sayfasından API anahtarınızı girin."));
                emitter.complete();
                return;
            }

            var session = mcpSessionManager.getActiveSession();
            if (session == null || !session.isParsed()) {
                sendEvent(emitter, "error", Map.of("message",
                        "Aktif bir heap dump oturumu yok. Lütfen önce bir .hprof dosyası yükleyin."));
                emitter.complete();
                return;
            }

            String sessionId = session.getSessionId();
            log.info("[Chat] User message for session {}: {} chars", sessionId, userMessage.length());

            // Real streaming with tool support + Spring AI memory advisor
            Flux<ChatResponse> responseFlux = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .tools(heapDumpMcpTools)
                    .toolContext(Map.of("sessionId", sessionId))
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                            .conversationId(sessionId)
                            .build())
                    .stream()
                    .chatResponse();

            StringBuilder fullResponse = new StringBuilder();

            responseFlux
                    .doOnNext(chatResponse -> {
                        try {
                            if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                                return;
                            }
                            var output = chatResponse.getResult().getOutput();

                            // Stream text content chunks
                            String text = output.getText();
                            if (text != null && !text.isEmpty()) {
                                fullResponse.append(text);
                                sendEvent(emitter, "text", Map.of("content", text));
                            }

                            // Emit tool call debug events
                            if (output.hasToolCalls()) {
                                for (var toolCall : output.getToolCalls()) {
                                    sendEvent(emitter, "tool_call", Map.of(
                                            "name", toolCall.name(),
                                            "args", toolCall.arguments() != null ? toolCall.arguments() : ""));
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .blockLast(Duration.ofMinutes(5));

            sendEvent(emitter, "done", Map.of());
            emitter.complete();

            log.info("[Chat] Streaming completed for session {} ({} chars)", sessionId, fullResponse.length());

        } catch (Exception e) {
            log.error("[Chat] Error during chat", e);
            try {
                sendEvent(emitter, "error", Map.of("message", "Hata: " + e.getMessage()));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Clears conversation history for a session (called when session is closed).
     */
    public void clearHistory(String sessionId) {
        chatMemory.clear(sessionId);
    }

    private void sendEvent(SseEmitter emitter, String eventType, Map<String, ?> data) throws IOException {
        String json = toJson(data);
        emitter.send(SseEmitter.event()
                .name(eventType)
                .data(json));
    }

    /**
     * Minimal JSON serialization — avoids pulling in Jackson dependency at the
     * service layer.
     */
    private String toJson(Map<String, ?> map) {
        if (map.isEmpty())
            return "{}";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first)
                sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else {
                sb.append(val);
            }
            first = false;
        }
        return sb.append("}").toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
