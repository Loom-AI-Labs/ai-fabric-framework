# syntax=docker/dockerfile:1.7
#
# Build from the examples/real-apps directory. The Docker build copies only the
# real-app examples reactor, then resolves AI Fabric from the released Maven
# version. It does not copy or install ai-infrastructure-module.
#
# From repo root:
# docker build -f examples/real-apps/ai-fabric-account-resolver/Dockerfile \
#   --build-arg AI_FABRIC_VERSION=0.3.1 \
#   -t ai-fabric-account-resolver:0.3.1 \
#   examples/real-apps

ARG AI_FABRIC_VERSION=0.3.1

FROM maven:3.9-eclipse-temurin-21 AS build
ARG AI_FABRIC_VERSION
WORKDIR /workspace

COPY . .

RUN mvn -B -V --no-transfer-progress \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -pl ai-fabric-account-resolver -am package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV PORT=8081
ENV JAVA_OPTS=""

RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data \
    && chown -R app:app /app

USER app:app

COPY --from=build --chown=app:app /workspace/ai-fabric-account-resolver/target/ai-fabric-account-resolver-*.jar /app/app.jar

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
