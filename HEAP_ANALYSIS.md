# Heap Dump Analysis — How It Works

This document explains the heap dump analysis pipeline, detailing how Eclipse MAT reports are extracted, structured, and sent to the AI for interpretation.

## Overview

When you upload a `.hprof` heap dump file, the pipeline works as follows:

```
Upload .hprof → Eclipse MAT (headless) → Structured Extraction → AI Analysis → Results
```

1. **Upload**: The file is streamed to disk (supports up to 5 GB)
2. **Eclipse MAT**: Runs headlessly with `ParseHeapDump.sh`, generating two report ZIPs
3. **Extraction**: Report ZIPs are parsed and structured into token-efficient sections
4. **AI Analysis**: The structured report is sent to the LLM with a specialized prompt
5. **Results**: AI-generated Markdown insights are rendered in the browser

## Eclipse MAT Reports

MAT supports three report types. We run **two** of them:

| Report ID | Name | What It Contains |
|---|---|---|
| `org.eclipse.mat.api:suspects` ✅ | Leak Suspects | Root cause candidates, accumulation points, GC root paths |
| `org.eclipse.mat.api:overview` ✅ | System Overview | Class histogram, top consumers, system properties, thread info |
| `org.eclipse.mat.api:top_components` ❌ | Top Components | Detailed component breakdown (too large, not used) |

### Why Not `top_components`?

The `top_components` report produces very large output with deep classloader-level breakdowns. The Leak Suspects + Overview combination provides the most actionable data at a fraction of the size.

## Structured Report Sections

The raw MAT HTML output is parsed and structured into 6 sections, each with its own character budget to stay within the LLM's input limits:

### Section 1: Leak Suspects Summary (~4,000 chars)

Extracted from: `*_Leak_Suspects.zip → index.html`

The main summary page listing each leak suspect with:
- Description of the suspected leak
- Retained heap size and percentage
- Brief identification of the accumulation point

### Section 2: Suspect Details (~3,000 chars)

Extracted from: `*_Leak_Suspects.zip → 1.html, 2.html, 3.html`

Detail pages for the **top 3 suspects**, containing:
- Accumulation point class and field
- Shortest path from the suspect to the nearest GC root
- Object reference chain that prevents garbage collection

This is critical for understanding *why* objects aren't being collected.

### Section 3: Top Classes by Retained Heap (~2,000 chars)

Extracted from: `*_System_Overview.zip → histogram page`

A class-level breakdown showing:
- Class name
- Number of instances
- Retained heap size
- Percentage of total heap

Example output sent to LLM:
```
#1  byte[]                    812 MB  (45.2%)  — 2,341,012 objects
#2  com.app.cache.CacheEntry  312 MB  (17.4%)  — 1,203,445 objects
#3  java.lang.String          89 MB   (4.9%)   — 4,521,003 objects
```

### Section 4: Top Packages by Retained Heap (~1,500 chars)

Extracted from: `*_System_Overview.zip → top_component / package page`

Package-level aggregation showing which modules consume the most memory. Helps identify if the problem is in your application code, a library, or framework internals.

### Section 5: JVM System Properties (~500 chars)

Extracted from: `*_System_Overview.zip → system_properties page`

Filtered to **JVM-relevant settings only**:
- Heap sizing flags: `-Xmx`, `-Xms`, `-Xss`
- GC configuration: `-XX:+UseG1GC`, `-XX:MaxGCPauseMillis`, etc.
- Metaspace settings
- Java version and architecture

Non-relevant properties (locale, temp dirs, etc.) are stripped.

### Section 6: Thread Overview (~500 chars)

Extracted from: `*_System_Overview.zip → thread page`

Thread count and states at the time of the heap dump. Shows if the application was under thread pressure when the dump was captured.

## Token Budget

The total report is capped at **15,000 characters** (~4,000 LLM tokens).

| Section | Budget | Purpose |
|---|---|---|
| Leak Suspects Summary | 4,000 chars | Root cause candidates |
| Suspect Details (top 3) | 3,000 chars | GC root paths, accumulation points |
| Class Histogram (top 25) | 2,000 chars | Memory distribution by class |
| Top Packages (top 15) | 1,500 chars | Memory distribution by package |
| JVM System Properties | 500 chars | Heap/GC configuration |
| Thread Overview | 500 chars | Thread state context |
| **Total** | **~11,500** | Fits well within 15K cap |

The 3,500 char margin allows for section headers, separators, and edge cases where individual sections are slightly larger.

## AI Prompt Design

The LLM receives a system prompt that explicitly references the 6 sections. It is instructed to:

1. **Executive Summary** — Root cause in 2-3 sentences
2. **Leak Suspect Analysis** — Per-suspect deep dive using detail pages
3. **Memory Distribution** — Class/package level analysis using histogram data
4. **JVM Configuration Assessment** — Using system properties to evaluate sizing and GC config
5. **Actionable Recommendations** — Code changes, JVM flags, monitoring
6. **Risk Assessment** — Severity and urgency

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `APP_MAT_HOME` | `/opt/mat` | Path to Eclipse MAT installation |
| `APP_MAT_TIMEOUT_MINUTES` | `30` | MAT analysis timeout |
| `MAT_HEAP_SIZE` | `4g` | Heap allocated to the MAT process |

For very large heap dumps (>2 GB), increase `MAT_HEAP_SIZE` to at least `8g`:

```bash
MAT_HEAP_SIZE=8g
```

## File Locations

After MAT runs, the following files are generated alongside the `.hprof` file:

```
heap-dumps/
├── myapp.hprof                     # Original heap dump
├── myapp_Leak_Suspects.zip         # Leak Suspects report
├── myapp_System_Overview.zip       # System Overview report
├── myapp.0001.index                # MAT index files
├── myapp.dominator.index           # (used internally by MAT)
├── myapp.threads                   # Thread data
└── myapp.*.index                   # Various index files
```

The ZIP files are the ones parsed by `MatAnalysisService`. The index files are used by MAT internally and can be deleted after analysis.
