package com.heapanalyzer.mcp;

import com.heapanalyzer.model.HeapDumpSession;
import com.heapanalyzer.service.MatQueryService;
import com.heapanalyzer.service.McpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for interactive heap dump analysis.
 *
 * <p>Each method is exposed as an MCP tool that coding agents (Claude Code,
 * GitHub Copilot, Antigravity, Cursor) can invoke to query a loaded heap dump.</p>
 *
 * <p>A heap dump must be uploaded and parsed via the MCP page before tools are usable.</p>
 */
@Component
public class HeapDumpMcpTools {

    private static final Logger log = LoggerFactory.getLogger(HeapDumpMcpTools.class);

    private final McpSessionManager sessionManager;
    private final MatQueryService matQueryService;

    public HeapDumpMcpTools(McpSessionManager sessionManager, MatQueryService matQueryService) {
        this.sessionManager = sessionManager;
        this.matQueryService = matQueryService;
    }

    @Tool(name = "get_heap_summary",
          description = "Get heap dump overview: total heap size, object count, class count, class loaders, GC roots, JVM system properties. " +
                        "Returns: heapSize, objectCount, classCount, classLoaderCount, gcRoots, unreachableObjects, systemProperties(java.version, os.name, etc)")
    public String getHeapSummary() {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            return matQueryService.getHeapSummary(session.getHprofPath());
        } catch (Exception e) {
            log.error("[MCP] get_heap_summary failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_leak_suspects",
          description = "Get memory leak suspects with accumulation points, retained heap sizes, and shortest paths to GC roots. " +
                        "Returns: ranked suspects with className, retainedHeap bytes, percentage of total heap, accumulation point path. " +
                        "Use when: investigating OutOfMemoryError or unexpectedly high memory usage")
    public String getLeakSuspects() {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            return matQueryService.getLeakSuspects(session.getHprofPath());
        } catch (Exception e) {
            log.error("[MCP] get_leak_suspects failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_class_histogram",
          description = "Get top classes ranked by retained heap size. " +
                        "Params: topN(default 30, max 200), pattern(Java regex e.g. 'com\\.myapp\\..*'). " +
                        "Returns per class: className, objectCount, shallowHeap, retainedHeap. " +
                        "Use when: identifying which classes consume the most memory")
    public String getClassHistogram(
            @ToolParam(description = "Number of top classes to return (default 30)") Integer topN,
            @ToolParam(description = "Java regex to filter class names, e.g. 'com\\.myapp\\..*'") String pattern) {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            int n = (topN != null && topN > 0) ? Math.min(topN, 200) : 30;
            return matQueryService.getClassHistogram(session.getHprofPath(), n, pattern);
        } catch (Exception e) {
            log.error("[MCP] get_class_histogram failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_dominator_tree",
          description = "Get top objects that dominate (retain) the most memory. " +
                        "Params: topN(default 20, max 100). " +
                        "Returns per object: className, objectAddress, shallowHeap, retainedHeap, percentage. " +
                        "Use when: finding the single largest memory-holding objects")
    public String getDominatorTree(
            @ToolParam(description = "Number of top dominator objects to return (default 20)") Integer topN) {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            int n = (topN != null && topN > 0) ? Math.min(topN, 100) : 20;
            return matQueryService.getDominatorTree(session.getHprofPath(), n);
        } catch (Exception e) {
            log.error("[MCP] get_dominator_tree failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_top_consumers",
          description = "Get biggest memory consumers grouped by class, classloader, and package. " +
                        "Returns three ranked lists: topByClass(className, retainedHeap), topByClassLoader, topByPackage. " +
                        "Use when: getting a high-level breakdown of memory distribution")
    public String getTopConsumers() {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            return matQueryService.getTopConsumers(session.getHprofPath());
        } catch (Exception e) {
            log.error("[MCP] get_top_consumers failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "run_oql_query",
          description = "Execute OQL (Object Query Language) query against the heap dump. " +
                        "Syntax: SELECT [fields] FROM [class] [WHERE condition]. " +
                        "Examples: 'SELECT toString(s), s.@retainedHeapSize FROM java.lang.String s WHERE s.@retainedHeapSize > 1000000', " +
                        "'SELECT c.name.toString(), c.@retainedHeapSize FROM java.lang.Class c WHERE c.@retainedHeapSize > 500000'. " +
                        "Returns up to 100 rows. Use when: custom queries not covered by other tools")
    public String runOqlQuery(
            @ToolParam(description = "OQL query string starting with SELECT") String query) {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            return matQueryService.runOqlQuery(session.getHprofPath(), query);
        } catch (Exception e) {
            log.error("[MCP] run_oql_query failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_thread_stacks",
          description = "Get thread stack traces from the heap dump with local variables and retained sizes at dump time. " +
                        "Returns per thread: threadName, state, stackFrames with localVars and retainedHeap. " +
                        "Use when: investigating deadlocks, blocked threads, or thread-local memory leaks")
    public String getThreadStacks() {
        HeapDumpSession session = requireActiveSession();
        try {
            session.touch();
            return matQueryService.getThreadStacks(session.getHprofPath());
        } catch (Exception e) {
            log.error("[MCP] get_thread_stacks failed", e);
            return "Error: " + e.getMessage();
        }
    }

    // ========================== Internal ==========================

    private HeapDumpSession requireActiveSession() {
        HeapDumpSession session = sessionManager.getActiveSession();
        if (session == null) {
            throw new IllegalStateException(
                    "No active heap dump session. Upload a .hprof file via the MCP page at /mcp first.");
        }
        if (!session.isParsed()) {
            if (session.getParseError() != null) {
                throw new IllegalStateException(
                        "Heap dump parsing failed: " + session.getParseError());
            }
            throw new IllegalStateException(
                    "Heap dump is still being parsed. Please wait a moment and try again.");
        }
        return session;
    }
}
