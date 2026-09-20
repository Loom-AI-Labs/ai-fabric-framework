# syntax=docker/dockerfile:1.7
#
# Build from the examples/real-apps directory so smoke-support is available in
# the same Maven reactor while AI Fabric resolves from the released version.
#
# From the repository root:
# docker build -f examples/real-apps/ecommerce-store/Dockerfile \
#   --build-arg AI_FABRIC_VERSION=0.8.0 \
#   -t ai-fabric-ecommerce-store:0.8.0 \
#   examples/real-apps

ARG AI_FABRIC_VERSION=0.8.0

FROM maven:3.9-eclipse-temurin-21 AS build
ARG AI_FABRIC_VERSION
WORKDIR /workspace

COPY . .

RUN mvn -B -V --no-transfer-progress \
    -Dai-fabric.version="${AI_FABRIC_VERSION}" \
    -pl ecommerce-store -am package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV PORT=8096
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE="smoke"

RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data \
    && chown -R app:app /app

USER app:app

COPY --from=build --chown=app:app /workspace/ecommerce-store/target/ecommerce-store-*.jar /app/app.jar

EXPOSE 8096

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
