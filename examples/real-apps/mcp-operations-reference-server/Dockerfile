# syntax=docker/dockerfile:1.7
ARG APP_VERSION=1.0.0-SNAPSHOT
ARG SOURCE_COMMIT=
ARG SOURCE_BRANCH=
ARG BUILD_COMMIT=unknown
ARG BUILD_BRANCH=unknown
ARG BUILD_TIME=

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -V --no-transfer-progress \
    -pl mcp-operations-reference-server -am package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
ARG APP_VERSION
ARG SOURCE_COMMIT
ARG SOURCE_BRANCH
ARG BUILD_COMMIT
ARG BUILD_BRANCH
ARG BUILD_TIME
ENV PORT=8106
ENV JAVA_OPTS=""
ENV APP_VERSION="${APP_VERSION}"
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data \
    && chown -R app:app /app
RUN actual_build_time="${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" \
    && actual_commit="${SOURCE_COMMIT:-${BUILD_COMMIT:-unknown}}" \
    && actual_branch="${SOURCE_BRANCH:-${BUILD_BRANCH:-unknown}}" \
    && printf 'version=%s\ncommit=%s\nbranch=%s\nbuiltAt=%s\naiFabricVersion=%s\n' \
      "${APP_VERSION}" "${actual_commit}" "${actual_branch}" "${actual_build_time}" "n/a" \
      > /app/build-info.properties \
    && chown app:app /app/build-info.properties
COPY --from=build --chown=app:app \
    /workspace/mcp-operations-reference-server/target/mcp-operations-reference-server-*.jar \
    /app/app.jar
USER app:app
EXPOSE 8106
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
