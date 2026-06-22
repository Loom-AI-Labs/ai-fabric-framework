#!/bin/bash

###############################################################################
# Chat Session Real API Integration Test Runner
#
# Runs chat-session real API integration tests (Failsafe) and lets you pass
# provider combinations similar to relationship-query suite runners.
#
# Usage:
#   ./run-chat-session-realapi-tests.sh [LLM:EMBEDDING[:VECTOR_DB]]
#   ./run-chat-session-realapi-tests.sh "openai:onnx:lucene"
#
# Prerequisites:
#   - Java 21+
#   - Maven 3.8+
#   - Provider credentials via env vars (e.g., OPENAI_API_KEY)
#
# Optional env:
#   AI_PROVIDERS_REAL_API_SKIP_DEPENDENCY_BUILD - Set true to skip installing local framework modules first
#   AI_CHAT_REAL_API_DEPENDENCY_MODULES - Comma-separated Maven module list to install before the smoke run
###############################################################################

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/.." && pwd)"
FAILSAFE_EVALUATOR="${REPO_ROOT}/scripts/evaluate-failsafe-success-rate.sh"

MAVEN_PROFILE="realapi"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-realapi}"
MATRIX_SPEC="${1:-openai:onnx:lucene}"

print_header() {
  echo -e "${BLUE}"
  echo "═══════════════════════════════════════════════════════════════"
  echo "$1"
  echo "═══════════════════════════════════════════════════════════════"
  echo -e "${NC}"
}

print_info() { echo -e "${BLUE}ℹ${NC} $1"; }
print_success() { echo -e "${GREEN}✓${NC} $1"; }
print_error() { echo -e "${RED}✗${NC} $1"; }
print_warning() { echo -e "${YELLOW}⚠${NC} $1"; }

print_header "Pre-flight Checks"

if ! command -v java &> /dev/null; then
  print_error "Java is not installed"
  exit 1
fi
print_success "Java found: $(java -version 2>&1 | head -n 1)"

if ! command -v mvn &> /dev/null; then
  print_error "Maven is not installed"
  exit 1
fi
print_success "Maven found: $(mvn -v 2>&1 | head -n 1)"

LLM_PROVIDER="$(echo "$MATRIX_SPEC" | cut -d':' -f1)"
EMBEDDING_PROVIDER="$(echo "$MATRIX_SPEC" | cut -d':' -f2)"
VECTOR_DB="$(echo "$MATRIX_SPEC" | cut -d':' -f3)"
if [ -z "$VECTOR_DB" ]; then
  VECTOR_DB="lucene"
fi

export AI_INFRASTRUCTURE_LLM_PROVIDER="${AI_INFRASTRUCTURE_LLM_PROVIDER:-$LLM_PROVIDER}"
export AI_INFRASTRUCTURE_EMBEDDING_PROVIDER="${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:-$EMBEDDING_PROVIDER}"
export AI_INFRASTRUCTURE_VECTOR_DATABASE="${AI_INFRASTRUCTURE_VECTOR_DATABASE:-$VECTOR_DB}"

print_info "Provider matrix: LLM=$AI_INFRASTRUCTURE_LLM_PROVIDER, Embedding=$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER, VectorDB=$AI_INFRASTRUCTURE_VECTOR_DATABASE"

