# RealAPI Provider Matrix Testing Guide (External Users)

This guide explains how to run “RealAPI” integration tests across provider combinations (LLM + embedding + vector DB) using the project scripts.

Use this when you are validating:
- provider-specific behavior (OpenAI vs Cohere vs Gemini vs Anthropic)
- vendor vector DBs (Pinecone, Weaviate, Qdrant, Milvus)
- real provider resilience features (retry, structured plan repair)

---

## 1) What Is “RealAPI”?

RealAPI tests exercise the framework against real external providers (LLMs and/or vector DBs). They are separate from unit tests and local-only integration tests.

---

## 2) Required Environment Variables

Set provider API keys as environment variables (do not commit keys):
- `OPENAI_API_KEY`
- `COHERE_API_KEY`
- `GEMINI_API_KEY`
- `ANTHROPIC_API_KEY`
- `PINECONE_API_KEY` (when using Pinecone)

Also set Pinecone host/index when using Pinecone:
- `PINECONE_API_HOST` (or `AI_PROVIDERS_PINECONE_API_HOST`)
- `PINECONE_INDEX_NAME` (or `AI_PROVIDERS_PINECONE_INDEX_NAME`)

Optional framework preset (curated pack):
- `AI_CURATED_PACK` (example values: `support`, `commerce`, `catalog`)
  - When set, the framework loads `classpath:ai-curated/packs/<pack>.yml` as low-precedence defaults.
  - Some RealAPI test profiles default this to `support` unless overridden.

---

## 3) Running a Single RealAPI Test Class

Use:
- `scripts/run-single-test.sh`

Example (Gemini + ONNX + Weaviate):

```bash
TEST_CLASS=RealAPIProviderMatrixIntegrationTest \
MAVEN_MODULE=integration-Testing/integration-tests \
MAVEN_PROFILE=real-api-test \
TEST_RUNNER=failsafe \
MATRIX_SPEC="gemini:onnx:weaviate" \
./scripts/run-single-test.sh
```

---

## 4) Running the Provider Matrix Suite

Use module scripts under `ai-infrastructure-module/integration-Testing/`:

- `ai-infrastructure-module/integration-Testing/integration-tests/run-provider-matrix-tests.sh`
- `ai-infrastructure-module/integration-Testing/relationship-query-integration-tests/run-relationship-query-realapi-tests.sh`
- `ai-infrastructure-module/integration-Testing/chat-session-integration-tests/run-chat-session-realapi-tests.sh`
- `ai-infrastructure-module/integration-Testing/behavior-integration-tests/run-behavior-realapi-tests.sh`

Example:

```bash
cd ai-infrastructure-module/integration-Testing/integration-tests
./run-provider-matrix-tests.sh "openai:onnx:pinecone"
```

---

## 5) Common Failure Modes

- **Missing keys**: test scripts will skip or fail fast when required environment variables are not set.
- **Provider overload (e.g., 503)**: transient failures should be handled by provider retry policies where supported.
- **Truncated/malformed structured outputs**: relationship-query planner may attempt bounded repair; otherwise returns structured failure responses.

If you’re triaging failures, start with the provider combination name and the module test reports:
- `ai-infrastructure-module/**/target/surefire-reports/`
- `ai-infrastructure-module/**/target/failsafe-reports/`
