# syntax=docker/dockerfile:1.6
#
# Multi-stage build for baas-ncube — CBN Open Banking adapter + NIBSS NPS gateway.
# Deployment-agnostic: same pattern as baas-engine.
#
# Build: docker build -t baas-ncube:local -f baas-ncube/Dockerfile baas-ncube
# Run:   docker run --rm -p 8082:8082 --env-file .env baas-ncube:local
#
# Required runtime env vars:
#   DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD (if persisting state)
#   NIBSS_NPS_BASE_URL — the NIBSS environment URL (sandbox vs prod)

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

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8082/actuator/health/readiness || exit 1
# Security baseline baked into ENTRYPOINT — not overridable via JAVA_TOOL_OPTIONS env var.
# To add runtime options at deploy time, append to the deployment's args field.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