check_provider_api_keys() {
  local llm_provider="$1"
  local embedding_provider="$2"
  local missing=()

  case "$llm_provider" in
    openai) [ -z "${OPENAI_API_KEY:-}" ] && missing+=("OPENAI_API_KEY") ;;
    anthropic) [ -z "${ANTHROPIC_API_KEY:-}" ] && missing+=("ANTHROPIC_API_KEY") ;;
    gemini) [ -z "${GEMINI_API_KEY:-}" ] && missing+=("GEMINI_API_KEY") ;;
    cohere) [ -z "${COHERE_API_KEY:-}" ] && missing+=("COHERE_API_KEY") ;;
    azure)
      [ -z "${AZURE_API_KEY:-}" ] && missing+=("AZURE_API_KEY")
      [ -z "${AZURE_ENDPOINT:-}" ] && missing+=("AZURE_ENDPOINT")
      ;;
  esac

  case "$embedding_provider" in
    openai) [ -z "${OPENAI_API_KEY:-}" ] && missing+=("OPENAI_API_KEY") ;;
    anthropic) [ -z "${ANTHROPIC_API_KEY:-}" ] && missing+=("ANTHROPIC_API_KEY") ;;
    gemini) [ -z "${GEMINI_API_KEY:-}" ] && missing+=("GEMINI_API_KEY") ;;
    cohere) [ -z "${COHERE_API_KEY:-}" ] && missing+=("COHERE_API_KEY") ;;
    azure)
      [ -z "${AZURE_API_KEY:-}" ] && missing+=("AZURE_API_KEY")
      [ -z "${AZURE_ENDPOINT:-}" ] && missing+=("AZURE_ENDPOINT")
      ;;
  esac

  if [ ${#missing[@]} -gt 0 ]; then
    print_error "Missing required credentials: ${missing[*]}"
    exit 1
  fi
}

check_provider_api_keys "$AI_INFRASTRUCTURE_LLM_PROVIDER" "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"

if [ "${AI_PROVIDERS_REAL_API_SKIP_DEPENDENCY_BUILD:-false}" == "true" ]; then
  print_info "Skipping dependency build because AI_PROVIDERS_REAL_API_SKIP_DEPENDENCY_BUILD=true"
else
  DEPENDENCY_MODULES="${AI_CHAT_REAL_API_DEPENDENCY_MODULES:-ai-fabric-chat-session,ai-fabric-pii,ai-fabric-governance,providers/ai-fabric-provider-spring-ai,providers/ai-fabric-onnx-starter,victor-databases/ai-fabric-vector-lucene,integration-Testing/testcontainers-support}"
  print_info "Installing local framework dependencies for chat-session RealAPI smoke"
  if ! (cd "$PROJECT_ROOT" && mvn -B -q --no-transfer-progress -pl "$DEPENDENCY_MODULES" -am -DskipITs clean install); then
    print_error "Failed to install local dependencies. Re-run from ai-infrastructure-module with: mvn -B --no-transfer-progress -pl \"$DEPENDENCY_MODULES\" -am -DskipITs clean install"
    exit 1
  fi
fi

cd "$SCRIPT_DIR"

REPORTS_DIR="${SCRIPT_DIR}/target/failsafe-reports"
SCORECARD_DIR="${SCRIPT_DIR}/target/provider-matrix-reports"
SCORECARD_FILE="chat-session-realapi-${AI_INFRASTRUCTURE_LLM_PROVIDER}-${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER}-${AI_INFRASTRUCTURE_VECTOR_DATABASE:-none}.json"
SCORECARD_PATH="${SCORECARD_DIR}/${SCORECARD_FILE}"
rm -rf "$REPORTS_DIR" "$SCORECARD_DIR"

print_info "Refreshing compiled test module classes"
mvn -q --no-transfer-progress -DskipTests -DskipITs clean test-compile

print_header "Connectivity Verification"
CONNECTIVITY_COMMAND="mvn -P${MAVEN_PROFILE} -Dspring.profiles.active=${SPRING_PROFILE} -Dai.realapi.connectivity.check=true -Dtest=RealApiConnectivityVerificationTest"
if [ -n "$AI_INFRASTRUCTURE_VECTOR_DATABASE" ]; then
  CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.vector-db.type=${AI_INFRASTRUCTURE_VECTOR_DATABASE}"
fi
if [ -n "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" ]; then
  CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.embedding-provider=$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"
fi
if [ -n "$AI_INFRASTRUCTURE_LLM_PROVIDER" ]; then
  CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.llm-provider=$AI_INFRASTRUCTURE_LLM_PROVIDER"
fi
if [ "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" = "openai" ] && [ "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" = "lucene" ]; then
  CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.openai.embedding-dimensions=512"
fi

print_info "Connectivity command:"
echo "  $CONNECTIVITY_COMMAND"
echo ""

if eval "$CONNECTIVITY_COMMAND test"; then
  print_success "Connectivity verification passed"
else
  print_error "Connectivity verification failed - aborting test run"
  exit 1
fi

print_header "Executing RealAPI Tests"
MAVEN_COMMAND="mvn -P${MAVEN_PROFILE} -Dspring.profiles.active=${SPRING_PROFILE} -DforkCount=1 -DreuseForks=false"
if [ -n "$AI_INFRASTRUCTURE_VECTOR_DATABASE" ]; then
  MAVEN_COMMAND="$MAVEN_COMMAND -Dai.vector-db.type=${AI_INFRASTRUCTURE_VECTOR_DATABASE}"
fi
if [ -n "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" ]; then
  MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.embedding-provider=$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"
fi
if [ -n "$AI_INFRASTRUCTURE_LLM_PROVIDER" ]; then
  MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.llm-provider=$AI_INFRASTRUCTURE_LLM_PROVIDER"
fi

case "$AI_INFRASTRUCTURE_LLM_PROVIDER" in
  openai) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.enabled=true -DOPENAI_ENABLED=true" ;;
  anthropic) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.anthropic.enabled=true -DANTHROPIC_ENABLED=true" ;;
  gemini) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.gemini.enabled=true -DGEMINI_ENABLED=true" ;;
  cohere) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.enabled=true -DCOHERE_ENABLED=true" ;;
  azure) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.enabled=true -DAZURE_ENABLED=true" ;;
