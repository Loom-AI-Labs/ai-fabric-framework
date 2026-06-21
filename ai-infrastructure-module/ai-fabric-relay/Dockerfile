# Build context: repo root
# Example:
#   docker build -f ai-infrastructure-module/ai-fabric-relay/Dockerfile -t ai-fabric-relay:dev .

FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /workspace

# Cache dependencies first
COPY ai-infrastructure-module/pom.xml ai-infrastructure-module/pom.xml
COPY ai-infrastructure-module/ai-fabric-relay/pom.xml ai-infrastructure-module/ai-fabric-relay/pom.xml

RUN mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-relay -am dependency:go-offline

# Copy the sources needed to build the relay (and its parents/modules)
COPY ai-infrastructure-module ai-infrastructure-module

RUN mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-relay -am package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd -r -u 10001 -g root relay

COPY --from=build /workspace/ai-infrastructure-module/ai-fabric-relay/target/ai-fabric-relay-*.jar /app/relay.jar

EXPOSE 8081

USER 10001

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/relay.jar"]
