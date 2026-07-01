# Provider Failover Lab

## Scenario

This app demonstrates provider routing, fallback evidence, safe diagnostics, and transient input
policy. It is designed for operators and release checks that need to understand what happened when a
provider failed or a fallback provider was selected.

## AI Fabric Capabilities Proved

- Missing or failing primary provider falls back to a configured secondary provider.
- Provider attempts are returned as safe diagnostics.
- Error diagnostics expose provider/error categories without leaking prompts or secrets.
- Transient file URL presence is tracked for the call but not persisted, logged, or indexed.
- Token/model evidence is returned when available from the selected provider.
- Smoke mode can prove fallback behavior without external keys.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-provider-spring-ai`
- `AIProvider`
- provider routing and fallback conventions
- transient input policy evidence
- safe observation/diagnostic shape

## Runtime Posture

Default runtime can use deterministic local providers. Real provider testing is opt-in through
provider configuration and credentials.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl provider-failover-lab -am package
java -jar examples/real-apps/provider-failover-lab/target/provider-failover-lab-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl provider-failover-lab -am test
```

Use `requests/demo.http` for local provider routing/fallback scenarios.

## Demo Flow

1. Configure a failing primary provider and a working fallback provider.
2. Submit a generation probe.
3. Inspect provider attempts and selected provider.
4. Submit a request containing a transient file URL.
5. Verify diagnostics show the transient input was seen but not persisted.
6. Confirm prompts/secrets are not exposed in error diagnostics.

## What This App Does Not Cover

- Full provider matrix scoring. Use integration-test provider matrix scripts.
- Vector provider lifecycle. Use `vector-readiness-playground`.
- RAG quality. Use `smart-faq-assistant`.
