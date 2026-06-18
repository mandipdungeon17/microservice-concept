# ────────────────────────────────────────────────────────────
# STAGE 1 — Build: JDK + Gradle. Produces the bootJar only.
#            This entire stage is thrown away after build.
# ────────────────────────────────────────────────────────────

# JDK 21 base image (needs full JDK for Gradle compilation)
FROM eclipse-temurin:21-jdk AS builder

# Metadata — only meaningful on the final image, but harmless here
LABEL authors="Mandip Pandit"

# All build work happens under /build inside the container
WORKDIR /build

# Build arguments passed in via --build-arg at docker build time.
# MODULE_NAME = Gradle project name e.g. user-service  (used in :user-service:bootJar)
# MODULE_DIR  = folder on disk e.g. user               (used to locate the output JAR)
ARG MODULE_NAME
ARG MODULE_DIR

# ── LAYER 1: Gradle wrapper ───────────────────────────────
# Copying the wrapper script and its JAR separately lets Docker cache
# this layer until the Gradle version changes (rare).
COPY gradlew .
COPY gradle/ gradle/
# chmod makes gradlew executable (lost on Windows → Linux copy).
# --version triggers Gradle distribution download — cached for next builds.
RUN chmod +x gradlew && ./gradlew --version

# ── LAYER 2: Build descriptor files ──────────────────────
# Copying only *.gradle and gradle.properties (NOT source yet).
# If source code changes but build files don't, this layer and the
# dependency-download layer below remain cached → much faster rebuild.
COPY build.gradle settings.gradle gradle.properties ./
# Each module's build.gradle is needed so Gradle can resolve the full
# dependency graph for the target module (including commons, transitive deps).
COPY commons/build.gradle commons/
COPY user/build.gradle user/
COPY order/build.gradle order/
COPY portfolio/build.gradle portfolio/
COPY product/build.gradle product/
COPY market-data/build.gradle market-data/
COPY ledger/build.gradle ledger/
COPY notification/build.gradle notification/
COPY discovery-server/build.gradle discovery-server/
COPY config-server/build.gradle config-server/
COPY api-gateway/build.gradle api-gateway/
COPY app/build.gradle app/

# ── LAYER 3: Dependency download ─────────────────────────
# Resolves and downloads all dependencies for the target module.
# Cached as long as any *.gradle file above is unchanged.
# -x test skips test dependency resolution (not needed in image).
RUN ./gradlew :${MODULE_NAME}:dependencies --no-daemon

# ── LAYER 4: Source code ──────────────────────────────────
# Source is copied AFTER dependency download so that code changes
# only invalidate this layer and the bootJar layer below, not deps.
COPY commons/src commons/src
COPY user/src user/src
COPY order/src order/src
COPY portfolio/src portfolio/src
COPY product/src product/src
COPY market-data/src market-data/src
COPY ledger/src ledger/src
COPY notification/src notification/src
COPY discovery-server/src discovery-server/src
COPY config-server/src config-server/src
COPY api-gateway/src api-gateway/src

# ── LAYER 5: Compile and package ─────────────────────────
# Gradle builds only the target module's bootJar (fat JAR with all deps).
# -x test skips unit tests (run tests separately in CI, not during image build).
RUN ./gradlew :${MODULE_NAME}:bootJar --no-daemon -x test

# ────────────────────────────────────────────────────────────
# STAGE 2 — Runtime: JRE only. This becomes the final image.
#            ~200MB vs ~800MB for a JDK image.
# ────────────────────────────────────────────────────────────

# Alpine-based JRE image — minimal OS, only Java runtime (no compiler)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Import corporate Zscaler Root CA into JVM truststore (required for HTTPS through corporate proxy)
#
# WHY: Zscaler (corporate proxy) performs TLS interception — it terminates the original TLS connection
# from the container, inspects traffic, then re-encrypts with its own private CA certificate.
# The JVM's default cacerts bundle only contains public CAs (DigiCert, Let's Encrypt, etc.) and does
# NOT include Zscaler's private root CA. Without this import, any HTTPS call (e.g., Alpha Vantage API,
# Spring Cloud Config Server) throws: javax.net.ssl.SSLHandshakeException: PKIX path building failed.
#
# HOW: keytool -importcert adds the certificate to the JVM's cacerts keystore located at
# /opt/java/openjdk/lib/security/cacerts (Alpine JRE). Unlike Debian-based images where cacerts is
# symlinked to /etc/ssl/certs/java/cacerts, Alpine's Eclipse Temurin stores it as a regular file
# directly in the JDK tree. The -cacerts flag targets this file automatically.
#
# BUILD CONTEXT NOTE: The build context is equitycart/ (parent of docker/), so COPY paths are
# relative to equitycart/, not to the Dockerfile's location. Hence: docker/ZscalerRootCA.pem
# (not just ZscalerRootCA.pem).
COPY docker/ZscalerRootCA.pem /tmp/ZscalerRootCA.pem
RUN keytool -importcert -cacerts -storepass changeit -noprompt \
  -alias zscaler-root-ca -file /tmp/ZscalerRootCA.pem \
  && rm /tmp/ZscalerRootCA.pem

# ARG values reset after every FROM — must re-declare what Stage 2 needs.
# MODULE_NAME is not needed here; only MODULE_DIR (to locate the JAR).
ARG MODULE_DIR

# Copy only the built JAR from Stage 1. Everything else (JDK, Gradle
# cache, source code) is discarded — it never enters this image.
COPY --from=builder /build/${MODULE_DIR}/build/libs/*.jar app.jar

# Document that the app listens on 8080 (informational — does not publish the port)
EXPOSE 8080

# PID 1 process. exec form (JSON array) avoids shell wrapping — signals
# like SIGTERM reach the JVM directly for graceful shutdown.
ENTRYPOINT ["java", "-jar", "app.jar"]