# syntax=docker/dockerfile:1.7

ARG AI_FABRIC_VERSION=0.8.36
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
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -V --no-transfer-progress \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -DskipTests \
    -pl conversation-handover-demo -am package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG AI_FABRIC_VERSION
ARG APP_VERSION
ARG SOURCE_COMMIT
ARG SOURCE_BRANCH
ARG BUILD_COMMIT
ARG BUILD_BRANCH
ARG BUILD_TIME

ENV PORT=8110
ENV JAVA_OPTS=""
ENV AI_FABRIC_VERSION="${AI_FABRIC_VERSION}"
ENV APP_VERSION="${APP_VERSION}"
ENV DATABASE_URL="jdbc:h2:file:/app/data/conversation-handover;DB_CLOSE_ON_EXIT=FALSE"
ENV CORS_ALLOWED_ORIGINS="https://ai-fabric.dev"
ENV APP_DEMO_PUBLIC_OPERATOR_ENABLED="true"
ENV CORS_ALLOW_CREDENTIALS="true"
ENV SERVER_SERVLET_SESSION_COOKIE_SAME_SITE="none"
ENV SERVER_SERVLET_SESSION_COOKIE_SECURE="true"
ENV SERVER_SERVLET_SESSION_COOKIE_PARTITIONED="true"

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
COPY --from=build --chown=app:app /workspace/conversation-handover-demo/target/conversation-handover-demo-*.jar /app/app.jar

EXPOSE 8110
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health/liveness" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
