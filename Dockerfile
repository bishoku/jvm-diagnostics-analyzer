# ===========================================================================
#  Multi-stage Dockerfile for JVM Diagnostics Analyzer
#  Stage 1: Build the Spring Boot fat JAR with Maven
#  Stage 2: Runtime with Eclipse MAT + JDK 25
#
#  Multi-platform: supports linux/amd64 and linux/arm64
#  Build: docker buildx build --platform linux/amd64,linux/arm64 -t jvm-diagnostics .
# ===========================================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Install Maven (no wrapper in this project)
RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

# Cache dependencies first
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Build the application
COPY src src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre

# Install dependencies for Eclipse MAT
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        wget unzip libswt-gtk-4-jni && \
    rm -rf /var/lib/apt/lists/*

# Download and install Eclipse MAT standalone (headless)
# Detects architecture to support both arm64 (Apple Silicon) and amd64 (Intel)
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

# Storage volume for uploaded diagnostic files
RUN mkdir -p /data/uploads
VOLUME /data/uploads

# ---- Environment defaults ----
# These can all be overridden at runtime via docker-compose or docker run -e
ENV APP_STORAGE_LOCATION=/data/uploads
ENV APP_MAT_HOME=/opt/mat
ENV JAVA_OPTS="-Xms512m -Xmx2g"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
