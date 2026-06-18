#!/bin/bash
# Run all RealAPI tests with OpenAI LLM + ONNX embeddings + Lucene vector DB.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REACTOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

maybe_load_openai_api_key() {
  if [ -n "${OPENAI_API_KEY:-}" ]; then
    return
  fi

  local candidates=()
  if [ -n "${OPENAI_API_KEY_FILE:-}" ]; then
    candidates+=("${OPENAI_API_KEY_FILE}")
  fi

  # Common local patterns (all should be ignored by .gitignore).
  candidates+=(
    "${REACTOR_ROOT}/../.env.local"
    "${REACTOR_ROOT}/../dev.env"
    "${REACTOR_ROOT}/../dev2.env"
    "${REACTOR_ROOT}/../scripts/openai.env"
  )

  for candidate in "${candidates[@]}"; do
    if [ -z "$candidate" ] || [ ! -f "$candidate" ]; then
      continue
    fi

    # Accept either:
    # - raw key (single line)
    # - OPENAI_API_KEY=... (dotenv style)
    local first_line
    first_line="$(head -n 1 "$candidate" | tr -d '\r\n' | xargs)"
    if [ -z "$first_line" ]; then
      continue
    fi
    if [[ "$first_line" == OPENAI_API_KEY=* ]]; then
      first_line="${first_line#OPENAI_API_KEY=}"
      first_line="$(echo "$first_line" | xargs)"
    fi

    if [ -n "$first_line" ]; then
      export OPENAI_API_KEY="$first_line"
      return
    fi
  done
}

maybe_load_openai_api_key

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "ERROR: OPENAI_API_KEY must be set to run RealAPI tests (or set OPENAI_API_KEY_FILE, or create ../dev2.env)." >&2
  exit 1
fi

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
echo "  - LLM Provider: openai"
echo "  - Embedding Provider: onnx"
echo "  - Vector DB: lucene"
echo "=========================================="
echo ""

echo "Cleaning Lucene indexes..."
rm -rf "${SCRIPT_DIR}/data/lucene-vector-index" \
       "${SCRIPT_DIR}/data/test-lucene-index" \
       "${SCRIPT_DIR}/data/test-lucene-index-hybrid"

cd "${REACTOR_ROOT}" || exit 1

mvn -pl integration-Testing/integration-tests -am test \
  "-Dtest=${TEST_CLASSES}" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dai.providers.llm-provider=openai" \
  "-Dai.providers.embedding-provider=onnx" \
  "-Dai.vector-db.type=lucene" \
  "-DforkCount=1" \
  "-DreuseForks=false" \
  "-Dlogging.level.root=WARN" \
  "-Dlogging.level.ai.fabric.provider.openai=INFO" \
  2>&1 | tee /tmp/realapi-tests-openai-onnx-lucene-output.log

echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
grep -E "Tests run|BUILD|FAILURE|SUCCESS|Running.*RealAPI" /tmp/realapi-tests-openai-onnx-lucene-output.log | tail -n 30
