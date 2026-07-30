# syntax=docker/dockerfile:1.7
#
# Build from the repository root. This reference app exercises contracts that
# are introduced by the same source revision, so the image first verifies and
# installs the required framework modules before packaging the app.
#
# From repo root:
# docker build -f examples/real-apps/agentic-ai-action-resolver/Dockerfile \
#   --build-arg AI_FABRIC_VERSION=0.5.1 \
#   -t agentic-ai-action-resolver:source .

ARG AI_FABRIC_VERSION=0.5.1
ARG APP_VERSION=1.0.0-SNAPSHOT
ARG SOURCE_COMMIT=
ARG SOURCE_BRANCH=
ARG BUILD_COMMIT=unknown
ARG BUILD_BRANCH=unknown
ARG BUILD_TIME=

FROM maven:3.9-eclipse-temurin-21 AS build
ARG AI_FABRIC_VERSION
WORKDIR /workspace

COPY ai-infrastructure-module ./ai-infrastructure-module

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -V --no-transfer-progress \
    -f ai-infrastructure-module/pom.xml \
    -pl ai-fabric-starter,ai-fabric-behavior,victor-databases/ai-fabric-vector-lucene,providers/ai-fabric-provider-spring-ai,ai-fabric-rag,ai-fabric-chat-session,ai-fabric-execution,ai-fabric-relationship-query \
    -am install

COPY examples/real-apps ./examples/real-apps

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -V --no-transfer-progress \
    -f examples/real-apps/pom.xml \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -pl agentic-ai-action-resolver -am package

FROM eclipse-temurin:21-jre-alpine
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

RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data \
    && chown -R app:app /app

RUN actual_build_time="${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" \
    && actual_commit="${SOURCE_COMMIT:-${BUILD_COMMIT:-unknown}}" \
    && actual_branch="${SOURCE_BRANCH:-${BUILD_BRANCH:-unknown}}" \
    && printf 'version=%s\ncommit=%s\nbranch=%s\nbuiltAt=%s\naiFabricVersion=%s\n' \
      "${APP_VERSION}" "${actual_commit}" "${actual_branch}" "${actual_build_time}" "${AI_FABRIC_VERSION}" \
      > /app/build-info.properties \
    && chown app:app /app/build-info.properties

USER app:app

COPY --from=build --chown=app:app /workspace/examples/real-apps/agentic-ai-action-resolver/target/agentic-ai-action-resolver-*.jar /app/app.jar

EXPOSE 8105

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
