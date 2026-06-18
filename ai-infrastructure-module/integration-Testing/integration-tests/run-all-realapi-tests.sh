#!/bin/bash
# Run all RealAPI tests with OpenAI embeddings (512 dimensions) and Lucene

if [[ -z "${OPENAI_API_KEY}" ]]; then
  echo "ERROR: OPENAI_API_KEY must be set to run RealAPI tests." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# All RealAPI test classes
TESTS=(
  "RealAPIIntegrationTest"
  "RealAPIProgressiveExtractionDiagnosticsIntegrationTest"
  "RealAPIONNXFallbackIntegrationTest"
  "RealAPISmartValidationIntegrationTest"
  "RealAPIVectorLifecycleIntegrationTest"
  "RealAPIHybridRetrievalToggleIntegrationTest"
  "RealAPIIntentHistoryAggregationIntegrationTest"
  "RealAPIActionErrorRecoveryIntegrationTest"
  "RealAPIActionFlowIntegrationTest"
  "RealAPIIntentGenerationRoutingIntegrationTest"
  "RealAPIMultiProviderFailoverIntegrationTest"
  "RealAPISmartSuggestionsIntegrationTest"
  "RealAPIPIIEdgeSpectrumIntegrationTest"
)

TEST_CLASSES=$(IFS=,; echo "${TESTS[*]}")

echo "=========================================="
echo "Running All RealAPI Tests"
echo "=========================================="
echo "Test Classes: ${TEST_CLASSES}"
echo "Configuration:"
echo "  - Embedding Provider: openai"
echo "  - Embedding Dimensions: 512"
echo "  - Vector DB: lucene"
echo "=========================================="
echo ""

cd "${REACTOR_ROOT}" || exit 1

mvn -pl integration-Testing/integration-tests -am test \
  "-Dtest=${TEST_CLASSES}" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dai.providers.llm-provider=openai" \
  "-Dai.providers.embedding-provider=openai" \
  "-Dai.providers.openai.embedding-model=text-embedding-3-small" \
  "-Dai.providers.openai.embedding-dimensions=512" \
  "-Dai.vector-db.type=lucene" \
  "-DforkCount=1" \
  "-DreuseForks=false" \
  "-Dlogging.level.root=WARN" \
  "-Dlogging.level.ai.fabric.provider.openai=INFO" \
  2>&1 | tee /tmp/realapi-tests-output.log

echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
grep -E "Tests run|BUILD|FAILURE|SUCCESS|Running.*RealAPI" /tmp/realapi-tests-output.log | tail -n 30
