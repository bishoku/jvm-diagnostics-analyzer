# JVM Diagnostics Analyzer

AI-powered analysis for Java heap dumps, thread dumps, and GC logs. Upload your diagnostic files and get actionable insights powered by any OpenAI-compatible LLM. Includes an **MCP server** for AI agent integration and a **built-in AI chat** for conversational heap dump analysis.

## Quick Start

```bash
docker run -d \
  --name jvm-diagnostics \
  -p 8080:8080 \
  -v jvm-uploads:/data/uploads \
  barishoku/jvm-dump-analyzer:latest
```

Open **http://localhost:8080** — a setup wizard will guide you through API key configuration on first launch.

## What It Analyzes

### 🔬 Heap Dumps (`.hprof`)

Upload a heap dump and get:

- **Leak suspect analysis** — identifies what's accumulating and why
- **Memory distribution** — class histogram and package-level breakdown
- **GC root paths** — traces from suspects back to roots
- **JVM configuration assessment** — heap sizing and GC collector recommendations
- **Risk assessment** — severity rating with prioritized fixes

Under the hood, the app uses **Eclipse Memory Analyzer (MAT)** — bundled in the image — to parse the `.hprof` file and extract a structured report, which is then sent to the AI for interpretation.

### 🧵 Thread Dumps (`.tdump`, `.txt`)

Upload a thread dump and get:

- **Thread state analysis** — distribution of RUNNABLE, WAITING, BLOCKED states
- **Deadlock detection** — identifies and explains deadlock chains
- **Bottleneck identification** — lock contention, pool exhaustion, I/O stalls
- **Actionable fixes** — thread pool tuning, lock ordering, connection pool sizing

### 📊 GC Logs

Upload a GC log and get:

- **Pause time analysis** — trends, distributions, and outliers
- **Memory pressure assessment** — leak indicators, promotion rates, humongous allocations
- **Full GC root cause** — what triggers them and how to prevent them
- **Collector-specific tuning** — concrete JVM flags with recommended values

### 🔌 MCP Server (Model Context Protocol)

Exposes heap dump analysis tools via an **SSE-based MCP server** that any AI agent can connect to:

- **7 built-in tools** — `get_heap_summary`, `get_leak_suspects`, `get_class_histogram`, `get_dominator_tree`, `get_top_consumers`, `run_oql_query`, `get_thread_stacks`
- **Standard MCP protocol** — Compatible with Claude Desktop, Cursor, Windsurf, and any MCP-compatible client
- **Session management** — Upload a heap dump on the MCP page, then connect your agent to the SSE endpoint
- **Live tool activity log** — See real-time tool calls and results on the MCP page

Connect to `http://localhost:8080/mcp/sse` after uploading a heap dump file.

### 💬 Built-in AI Chat

Chat with your heap dump directly from the browser — no external agent needed:

- **Conversational analysis** — Ask questions in natural language, get structured Markdown responses
- **Real-time streaming** — Token-by-token LLM responses via Server-Sent Events
- **Automatic tool usage** — The AI calls heap analysis tools as needed to answer your questions
- **Conversation memory** — Context is maintained across messages within a session
- **Collapsible debug info** — See which tools the AI called and with what parameters

## Configuration

### First-Run Setup (Web UI)

On first launch, the app opens a setup wizard at `/setup` where you can configure:

- **API Key** — your OpenAI / OpenRouter / on-prem API key
- **Base URL** — endpoint for your AI provider (default: OpenRouter)
- **Model** — which model to use (default: `openai/gpt-4o`)
- **Temperature** — response randomness (default: `0.3`)
- **Trust insecure TLS** — for on-prem models with self-signed certificates

Settings are persisted in `/root/.jvm-diagnostics/config.properties` inside the container.

You can change these anytime at `/settings`.

### Environment Variables

All settings can also be passed as environment variables (these override the config file):

