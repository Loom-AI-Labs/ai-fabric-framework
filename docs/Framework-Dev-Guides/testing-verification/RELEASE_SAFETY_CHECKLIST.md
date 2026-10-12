# AI Fabric Release Safety Checklist

Use this checklist before declaring an AI Fabric framework release ready. It is
written for maintainers and coding-assistant sessions that need a repeatable,
evidence-backed release gate.

The release is not ready while a mandatory gate is failing, Maven Central
artifacts are unavailable, a required provider path is unverified, or deployed
applications are running an older framework version.

## Release Principles

- Do not hide failures with mocks, silent fallbacks, skipped tests, or stale
  deployments.
- Keep deterministic framework verification separate from model-variable real
  provider verification.
- Test packaged applications and published artifacts, not only source-classpath
  execution.
- Treat identity, tenant boundaries, authorization, confirmation, and failure
  visibility as release contracts.
- Maven Central versions and release tags are immutable. Fix a published
  release with a new patch version.

## 1. Review Scope And Impact

- Inspect `git status`, the complete diff, and the commits included in the
  release.
- Separate framework changes, examples, documentation, generated files, and
  unrelated working-tree changes.
- Identify affected modules, public APIs, configuration properties, database
  state, vector indexes, prompts, providers, real apps, and deployed demos.
- Record whether consumers need configuration changes, a database migration,
  vector reindexing, or application code changes.
- Check the implementation against the framework philosophy: application-owned
  trust, structured contracts, governed writes, visible failures, and no
  deterministic text matching presented as intelligence.

## 2. Run Repository Release Guards

```bash
.github/scripts/validate-framework-release-guards.sh
```

The aggregate guard validates:

- semantic decision-source policy;
- provider registry consistency;
- workflow test policy;
- release-document policy;
- absence of production stubs; and
- vector-readiness verifier behavior.

Any failure is a release blocker. Correct the underlying code, configuration,
workflow, or documentation instead of weakening the guard.

## 3. Build And Test The Framework Reactor

Use Java 21 and the supported Spring Boot 4.1.x baseline. Run tests; do not add
`skipTests` or `maven.test.skip` to obtain a green release.

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -Dai.vector-db.lucene.cleanup-on-close=true \
  -Prelease \
  -pl '!integration-Testing/vector-contract-tests,!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install
```

Compile the separately gated integration suites:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl integration-Testing/testcontainers-support,integration-Testing/integration-tests,integration-Testing/relationship-query-integration-tests,integration-Testing/chat-session-integration-tests,integration-Testing/behavior-integration-tests \
  -am \
  test-compile
```

The release profile also proves that source and Javadoc artifacts can be
created for Maven Central.

## 4. Verify Infrastructure And Provider Contracts

Run the container-backed vector contract gate with Docker available:

```bash
.github/scripts/run-vector-container-contracts.sh
```

For affected modules, verify applicable contracts such as:

- vector store, metadata filtering, scope isolation, update, and deletion;
- persistence and restart recovery;
- tenant and user isolation;
- action authorization, confirmation, rejection, expiration, and replay;
- specialist delegation, handoff, bounded chaining, and aggregation;
- document preview, indexing, replacement, retrieval, and exact deletion; and
- MCP server binding, tool policy, safe projection, and audit records.

Retain Surefire and Failsafe reports as release evidence.

## 5. Prove External Consumer Compatibility

Compile and test consumers against installed release-candidate artifacts:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/minimal-spring-boot/pom.xml \
  compile

mvn -B -V --no-transfer-progress \
  -f examples/agentic-execution-consumer/pom.xml \
  clean test

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  install

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/incident-investigation-room/pom.xml \
  clean verify
```

Run the packaged-runtime and real-app gates:

```bash
.github/scripts/smoke-incident-specialist-chain-docker.sh
.github/scripts/smoke-boot-realapps.sh
.github/scripts/smoke-ecommerce-chat-datasync.sh
.github/scripts/smoke-p1-realapp-scenarios.sh
```

These checks catch missing runtime dependencies, source-only assumptions,
configuration drift, serialization failures, and application integration
regressions that unit tests cannot expose.

## 6. Run Real Provider Verification When Relevant

Real provider tests are mandatory when the release changes prompts, intent or
mode routing, RAG generation, action extraction, specialists, provider
adapters, embeddings, or managed vector behavior.

- Start with the configured OpenAI path when an OpenAI key is available.
- Run the provider matrix for every available provider credential.
- Use multiple natural-language phrasings, follow-up turns, missing-parameter
  answers, confirmation, rejection, and failure scenarios.
- Log the selected intent, action, evidence, parameters, confirmation state,
  provider/model, and final outcome.
- Apply the configured minimum success-rate and considered-test thresholds.
- Do not convert provider errors into successful-looking fallback responses.
- Investigate each failure as test code, environment/provider variability, or
  framework behavior before changing framework code.

Use these workflows and guides:

- `.github/workflows/provider-suite-keys-only.yml`
- `.github/workflows/integration-tests-manual.yml`
- `REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

