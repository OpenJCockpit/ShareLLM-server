# syntax=docker/dockerfile:1

# Build stage: compile the Spring Boot jar. Tests are not run here because they need Docker
# (Testcontainers); run `mvn -B verify` on the host or in CI before building a release image.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/sharellm-server-*.jar extract --layers --launcher --destination extracted

# Runtime stage: JRE only, fixed non-root UID, dependency layers first for better caching.
FROM eclipse-temurin:25-jre-alpine
LABEL org.opencontainers.image.title="ShareLLM Server" \
      org.opencontainers.image.description="Durable request broker for outbound-only laptop inference workers" \
      org.opencontainers.image.source="https://github.com/OpenJCockpit/ShareLLM-server" \
      org.opencontainers.image.vendor="MetaFactory B.V." \
      org.opencontainers.image.licenses="Apache-2.0"
RUN addgroup -S -g 10001 sharellm && adduser -S -u 10001 -G sharellm sharellm
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
ENV PORT=8080 \
    MANAGEMENT_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 8080: API (put behind an HTTPS reverse proxy). 8081: health probes (keep internal).
EXPOSE 8080 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health/readiness" >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