| Variable | Description | Default |
|---|---|---|
| `OPENROUTER_API_KEY` | API key for AI provider | *(required)* |
| `OPENROUTER_BASE_URL` | AI API endpoint | `https://openrouter.ai/api` |
| `AI_MODEL` | Model name | `openai/gpt-4o` |
| `AI_TEMPERATURE` | Response temperature (0-2) | `0.3` |
| `AI_TRUST_INSECURE_CERTS` | Trust self-signed TLS certs | `false` |
| `APP_STORAGE_LOCATION` | Upload directory | `/data/uploads` |
| `MAT_HEAP_SIZE` | Heap for MAT analysis | `(unset)` |
| `DYNAMIC_MAX_MAT_MEMORY` | Max bound for dynamic MAT heap | `8g` |
| `APP_MAT_TIMEOUT_MINUTES` | MAT analysis timeout | `30` |
| `JAVA_OPTS` | JVM options | `-Xms512m -Xmx2g` |

### Example with Environment Variables

```bash
docker run -d \
  --name jvm-diagnostics \
  -p 8080:8080 \
  -e OPENROUTER_API_KEY=sk-or-v1-your-key \
  -e AI_MODEL=anthropic/claude-sonnet-4 \
  -e MAT_HEAP_SIZE=8g \
  -v jvm-uploads:/data/uploads \
  barishoku/jvm-dump-analyzer:latest
```

### Docker Compose

```yaml
services:
  jvm-diagnostics:
    image: barishoku/jvm-dump-analyzer:latest
    ports:
      - "8080:8080"
    environment:
      - OPENROUTER_API_KEY=sk-or-v1-your-key
      - AI_MODEL=openai/gpt-4o
      - MAT_HEAP_SIZE=4g
      - JAVA_OPTS=-Xms512m -Xmx2g
    volumes:
      - upload-data:/data/uploads
    restart: unless-stopped

volumes:
  upload-data:
```

## Custom Prompts

The system prompts sent to the AI can be fully customized from the **Settings** page (`/settings`). Each analysis type (Heap Dump, Thread Dump, GC Log) has its own prompt that you can edit. The original default prompts are always preserved and can be restored with one click.

## On-Prem / Self-Hosted LLMs

This application works with any OpenAI-compatible API. To use a self-hosted model:

1. Set `OPENROUTER_BASE_URL` to your model's endpoint (e.g., `https://my-llm.internal/v1`)
2. Set `AI_MODEL` to your model name
3. If using self-signed TLS certificates, set `AI_TRUST_INSECURE_CERTS=true`

Compatible with: **Ollama**, **vLLM**, **text-generation-inference**, **LocalAI**, **LMStudio**, and any OpenAI-compatible server.

## Supported Platforms

| Architecture | Status |
|---|---|
| `linux/amd64` | ✅ Supported |
| `linux/arm64` | ✅ Supported |

## Memory Recommendations

| Heap Dump Size | Recommended `MAT_HEAP_SIZE` | Recommended `JAVA_OPTS` |
|---|---|---|
| < 500MB | `2g` | `-Xms256m -Xmx1g` |
| 500MB – 2GB | `4g` | `-Xms512m -Xmx2g` |
| 2GB – 8GB | `8g` | `-Xms1g -Xmx4g` |
| > 8GB | `16g` | `-Xms2g -Xmx8g` |

> **Tip:** `MAT_HEAP_SIZE` controls the heap allocated to Eclipse MAT for parsing `.hprof` files. For large dumps, this needs to be large enough to hold the dump's object graph in memory.

## Persistent Data

Mount a volume to `/data/uploads` to persist uploaded files across container restarts:

```bash
-v /path/on/host:/data/uploads
```

Settings are stored at `/root/.jvm-diagnostics/config.properties`.

## Links

- **GitHub**: [github.com/bishoku/jvm-diagnostics-analyzer](https://github.com/bishoku/jvm-diagnostics-analyzer)
- **Releases**: [Desktop installers (.deb, .exe)](https://github.com/bishoku/jvm-diagnostics-analyzer/releases)

## License

MIT