Tests that do not need a provider key, such as action-handler unit tests,
deterministic vector contracts, configuration loading, access-policy tests,
chat-session persistence, and smoke-profile boot, must remain deterministic.

## 7. Verify Security Boundaries

- Derive identity, tenant, deployment, account, and authorization scope from
  trusted backend context.
- Attempt cross-tenant retrieval and prove no foreign evidence is returned.
- Reject caller- or model-supplied trusted fields and unknown action fields.
- Verify PII, credentials, and unprojected provider payloads are absent from
  logs and responses.
- Prove writes require the declared authorization and confirmation policy.
- Prove confirmation replay cannot execute a side effect twice.
- Prove unavailable providers, tools, evidence, and policy bindings fail
  visibly and closed.

## 8. Prepare Versioned Documentation

- Use one version across the parent POM, BOM, modules, examples, release notes,
  and consumer documentation.
- Write release notes that describe behavior, verification, limitations, and
  migration impact.
- Provide platform or application migration guidance when consumers need more
  than a BOM version change.
- Document dependency, property, environment-variable, schema, storage,
  reindex, prompt, and deployment changes.
- Confirm public READMEs and live-demo descriptions match implemented behavior.
- Run the release guards again after documentation changes.

## 9. Publish An Immutable Release

- Confirm the tag is `ai-fabric-framework-v<version>` and exactly matches the
  Maven project version.
- Publish only from the reviewed immutable tag.
- Do not move, delete, or recreate a tag whose artifacts may have reached
  Maven Central.
- If the version already exists, increment to a new patch version.
- Verify the Maven Central deployment reaches a successful state.
- Wait until the BOM URL returns HTTP `200`.

Follow `docs/MAVEN_CENTRAL_RELEASE_GUIDE.md` for the signed publication flow and
the pinned Maven version used by the release workflow.

## 10. Prove Published Artifact Consumption

After publication, use a clean consumer environment that cannot resolve local
reactor artifacts accidentally.

- Import the published BOM from Maven Central.
- Build a minimal application using only published coordinates.
- Check the dependency tree for mixed AI Fabric versions.
- Start the packaged application and exercise one representative framework
  capability.
- Confirm source and Javadoc artifacts are available to consumer tooling.

A successful deployment workflow alone does not prove that external consumers
can resolve and run the release.

## 11. Redeploy And Verify Live Applications

For every affected deployed demo or platform application:

- trigger a rebuild from the intended source commit;
- verify health, CORS, startup, migrations, seeding, and cleanup behavior;
- confirm the health response reports the expected framework version, source
  commit, and build time;
- run complete live API and UI scenarios against the deployed backend;
- verify real AI responses when the feature depends on an LLM;
- test confirmation, rejection, reset, and session isolation where applicable;
  and
- confirm the deployment is current instead of trusting an auto-deploy event.

## 12. Record The Final Release Verdict

The final report must include:

- release version, commit, and tag;
- exact commands and workflows executed;
- passed, failed, and skipped tests, including skip reasons;
- real providers and models tested;
- Maven Central deployment and BOM URL;
- deployed application versions and URLs;
- migration documents produced; and
- remaining risks or explicitly untested paths.

Use one of these verdicts:

- `READY`: every mandatory applicable gate passed.
- `BLOCKED`: a mandatory gate failed or required evidence is unavailable.
- `CONDITIONALLY READY`: only a clearly named optional provider or deployment
  gate is pending, and the affected feature will remain disabled.

Never use `READY` merely because compilation passed.

## Coding-Assistant Handoff

Give another coding-assistant session this instruction:

```text
Apply docs/Framework-Dev-Guides/testing-verification/RELEASE_SAFETY_CHECKLIST.md
as the release contract. Inspect the complete diff, run every applicable
deterministic and real-provider gate, preserve test evidence, verify published
artifact consumption and deployed versions, and report READY only when every
mandatory gate passes. Do not hide failures with mocks, fallbacks, skipped
tests, or stale deployments. Do not change framework code merely to make a
test pass until the failure is classified as a test defect or framework defect.
```

## Related Guides

- [CI Pipeline Guide](CI_PIPELINE_GUIDE.md)
- [RealAPI Provider Matrix Testing Guide](REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md)
- [GitHub Actions Verification Suite Guide](GITHUB_ACTIONS_VERIFICATION_SUITE_GUIDE.md)
- [Production Checklist](../../getting-started/13-production-checklist.md)
- [Maven Central Release Guide](../../MAVEN_CENTRAL_RELEASE_GUIDE.md)
