# syntax=docker/dockerfile:1.6
# Base images pinned to manifest-list digests for reproducible builds.
# To refresh: docker buildx imagetools inspect <image>:<tag> --format '{{.Manifest.Digest}}'
# Update both FROM-line digests below; verify tag still points to a multi-arch index.
# Bump deliberately after reviewing upstream release notes.
#
# Multi-stage build for baas-fep.
# Deployment-agnostic: produces a generic Linux container image runnable on
# Docker, Podman, containerd, k8s, or any OCI-compatible runtime — no
# cloud-provider-specific build steps.
#
# Build:   docker build -t baas-fep:local -f baas-fep/Dockerfile baas-fep
# Run:     docker run --rm -p 8082:8082 -p 8583:8583 --env-file .env baas-fep:local
#
# Required runtime env vars (no defaults — startup fails fast if missing):
#   INTERNAL_SERVICE_SECRET
#   CARD_BASE_URL (defaults to http://baas-card:8081)

# ─── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine@sha256:856fe78872c59d3b584a64d07693d5433cbf44e35b5ba1f2c911147f39f54266 AS build
WORKDIR /workspace
# Layer 1: dependencies. pom.xml only — does not invalidate when src/ changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
# Layer 2: package. src/ change invalidates only this layer; deps stay cached.
# Tests are skipped here — they require a running environment which doesn't run
# inside `docker build`. Tests run in CI on a host runner before image build.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ─── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine@sha256:704db3c40204a44f471191446ddd9cda5d60dab40f0e15c6507b815ed897238b
# Apply latest Alpine package patches before installing curl.
RUN apk upgrade --no-cache && \
    apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
RUN chown -R app:app /app
USER app
# HTTP API port (actuator only — no partner-facing endpoints on FEP)
EXPOSE 8082
# ISO 8583 TCP socket server port
EXPOSE 8583
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8082/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-XX:ErrorFile=/tmp/hs_err_pid%p.log", "-jar", "/app/app.jar"]
