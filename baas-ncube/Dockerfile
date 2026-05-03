# syntax=docker/dockerfile:1.6
#
# Multi-stage build for baas-ncube — CBN Open Banking adapter + NIBSS NPS gateway.
# Deployment-agnostic: same pattern as baas-engine.
#
# Build: docker build -t baas-ncube:local -f baas-ncube/Dockerfile baas-ncube
# Run:   docker run --rm -p 8081:8081 --env-file .env baas-ncube:local
#
# Required runtime env vars:
#   DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD (if persisting state)
#   NIBSS_NPS_BASE_URL — the NIBSS environment URL (sandbox vs prod)

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
