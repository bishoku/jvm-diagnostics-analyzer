# Docker Image — Build & Publish

This guide covers building, testing, and publishing multi-platform Docker images for the JVM Diagnostics Analyzer.

---

## Prerequisites

- Docker Engine 20.10+ with Buildx
- QEMU (for cross-platform builds on x86 hosts)

```bash
# Install QEMU for arm64 emulation (Ubuntu/Debian)
sudo apt-get install -y qemu-user-static binfmt-support

# Register QEMU handlers
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
```

---

## 1. Local Build & Test

Build for your native platform only (fast, no registry needed):

```bash
docker build -t jvm-diagnostics:local .
```

Run locally:

```bash
docker run -d \
  --name jvm-diagnostics \
  -p 8080:8080 \
  -e OPENROUTER_API_KEY=sk-or-your-key-here \
  -v jvm-uploads:/data/uploads \
  jvm-diagnostics:local
```

Open **http://localhost:8080** and verify all three tools are working.

---

## 2. Multi-Platform Build

### Create a Buildx Builder

You only need to do this once:

```bash
docker buildx create --name multiplatform --driver docker-container --use

# Verify it supports both platforms
docker buildx inspect --bootstrap
# Should list: linux/amd64, linux/arm64
```

### Build for Both Platforms

> **Note:** Multi-platform builds **cannot** be loaded locally — they must be pushed to a registry.

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t your-username/jvm-diagnostics:latest \
  -t your-username/jvm-diagnostics:1.0.0 \
  --push .
```

The arm64 build uses QEMU emulation and takes ~5-10 minutes on an amd64 host.

---

## 3. Publishing

### Docker Hub

```bash
# Login
docker login

# Build and push
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t your-dockerhub-username/jvm-diagnostics:latest \
  -t your-dockerhub-username/jvm-diagnostics:1.0.0 \
  --push .
```

### GitHub Container Registry (ghcr.io)

```bash
# Login (use a Personal Access Token with write:packages scope)
echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin

# Build and push
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t ghcr.io/your-github-username/jvm-diagnostics:latest \
  -t ghcr.io/your-github-username/jvm-diagnostics:1.0.0 \
  --push .
```

After pushing, make the package public in **GitHub → Packages → Package Settings**.

---

## 4. Verify Published Image

```bash
# Check that both architectures are in the manifest
docker manifest inspect your-username/jvm-diagnostics:latest
```

You should see entries for both `amd64` and `arm64` in the output.

---

## 5. Users Pull and Run

Once published, anyone can run it with:

```bash
docker run -d \
  -p 8080:8080 \
  -e OPENROUTER_API_KEY=sk-or-your-key-here \
  your-username/jvm-diagnostics:latest
```

Docker automatically selects the correct architecture for the user's machine (Intel, AMD, or Apple Silicon).

---

## Image Details

| Layer | Base Image | Purpose |
|---|---|---|
| Build stage | `eclipse-temurin:25-jdk` | Compile with Maven |
| Runtime stage | `eclipse-temurin:25-jre` | Smaller runtime (~200 MB less) |
| Eclipse MAT | 1.16.1 | Downloaded during build, arch-aware |

### Supported Platforms

| Platform | Architecture | Typical Use |
|---|---|---|
| `linux/amd64` | x86_64 | Intel/AMD servers, most cloud VMs |
| `linux/arm64` | aarch64 | Apple Silicon (M1/M2/M3), AWS Graviton, ARM servers |

---

## Troubleshooting

**QEMU not registered:**
```bash
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
```

**Buildx builder not found:**
```bash
docker buildx create --name multiplatform --driver docker-container --use
```

**"Cannot load multi-platform result" error:**
Multi-platform builds must use `--push`. To test locally, build for your native platform only:
```bash
docker buildx build --load -t jvm-diagnostics:test .
```
