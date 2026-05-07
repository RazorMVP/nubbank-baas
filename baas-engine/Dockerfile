# syntax=docker/dockerfile:1.6
#
# Multi-stage build for baas-engine.
# Deployment-agnostic: produces a generic Linux container image runnable on
# Docker, Podman, containerd, k8s, or any OCI-compatible runtime — no
# cloud-provider-specific build steps.
#
# Build:   docker build -t baas-engine:local -f baas-engine/Dockerfile baas-engine
# Run:     docker run --rm -p 8080:8080 --env-file .env baas-engine:local
#
# Required runtime env vars (no defaults — startup fails fast if missing):
#   DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD
#   JWT_SECRET, ENCRYPTION_KEY

# ─── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
# Layer 1: dependencies. pom.xml only — does not invalidate when src/ changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
# Layer 2: package. src/ change invalidates only this layer; deps stay cached.
# Tests are skipped here — they require Testcontainers which doesn't run inside
# `docker build`. Tests run in CI on a host runner before image build.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ─── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
# JVM baseline in ENTRYPOINT args — not overridable via JAVA_TOOL_OPTIONS.
# (Note: _JAVA_OPTIONS and JDK_JAVA_OPTIONS env vars can still influence the JVM.)
# To add JVM flags at deploy time, override containers[].command in the deployment
# spec (k8s args: only reaches Spring Boot, because -jar precedes appended args).
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
