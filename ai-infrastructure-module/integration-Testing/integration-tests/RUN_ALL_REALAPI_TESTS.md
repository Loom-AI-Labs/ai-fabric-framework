# Run RealAPI Tests

All RealAPI tests in this module use `@ActiveProfiles("real-api-test")` and require a real provider API key.

## Quick scripts

```bash
cd ai-infrastructure-module/integration-Testing/integration-tests

# OpenAI LLM + ONNX embeddings + Lucene (loads OPENAI_API_KEY from env, or `../dev2.env`, or `../scripts/openai.env`)
./run-all-realapi-tests-openai-onnx-lucene.sh

# Provider matrix harness (single combination)
./run-provider-matrix-tests.sh "openai:onnx:lucene"
```

## Run All RealAPI Tests (recommended from reactor root)

```bash
cd ai-infrastructure-module

# Install dependencies first with normal unit tests.
mvn clean install

# Clean up any existing Lucene locks
rm -rf integration-Testing/integration-tests/data/lucene-vector-index \
       integration-Testing/integration-tests/data/test-lucene-index \
       integration-Testing/integration-tests/data/test-lucene-index-hybrid

# Set OpenAI API key (example: dev2.env contains the raw key string)
export OPENAI_API_KEY="$(tr -d '\n' < ../dev2.env)"

# Run all RealAPI tests (OpenAI LLM + OpenAI embeddings with 512 dimensions) and Lucene
mvn -pl integration-Testing/integration-tests test \
  "-Dtest=RealAPIIntegrationTest,RealAPIProgressiveExtractionDiagnosticsIntegrationTest,RealAPIONNXFallbackIntegrationTest,RealAPISmartValidationIntegrationTest,RealAPIVectorLifecycleIntegrationTest,RealAPIHybridRetrievalToggleIntegrationTest,RealAPIIntentHistoryAggregationIntegrationTest,RealAPIActionErrorRecoveryIntegrationTest,RealAPIActionFlowIntegrationTest,RealAPIIntentGenerationRoutingIntegrationTest,RealAPIMultiProviderFailoverIntegrationTest,RealAPISmartSuggestionsIntegrationTest,RealAPIPIIEdgeSpectrumIntegrationTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dai.providers.llm-provider=openai" \
  "-Dai.providers.embedding-provider=openai" \
  "-Dai.providers.openai.embedding-model=text-embedding-3-small" \
  "-Dai.providers.openai.embedding-dimensions=512" \
  "-Dai.vector-db.type=lucene" \
  "-DforkCount=1" \
  "-DreuseForks=false" \
  "-Dlogging.level.root=WARN" \
  "-Dlogging.level.ai.fabric.provider.openai=INFO"
```

## Run A Single RealAPI Test (OpenAI LLM + ONNX embeddings + Lucene)

```bash
cd ai-infrastructure-module

mvn clean install

rm -rf integration-Testing/integration-tests/data/lucene-vector-index \
       integration-Testing/integration-tests/data/test-lucene-index \
       integration-Testing/integration-tests/data/test-lucene-index-hybrid

export OPENAI_API_KEY="$(tr -d '\n' < ../dev2.env)"

mvn -pl integration-Testing/integration-tests test \
  "-Dtest=RealAPIProgressiveExtractionDiagnosticsIntegrationTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dai.providers.llm-provider=openai" \
  "-Dai.providers.embedding-provider=onnx" \
  "-Dai.vector-db.type=lucene"
```

## All RealAPI Test Classes
1. RealAPIIntegrationTest
2. RealAPIProgressiveExtractionDiagnosticsIntegrationTest
3. RealAPIIntegrationTestV2
4. RealAPIONNXFallbackIntegrationTest
5. RealAPISmartValidationIntegrationTest
6. RealAPIVectorLifecycleIntegrationTest
7. RealAPIHybridRetrievalToggleIntegrationTest
8. RealAPIIntentHistoryAggregationIntegrationTest
9. RealAPIActionErrorRecoveryIntegrationTest
10. RealAPIActionFlowIntegrationTest
11. RealAPIIntentGenerationRoutingIntegrationTest
12. RealAPIMultiProviderFailoverIntegrationTest
13. RealAPISmartSuggestionsIntegrationTest
14. RealAPIPIIEdgeSpectrumIntegrationTest
15. RealAPIProviderMatrixIntegrationTest
