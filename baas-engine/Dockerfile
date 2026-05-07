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
# Copy pom + source and build (skip tests — they require Testcontainers which
# doesn't run inside `docker build`. Tests are run in CI on a host runner
# before image build.)
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ─── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
# JAVA_TOOL_OPTIONS makes the entrypoint configurable without a custom shell wrapper.
# Override at runtime: -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Xlog:gc"
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
