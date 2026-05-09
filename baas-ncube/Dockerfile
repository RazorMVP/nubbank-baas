# syntax=docker/dockerfile:1.6
# Base images pinned to manifest-list digests for reproducible builds.
# To refresh: docker buildx imagetools inspect <image>:<tag> --format '{{.Manifest.Digest}}'
# Update both FROM-line digests below; verify tag still points to a multi-arch index.
# Bump deliberately after reviewing upstream release notes.
#
# Multi-stage build for baas-ncube — CBN Open Banking adapter + NIBSS NPS gateway.
# Deployment-agnostic: same pattern as baas-engine.
#
# Build: docker build -t baas-ncube:local -f baas-ncube/Dockerfile baas-ncube
# Run:   docker run --rm -p 8082:8082 --env-file .env baas-ncube:local
#
# Required runtime env vars:
#   DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD (if persisting state)
#   NPS_ENDPOINT — the NIBSS environment URL (sandbox vs prod). Renamed from NIBSS_NPS_BASE_URL in Task 8.

# ─── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine@sha256:a24c967778799ee42665a84d9f94e170ae6dc35788c8d2e218071a086b601768 AS build
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
FROM eclipse-temurin:21-jre-alpine@sha256:ad0cdd9782db550ca7dde6939a16fd850d04e683d37d3cff79d84a5848ba6a5a
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8082/actuator/health/readiness || exit 1
# JVM baseline in ENTRYPOINT args — not overridable via JAVA_TOOL_OPTIONS.
# (Note: _JAVA_OPTIONS and JDK_JAVA_OPTIONS env vars can still influence the JVM.)
# To add JVM flags at deploy time, override containers[].command in the deployment
# spec (k8s args: only reaches Spring Boot, because -jar precedes appended args).
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-XX:ErrorFile=/tmp/hs_err_pid%p.log", "-jar", "/app/app.jar"]
