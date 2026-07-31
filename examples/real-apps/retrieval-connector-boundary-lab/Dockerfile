# syntax=docker/dockerfile:1.7
ARG AI_FABRIC_VERSION=0.5.2
ARG APP_VERSION=1.0.0-SNAPSHOT
ARG SOURCE_COMMIT=
ARG SOURCE_BRANCH=
ARG BUILD_COMMIT=unknown
ARG BUILD_BRANCH=unknown
ARG BUILD_TIME=

FROM maven:3.9-eclipse-temurin-21 AS build
ARG AI_FABRIC_VERSION
WORKDIR /workspace
COPY . .
RUN mvn -B -V --no-transfer-progress \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -pl retrieval-connector-boundary-lab -am package

FROM eclipse-temurin:21-jre-alpine AS runtime-base
WORKDIR /app

ARG AI_FABRIC_VERSION
ARG APP_VERSION
ARG SOURCE_COMMIT
ARG SOURCE_BRANCH
ARG BUILD_COMMIT
ARG BUILD_BRANCH
ARG BUILD_TIME

ENV PORT=8105
ENV JAVA_OPTS=""
ENV AI_FABRIC_VERSION="${AI_FABRIC_VERSION}"
ENV APP_VERSION="${APP_VERSION}"

RUN addgroup -S app && adduser -S app -G app

RUN actual_build_time="${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" \
    && actual_commit="${SOURCE_COMMIT:-${BUILD_COMMIT:-unknown}}" \
    && actual_branch="${SOURCE_BRANCH:-${BUILD_BRANCH:-unknown}}" \
    && printf 'version=%s\ncommit=%s\nbranch=%s\nbuiltAt=%s\naiFabricVersion=%s\n' \
      "${APP_VERSION}" "${actual_commit}" "${actual_branch}" "${actual_build_time}" "${AI_FABRIC_VERSION}" \
      > /app/build-info.properties \
    && chown app:app /app/build-info.properties

USER app:app

EXPOSE 8105
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]

# Source-artifact verification target. Supply the tested JAR directory as the
# release-candidate-artifact build context and select this target explicitly.
FROM runtime-base AS release-candidate
COPY --from=release-candidate-artifact --chown=app:app \
    /retrieval-connector-boundary-lab-*.jar \
    /app/app.jar

# The default deployment remains a clean Maven Central consumer.
FROM runtime-base AS runtime
COPY --from=build --chown=app:app \
    /workspace/retrieval-connector-boundary-lab/target/retrieval-connector-boundary-lab-*.jar \
    /app/app.jar
