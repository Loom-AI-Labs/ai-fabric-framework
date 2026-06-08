# Build context: repo root
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY ai-infrastructure-module ai-infrastructure-module

RUN mvn -f ai-infrastructure-module/pom.xml -pl ai-infrastructure-generic-rest-connector -am -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends gosu && rm -rf /var/lib/apt/lists/*

RUN useradd -r -u 10002 -g root connector

RUN mkdir -p /config && chown -R 10002:0 /config

COPY --from=build /workspace/ai-infrastructure-module/ai-infrastructure-generic-rest-connector/target/ai-infrastructure-generic-rest-connector-*.jar /app/connector.jar
COPY ai-infrastructure-module/ai-infrastructure-generic-rest-connector/docker-entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

EXPOSE 8082

ENV JAVA_OPTS=""

ENTRYPOINT ["/app/entrypoint.sh"]

