# syntax=docker/dockerfile:1.7
#
# Build from examples/real-apps. The Docker build copies only the real-app
# examples reactor, then resolves AI Fabric from the released Maven version.
# It does not copy or install ai-infrastructure-module.
#
# From repo root:
# docker build -f examples/real-apps/tenant-knowledge-portal/Dockerfile \
#   --build-arg AI_FABRIC_VERSION=0.3.1 \
#   -t ai-fabric-tenant-guard:0.3.1 \
#   examples/real-apps

ARG AI_FABRIC_VERSION=0.3.1
ARG APP_VERSION=1.0.0-SNAPSHOT
ARG BUILD_COMMIT=unknown
ARG BUILD_BRANCH=unknown
ARG BUILD_TIME=

FROM maven:3.9-eclipse-temurin-21 AS build
ARG AI_FABRIC_VERSION
WORKDIR /workspace

COPY . .

RUN mvn -B -V --no-transfer-progress \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -pl tenant-knowledge-portal -am package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG AI_FABRIC_VERSION
ARG APP_VERSION
ARG BUILD_COMMIT
ARG BUILD_BRANCH
ARG BUILD_TIME

ENV PORT=8101
ENV JAVA_OPTS=""
ENV AI_FABRIC_VERSION="${AI_FABRIC_VERSION}"
ENV APP_VERSION="${APP_VERSION}"
ENV APP_BUILD_COMMIT="${BUILD_COMMIT}"
ENV APP_BUILD_BRANCH="${BUILD_BRANCH}"
ENV APP_BUILD_TIME="${BUILD_TIME}"

RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data \
    && chown -R app:app /app

RUN actual_build_time="${BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" \
    && printf 'version=%s\ncommit=%s\nbranch=%s\nbuiltAt=%s\naiFabricVersion=%s\n' \
      "${APP_VERSION}" "${BUILD_COMMIT}" "${BUILD_BRANCH}" "${actual_build_time}" "${AI_FABRIC_VERSION}" \
      > /app/build-info.properties \
    && chown app:app /app/build-info.properties

USER app:app

COPY --from=build --chown=app:app /workspace/tenant-knowledge-portal/target/tenant-knowledge-portal-*.jar /app/app.jar

EXPOSE 8101

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