esac

case "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" in
  openai) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.enabled=true -DOPENAI_ENABLED=true" ;;
  cohere) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.enabled=true -DCOHERE_ENABLED=true" ;;
  azure) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.enabled=true -DAZURE_ENABLED=true" ;;
esac

case "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" in
  pinecone) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.pinecone.enabled=true" ;;
  weaviate) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.weaviate.enabled=true" ;;
  qdrant) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.qdrant.enabled=true" ;;
  milvus) MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.milvus.enabled=true" ;;
esac

if [ "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" = "openai" ] && [ "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" = "lucene" ]; then
  MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.embedding-dimensions=512"
  print_info "Auto-configured OpenAI embedding dimensions to 512 for Lucene compatibility"
fi

MAVEN_COMMAND="$MAVEN_COMMAND failsafe:integration-test failsafe:verify"

print_info "Command:"
echo "  $MAVEN_COMMAND"
echo ""

start_time=$(date +%s)

if [ ! -f "$FAILSAFE_EVALUATOR" ]; then
  print_error "Missing failsafe evaluator script: $FAILSAFE_EVALUATOR"
  exit 1
fi

print_info "RealAPI thresholds: minSuccessRate=${AI_PROVIDERS_REAL_API_MINIMUM_SUCCESS_RATE:-0.85}, minConsideredTests=${AI_PROVIDERS_REAL_API_MINIMUM_CONSIDERED_TESTS:-20}"

set +e
eval "$MAVEN_COMMAND -Dmaven.test.failure.ignore=true"
mvn_exit=$?
set -e

end_time=$(date +%s)
duration=$((end_time - start_time))

if [ $mvn_exit -ne 0 ]; then
  print_error "Chat-session RealAPI tests failed to execute (${duration}s)"
  exit $mvn_exit
fi

set +e
bash "$FAILSAFE_EVALUATOR" \
  --reports-dir "$REPORTS_DIR" \
  --scorecard-path "$SCORECARD_PATH" \
  --suite "chat-session-integration-tests" \
  --llm "${AI_INFRASTRUCTURE_LLM_PROVIDER:-}" \
  --embedding "${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:-}" \
  --vector-db "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" \
  --min-success-rate "${AI_PROVIDERS_REAL_API_MINIMUM_SUCCESS_RATE:-0.85}" \
  --min-considered-tests "${AI_PROVIDERS_REAL_API_MINIMUM_CONSIDERED_TESTS:-20}"
gate_exit=$?
set -e

print_info "Duration: ${duration}s"
print_info "Scorecard: ${SCORECARD_PATH}"

if [ $gate_exit -eq 0 ]; then
  print_success "Chat-session RealAPI suite meets thresholds"
  exit 0
fi

print_error "Chat-session RealAPI suite below thresholds (exit=${gate_exit})"
exit 1
