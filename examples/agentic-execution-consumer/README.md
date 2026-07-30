# Agentic Execution Consumer

This standalone Spring Boot project proves that a normal application can
consume AI Fabric's optional execution module through public Maven artifacts.
It has no framework reactor parent and no relative module dependency.

## What It Proves

- AI Fabric dependency management through `ai-fabric-bom`.
- Explicit consumption of `ai-fabric-execution`.
- Public typed sequential and bounded parallel plan declarations.
- Application-owned branch mappers and deterministic aggregation.
- Spring Boot context startup with execution auto-configuration disabled.
- Real `DefaultAIExecutionCoordinator` fan-out/fan-in from packaged classes.
- Two read branches overlap and return one atomic typed result.
- Declaration-order traces expose the group and common source revision.

The deterministic test clients are test-only. They are not a provider fallback
and are not packaged into the application.

## Local Source Verification

When changing framework source, install the framework with tests and then run
this standalone project:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am install

mvn -B -V --no-transfer-progress \
  -f examples/agentic-execution-consumer/pom.xml \
  clean test
```

The second Maven invocation resolves installed JARs through the BOM. It does
not compile against framework source directories.

## Published Maven Central Verification

AI Fabric `0.5.1` is published. To prove source-independent consumption, use a
fresh local Maven repository and do not install the framework reactor first:

```bash
MAVEN_REPO="$(mktemp -d)"

mvn -B -V --no-transfer-progress \
  -Dmaven.repo.local="$MAVEN_REPO" \
  -Dai-fabric.version=0.5.1 \
  -f examples/agentic-execution-consumer/pom.xml \
  clean test
```

That command proves Maven Central metadata and transitive artifact
completeness. The `0.5.1` release gate ran the same proof from a copy of this
project outside the framework checkout and passed both tests.
