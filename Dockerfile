# ===========================================================================
#  Multi-stage Dockerfile for Heap Dump Analyzer
#  Stage 1: Build the Spring Boot fat JAR
#  Stage 2: Runtime with Eclipse MAT + JDK 21
# ===========================================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src src
RUN ./mvnw clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jdk

# Install dependencies for Eclipse MAT
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        wget unzip libswt-gtk-4-jni && \
    rm -rf /var/lib/apt/lists/*

# Download and install Eclipse MAT standalone (headless)
# Detects architecture to support both Apple Silicon (aarch64) and Intel (x86_64)
ENV MAT_VERSION=1.16.1
ENV MAT_BUILD=20250109

RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then \
        MAT_ARCH="aarch64"; \
    else \
        MAT_ARCH="x86_64"; \
    fi && \
    MAT_FILE="MemoryAnalyzer-${MAT_VERSION}.${MAT_BUILD}-linux.gtk.${MAT_ARCH}.zip" && \
    echo "Downloading Eclipse MAT: ${MAT_FILE}" && \
    wget -q -O /tmp/mat.zip "https://download.eclipse.org/mat/${MAT_VERSION}/rcp/${MAT_FILE}" && \
    unzip -q /tmp/mat.zip -d /opt/mat-tmp && \
    if [ -d /opt/mat-tmp/mat ]; then cp -r /opt/mat-tmp/mat/. /opt/mat/; else cp -r /opt/mat-tmp/. /opt/mat/; fi && \
    rm -rf /tmp/mat.zip /opt/mat-tmp && \
    chmod +x /opt/mat/ParseHeapDump.sh

# Increase MAT's own heap for analyzing large dumps
RUN echo '-Xmx4g' >> /opt/mat/MemoryAnalyzer.ini

WORKDIR /app

# Copy the built JAR
COPY --from=builder /build/target/*.jar app.jar

# Storage volume for heap dumps
RUN mkdir -p /data/heap-dumps
VOLUME /data/heap-dumps

# Environment defaults
ENV APP_STORAGE_LOCATION=/data/heap-dumps
ENV APP_MAT_HOME=/opt/mat
ENV JAVA_OPTS="-Xms512m -Xmx2g"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar \
    --app.storage.location=$APP_STORAGE_LOCATION \
    --app.mat.home=$APP_MAT_HOME"]
