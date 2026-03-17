# 🔬 Heap Dump Analyzer

A Spring Boot application that analyzes JVM heap dump (`.hprof`) files using **Eclipse MAT** for static analysis and **OpenAI GPT-4o** (via [OpenRouter](https://openrouter.ai)) for AI-powered, actionable memory leak insights.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-green) ![Spring AI](https://img.shields.io/badge/Spring%20AI-OpenRouter-purple)

---

## Features

- **Large file support** — Streams uploads (up to 5 GB) directly to disk; no in-memory buffering
- **Real Eclipse MAT analysis** — Runs `ParseHeapDump.sh` headlessly to produce Leak Suspects, System Overview, and Top Components reports
- **AI-powered insights** — Sends the MAT report to GPT-4o for root-cause analysis and code-level fix recommendations
- **Async pipeline** — Upload, MAT analysis, and AI generation run asynchronously with live status polling
- **Modern UI** — Dark-themed Tailwind CSS interface with drag-and-drop upload, progress tracking, and markdown-rendered results

---

## Prerequisites

| Requirement | Details |
|---|---|
| **Java 21** | Required for local builds |
| **Docker** | Required for the recommended Docker-based setup |
| **OpenRouter API Key** | Get one at [openrouter.ai/keys](https://openrouter.ai/keys) |

---

## Quick Start (Docker — Recommended)

Docker is the easiest way to run the app because the image **bundles Eclipse MAT** automatically.

```bash
# 1. Clone the project
cd heap-dump-analyze

# 2. Set your OpenRouter API key
export OPENROUTER_API_KEY=sk-or-your-key-here

# 3. Build and run
docker compose up --build
```

Open **http://localhost:8080** in your browser.

---

## Running Locally (Without Docker)

If you prefer running outside Docker, you need Eclipse MAT installed on your machine.

### 1. Install Eclipse MAT

Download the **standalone** version from [eclipse.org/mat](https://eclipse.dev/mat/downloads.php) and unzip it.

### 2. Set Environment Variables

```bash
export APP_MAT_HOME=/path/to/mat          # directory containing ParseHeapDump.sh
export OPENROUTER_API_KEY=sk-or-your-key-here
```

### 3. Build and Run

```bash
./mvnw spring-boot:run
```

Open **http://localhost:8080**.

---

## Usage

1. **Upload** — Drag and drop (or browse) a `.hprof` file on the landing page
2. **Wait** — The UI shows a live pipeline:
   - 📤 **Uploading** — File streams to disk
   - 🔬 **Eclipse MAT** — Static analysis runs (~1–10 min depending on dump size)
   - 🤖 **AI Analysis** — GPT-4o interprets the MAT report
   - ✅ **Done** — Results appear
3. **Review** — Expand the raw MAT report or read the AI's markdown-formatted recommendations

---

## Configuration

All settings are in `src/main/resources/application.yml` and can be overridden via environment variables:

| Property | Env Variable | Default | Description |
|---|---|---|---|
| `app.storage.location` | `APP_STORAGE_LOCATION` | `./heap-dumps` | Directory for uploaded files |
| `app.mat.home` | `APP_MAT_HOME` | `/opt/mat` | Path to Eclipse MAT installation |
| `app.mat.timeout-minutes` | — | `30` | Max time for MAT analysis |
| `spring.ai.openai.api-key` | `OPENROUTER_API_KEY` | — | Your OpenRouter API key |
| `spring.ai.openai.base-url` | — | `https://openrouter.ai/api/v1` | OpenRouter endpoint |
| `spring.ai.openai.chat.options.model` | — | `openai/gpt-4o` | Model to use (any [OpenRouter model](https://openrouter.ai/models)) |
| `spring.servlet.multipart.max-file-size` | — | `5GB` | Max upload size |

---

## Project Structure

```
src/main/java/com/heapanalyzer/
├── HeapDumpAnalyzerApplication.java   # Entry point
├── config/
│   └── AsyncConfig.java               # Thread pool for async analysis
├── controller/
│   └── AnalysisController.java        # REST API + UI route
├── model/
│   ├── AnalysisState.java             # Per-job state holder
│   └── AnalysisStatus.java            # Lifecycle enum
└── service/
    ├── AnalysisService.java           # Async pipeline orchestrator
    ├── FileStorageService.java        # Stream-to-disk uploads
    ├── MatAnalysisService.java        # Eclipse MAT subprocess
    └── SpringAiService.java           # OpenAI via Spring AI
```

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Web UI |
| `POST` | `/api/analysis/upload` | Upload `.hprof` file (multipart) → returns `{ analysisId }` |
| `GET` | `/api/analysis/{id}/status` | Poll analysis state and results |

---

## Docker Notes

The `Dockerfile` uses a multi-stage build:

1. **Builder stage** — Compiles the Spring Boot fat JAR with Maven
2. **Runtime stage** — Eclipse Temurin JDK 21 + Eclipse MAT standalone

Eclipse MAT inside the container is configured with `-Xmx4g` by default. For very large dumps (>3 GB), you may need to increase this in the Dockerfile and allocate more memory to the container:

```bash
docker compose up --build -d
docker update --memory=8g heap-dump-analyze-heap-analyzer-1
```

---

## How to Capture a Heap Dump

You need a `.hprof` file to use this tool. Here are the most common ways to get one from a running Java application.

### Using `jmap` (JDK tool)

```bash
# Find the PID of your Java process
jps -l

# Capture a heap dump (replace <PID>)
jmap -dump:format=b,file=heapdump.hprof <PID>
```

### Using `jcmd` (recommended, JDK 8+)

```bash
jcmd <PID> GC.heap_dump /path/to/heapdump.hprof
```

### Automatic Dump on OutOfMemoryError

Add these JVM flags when starting your application to automatically generate a heap dump when OOM occurs:

```bash
java -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/path/to/dumps/ \
     -jar your-app.jar
```

### From a Docker Container

```bash
# Find the Java PID inside the container (usually 1)
docker exec <container> jcmd 1 GC.heap_dump /tmp/heapdump.hprof

# Copy the dump to your host machine
docker cp <container>:/tmp/heapdump.hprof ./heapdump.hprof
```

### From Kubernetes

```bash
# Exec into the pod and dump
kubectl exec <pod-name> -- jcmd 1 GC.heap_dump /tmp/heapdump.hprof

# Copy to local machine
kubectl cp <pod-name>:/tmp/heapdump.hprof ./heapdump.hprof
```

> **Tip:** Heap dumps capture the full contents of JVM memory, so the `.hprof` file will be roughly the same size as your `-Xmx` heap setting. Make sure you have enough disk space.

---

## License

MIT
