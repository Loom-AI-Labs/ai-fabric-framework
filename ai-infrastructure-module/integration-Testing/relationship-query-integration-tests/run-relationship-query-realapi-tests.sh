#!/bin/bash

###############################################################################
###############################################################################
# Relationship Query Real API Integration Test Runner (Provider Matrix style)
#
# This script runs relationship-query real API integration tests (Failsafe) and
# lets you pass provider combinations similar to run-provider-matrix-tests.sh.
#
# Usage:
#   ./run-relationship-query-realapi-tests.sh [LLM:EMBEDDING[:VECTOR_DB]]
#   ./run-relationship-query-realapi-tests.sh "openai:onnx"
#   ./run-relationship-query-realapi-tests.sh "openai:openai:pinecone"
#   ./run-relationship-query-realapi-tests.sh "anthropic:openai" "qdrant"
#
# Prerequisites:
#   - Java 21+
#   - Maven 3.8+
#   - API key environment variable set based on selected providers:
#     * OPENAI_API_KEY (for OpenAI)
#     * ANTHROPIC_API_KEY (for Anthropic)
#     * GEMINI_API_KEY (for Gemini)
#     * COHERE_API_KEY (for Cohere)
#     * AZURE_API_KEY + AZURE_ENDPOINT (for Azure)
#   - ONNX provider doesn't require API keys
#   - Local: Dependencies built (script auto-builds if missing)
#   - CI/CD: Dependencies must be pre-built by workflow (script skips build check)
#
# CI/CD:
#   The GitHub workflow builds dependencies once in "Build AI Fabric Framework" step.
#   Script detects CI environment (CI=true or GITHUB_ACTIONS=true) and skips dependency
#   build check to avoid duplicate builds. The workflow exports OPENAI_API_KEY,
#   AI_INFRASTRUCTURE_LLM_PROVIDER, AI_INFRASTRUCTURE_EMBEDDING_PROVIDER,
#   AI_INFRASTRUCTURE_VECTOR_DATABASE, and AI_INFRASTRUCTURE_PERSISTENCE_DATABASE.
###############################################################################

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/.." && pwd)"
FAILSAFE_EVALUATOR="${REPO_ROOT}/scripts/evaluate-failsafe-success-rate.sh"

# Configuration
# - Maven profile controls which Maven executions run (RealAPI tests live under the `realapi` Maven profile).
# - Spring profiles control Spring Boot configuration overlays (e.g., `testcontainers`).
MAVEN_PROFILE="realapi"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-realapi}"
TEST_MODULE="relationship-query-integration-tests"
MATRIX_SPEC="${1:-openai:onnx}"
VECTOR_DB="${2:-}"

# Functions
print_header() {
    echo -e "${BLUE}"
    echo "═══════════════════════════════════════════════════════════════"
    echo "$1"
    echo "═══════════════════════════════════════════════════════════════"
    echo -e "${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

# Pre-flight checks
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

# Dynamic API key check based on selected providers
check_provider_api_keys() {
    local llm_provider="$1"
    local embedding_provider="$2"
    local missing_keys=()
    local providers_checked=()

    # Check LLM provider API key
	    case "$llm_provider" in
	        openai)
	            if [ -z "${OPENAI_API_KEY:-}" ]; then
	                missing_keys+=("OPENAI_API_KEY (for OpenAI LLM)")
	            else
	                providers_checked+=("OpenAI LLM")
	            fi
	            ;;
	        anthropic)
	            if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
	                missing_keys+=("ANTHROPIC_API_KEY (for Anthropic LLM)")
	            else
	                providers_checked+=("Anthropic LLM")
	            fi
	            ;;
	        gemini)
	            if [ -z "${GEMINI_API_KEY:-}" ]; then
	                missing_keys+=("GEMINI_API_KEY (for Gemini LLM)")
	            else
	                providers_checked+=("Gemini LLM")
	            fi
	            ;;
	        cohere)
	            if [ -z "${COHERE_API_KEY:-}" ]; then
	                missing_keys+=("COHERE_API_KEY (for Cohere LLM)")
	            else
	                providers_checked+=("Cohere LLM")
	            fi
	            ;;
	        azure)
	            if [ -z "${AZURE_API_KEY:-}" ]; then
	                missing_keys+=("AZURE_API_KEY (for Azure LLM)")
	            elif [ -z "${AZURE_ENDPOINT:-}" ]; then
	                missing_keys+=("AZURE_ENDPOINT (for Azure LLM)")
	            else
	                providers_checked+=("Azure LLM")
	            fi
	            ;;
        onnx)
            providers_checked+=("$llm_provider LLM (no API key required)")
            ;;
        *)
            print_warning "Unknown LLM provider: $llm_provider (skipping API key check)"
            ;;
    esac

    # Check Embedding provider API key
	    case "$embedding_provider" in
	        openai)
	            if [ -z "${OPENAI_API_KEY:-}" ]; then
	                missing_keys+=("OPENAI_API_KEY (for OpenAI Embedding)")
	            else
	                providers_checked+=("OpenAI Embedding")
	            fi
	            ;;
	        anthropic)
	            if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
	                missing_keys+=("ANTHROPIC_API_KEY (for Anthropic Embedding)")
	            else
	                providers_checked+=("Anthropic Embedding")
	            fi
	            ;;
	        gemini)
	            if [ -z "${GEMINI_API_KEY:-}" ]; then
	                missing_keys+=("GEMINI_API_KEY (for Gemini Embedding)")
	            else
	                providers_checked+=("Gemini Embedding")
	            fi
	            ;;
	        cohere)
	            if [ -z "${COHERE_API_KEY:-}" ]; then
	                missing_keys+=("COHERE_API_KEY (for Cohere Embedding)")
	            else
	                providers_checked+=("Cohere Embedding")
	            fi
	            ;;
	        azure)
	            if [ -z "${AZURE_API_KEY:-}" ]; then
	                missing_keys+=("AZURE_API_KEY (for Azure Embedding)")
	            elif [ -z "${AZURE_ENDPOINT:-}" ]; then
	                missing_keys+=("AZURE_ENDPOINT (for Azure Embedding)")
	            else
	                providers_checked+=("Azure Embedding")
	            fi
	            ;;
        onnx)
            providers_checked+=("$embedding_provider Embedding (no API key required)")
            ;;
        *)
            print_warning "Unknown Embedding provider: $embedding_provider (skipping API key check)"
            ;;
    esac

    # Report results
    if [ ${#missing_keys[@]} -gt 0 ]; then
        print_error "Missing required API keys for selected providers:"
        for key in "${missing_keys[@]}"; do
            echo "  - $key"
        done
        echo ""
        echo "Please set the required environment variables before running tests."
        return 1
    else
        print_success "API keys configured for: ${providers_checked[*]}"
        return 0
    fi
}

# Parse matrix spec first to determine providers
LLM_PROVIDER_TEMP=""
EMBEDDING_PROVIDER_TEMP=""

if [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+):(.+)$ ]]; then
    LLM_PROVIDER_TEMP="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER_TEMP="${BASH_REMATCH[2]}"
elif [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+)$ ]]; then
    LLM_PROVIDER_TEMP="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER_TEMP="${BASH_REMATCH[2]}"
else
    LLM_PROVIDER_TEMP="openai"
    EMBEDDING_PROVIDER_TEMP="onnx"
fi

# Check API keys based on providers
if ! check_provider_api_keys "$LLM_PROVIDER_TEMP" "$EMBEDDING_PROVIDER_TEMP"; then
    exit 1
fi

# Parse provider matrix (LLM:EMBEDDING[:VECTOR_DB])
LLM_PROVIDER=""
EMBEDDING_PROVIDER=""

if [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+):(.+)$ ]]; then
    LLM_PROVIDER="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER="${BASH_REMATCH[2]}"
    VECTOR_DB_FROM_SPEC="${BASH_REMATCH[3]}"
    MATRIX_SPEC="${LLM_PROVIDER}:${EMBEDDING_PROVIDER}"
    export AI_INFRASTRUCTURE_VECTOR_DATABASE="$VECTOR_DB_FROM_SPEC"
    print_info "Extracted vector DB from matrix spec: $VECTOR_DB_FROM_SPEC"
elif [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+)$ ]]; then
    LLM_PROVIDER="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER="${BASH_REMATCH[2]}"
else
    print_warning "Matrix spec not recognized, falling back to defaults (openai:onnx)"
    LLM_PROVIDER="openai"
    EMBEDDING_PROVIDER="onnx"
fi

# Override vector DB if passed as second arg
if [ -n "$VECTOR_DB" ]; then
    export AI_INFRASTRUCTURE_VECTOR_DATABASE="$VECTOR_DB"
    print_info "Vector DB (arg): $VECTOR_DB"
fi

# Default providers if not set
export AI_INFRASTRUCTURE_LLM_PROVIDER="${AI_INFRASTRUCTURE_LLM_PROVIDER:-$LLM_PROVIDER}"
export AI_INFRASTRUCTURE_EMBEDDING_PROVIDER="${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:-$EMBEDDING_PROVIDER}"
export AI_INFRASTRUCTURE_VECTOR_DATABASE="${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}"

print_header "Test Configuration"
print_info "Test Module: $TEST_MODULE"
print_info "Maven Profile: $MAVEN_PROFILE"
print_info "Spring Profiles: $SPRING_PROFILE"
print_info "Provider Matrix: ${AI_INFRASTRUCTURE_LLM_PROVIDER}:${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER}"
if [ -n "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" ]; then
    print_info "Vector DB: $AI_INFRASTRUCTURE_VECTOR_DATABASE"
fi
print_info "Test Classes: All *RealApiIntegrationTest.java in realapi/ directory"

# Fail fast on missing critical vector DB configuration (avoids long Spring stacktraces).
if [ "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" = "pinecone" ]; then
    if [ -z "${PINECONE_API_KEY:-}" ] && [ -z "${AI_PROVIDERS_PINECONE_API_KEY:-}" ]; then
        print_error "Pinecone selected but API key is missing."
        print_info "Set PINECONE_API_KEY (recommended: GitHub secret) or provide workflow input pinecone_api_key."
        exit 1
    fi

    if [ -z "${PINECONE_INDEX_NAME:-}" ] && [ -z "${AI_PROVIDERS_PINECONE_INDEX_NAME:-}" ] && \
       [ -z "${PINECONE_API_HOST:-}" ] && [ -z "${AI_PROVIDERS_PINECONE_API_HOST:-}" ]; then
        print_error "Pinecone selected but both index name and host are missing."
        print_info "Set PINECONE_INDEX_NAME or PINECONE_API_HOST (or their AI_PROVIDERS_* equivalents)."
        exit 1
    fi
fi

# Keep Spring's testcontainers support aligned with the selected vector database.
# The shared `testcontainers-support` module's `application-testcontainers.yml` uses VECTOR_DB_TYPE.
if [ -n "$AI_INFRASTRUCTURE_VECTOR_DATABASE" ] && [ -z "${VECTOR_DB_TYPE:-}" ]; then
    export VECTOR_DB_TYPE="$AI_INFRASTRUCTURE_VECTOR_DATABASE"
fi

# Check if dependencies are built (skip in CI/CD - already built by workflow)
if [ "${CI:-false}" == "true" ] || [ "${GITHUB_ACTIONS:-false}" == "true" ]; then
    print_info "Running in CI/CD - skipping dependency build check (already built by workflow)"
else
    # NOTE: The Maven reactor root is the ai-infrastructure-module directory (PROJECT_ROOT),
    # not integration-Testing. Run builds from there so local execution matches CI.
    PARENT_DIR="$PROJECT_ROOT"
    CORE_TARGET="${PROJECT_ROOT}/ai-fabric-core/target"
    if [ ! -d "$CORE_TARGET" ] || [ ! -f "$CORE_TARGET/ai-fabric-core-*.jar" ] 2>/dev/null; then
        print_warning "Dependencies may not be built. Attempting to build..."
        cd "$PARENT_DIR" || exit 1
        if ! mvn clean install -DskipTests -B -q; then
            print_error "Failed to build dependencies. Please run 'mvn clean install -DskipTests' from the parent module first."
            exit 1
        fi
        cd "$SCRIPT_DIR" || exit 1
        print_success "Dependencies built successfully"
    else
        print_success "Dependencies appear to be built"
    fi
fi

# Build Maven command (Failsafe)
print_header "Building Maven Command"

cd "$SCRIPT_DIR"

MAVEN_COMMAND="mvn -P${MAVEN_PROFILE}"
MAVEN_COMMAND="$MAVEN_COMMAND -Dspring.profiles.active=${SPRING_PROFILE}"
MAVEN_COMMAND="$MAVEN_COMMAND -DforkCount=1"
MAVEN_COMMAND="$MAVEN_COMMAND -DreuseForks=false"

# Ensure the vector DB type is explicitly set as a system property so:
# - Spring Boot auto-configurations match on `ai.vector-db.type`
# - TestcontainersInitializer treats container types as explicitly specified (even if profiles are misconfigured)
if [ -n "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.vector-db.type=${AI_INFRASTRUCTURE_VECTOR_DATABASE}"
fi

# Vector provider enablement:
# Some vector implementations (notably pinecone/milvus) fail fast during bean creation when disabled.
# Enable only the selected provider so real-api runs behave consistently across environments.
case "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" in
  pinecone)
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.pinecone.enabled=true"
    ;;
  weaviate)
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.weaviate.enabled=true"
    ;;
  qdrant)
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.qdrant.enabled=true"
    ;;
  milvus)
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.milvus.enabled=true"
    ;;
esac

# Pass embedding provider as system property (more reliable than environment variables)
# This ensures the selected provider from GitHub Actions UI is actually used
if [ -n "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.embedding-provider=$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"
    print_info "Configured embedding provider via system property: $AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"
fi

# Pass LLM provider as system property for consistency
if [ -n "$AI_INFRASTRUCTURE_LLM_PROVIDER" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.llm-provider=$AI_INFRASTRUCTURE_LLM_PROVIDER"
fi

# Enable providers based on selection
case "$AI_INFRASTRUCTURE_LLM_PROVIDER" in
    openai)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DOPENAI_ENABLED=true"
        ;;
    anthropic)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.anthropic.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DANTHROPIC_ENABLED=true"
        ;;
    gemini)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.gemini.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DGEMINI_ENABLED=true"
        ;;
    cohere)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DCOHERE_ENABLED=true"
        ;;
    azure)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DAZURE_ENABLED=true"
        ;;
esac

case "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" in
    openai)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DOPENAI_ENABLED=true"
        ;;
    cohere)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DCOHERE_ENABLED=true"
        ;;
    azure)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.enabled=true"
        MAVEN_COMMAND="$MAVEN_COMMAND -DAZURE_ENABLED=true"
        ;;
esac

# Auto-configure OpenAI embedding dimensions for Lucene compatibility
# OpenAI embeddings default to 1536 dimensions, but Lucene supports max 1024
# Check if we're using OpenAI embeddings with Lucene vector database
if [ "$EMBEDDING_PROVIDER" == "openai" ] && [ "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" == "lucene" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.embedding-dimensions=512"
    print_info "Auto-configured OpenAI embedding dimensions to 512 for Lucene compatibility"
fi

MAVEN_COMMAND="$MAVEN_COMMAND failsafe:integration-test failsafe:verify"

# ---------------------------------------------------------------------------
# Connectivity pre-check (fail fast on invalid credentials / unreachable provider)
# ---------------------------------------------------------------------------
print_header "Connectivity Verification"

CONNECTIVITY_COMMAND="mvn -P${MAVEN_PROFILE}"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dspring.profiles.active=${SPRING_PROFILE}"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -DforkCount=1"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -DreuseForks=false"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.realapi.connectivity.check=true"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dtest=RealApiConnectivityVerificationTest"

if [ -n "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" ]; then
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.vector-db.type=${AI_INFRASTRUCTURE_VECTOR_DATABASE}"
fi

case "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" in
  pinecone)
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.pinecone.enabled=true"
    ;;
  weaviate)
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.weaviate.enabled=true"
    ;;
  qdrant)
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.qdrant.enabled=true"
    ;;
  milvus)
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.milvus.enabled=true"
    ;;
esac

# Use the same provider selection + enablement flags as the main run.
if [ -n "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" ]; then
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.embedding-provider=$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER"
fi
if [ -n "$AI_INFRASTRUCTURE_LLM_PROVIDER" ]; then
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.llm-provider=$AI_INFRASTRUCTURE_LLM_PROVIDER"
fi

case "$AI_INFRASTRUCTURE_LLM_PROVIDER" in
    openai)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.openai.enabled=true -DOPENAI_ENABLED=true"
        ;;
    anthropic)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.anthropic.enabled=true -DANTHROPIC_ENABLED=true"
        ;;
    gemini)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.gemini.enabled=true -DGEMINI_ENABLED=true"
        ;;
    cohere)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.cohere.enabled=true -DCOHERE_ENABLED=true"
        ;;
    azure)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.azure.enabled=true -DAZURE_ENABLED=true"
        ;;
esac

case "$AI_INFRASTRUCTURE_EMBEDDING_PROVIDER" in
    openai)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.openai.enabled=true -DOPENAI_ENABLED=true"
        ;;
    cohere)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.cohere.enabled=true -DCOHERE_ENABLED=true"
        ;;
    azure)
        CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.azure.enabled=true -DAZURE_ENABLED=true"
        ;;
esac

print_info "Connectivity check command:"
echo "  $CONNECTIVITY_COMMAND"
echo ""

if eval "$CONNECTIVITY_COMMAND test"; then
    print_success "Connectivity verification passed"
else
    print_error "Connectivity verification failed - aborting test run"
    exit 1
fi

# Optional debug flag
if [ "${DEBUG:-false}" == "true" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -X"
fi

print_info "Working Directory: $(pwd)"

# Display the command
print_header "Executing Tests"
echo -e "${BLUE}Command:${NC}"
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
    print_header "Test Execution Failed"
    print_error "Maven execution failed (${duration}s)"
    exit $mvn_exit
fi

REPORTS_DIR="${SCRIPT_DIR}/target/failsafe-reports"
SCORECARD_DIR="${SCRIPT_DIR}/target/provider-matrix-reports"
SCORECARD_FILE="relationship-query-realapi-${AI_INFRASTRUCTURE_LLM_PROVIDER}-${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER}-${AI_INFRASTRUCTURE_VECTOR_DATABASE:-none}.json"
SCORECARD_PATH="${SCORECARD_DIR}/${SCORECARD_FILE}"

set +e
bash "$FAILSAFE_EVALUATOR" \
    --reports-dir "$REPORTS_DIR" \
    --scorecard-path "$SCORECARD_PATH" \
    --suite "$TEST_MODULE" \
    --llm "${AI_INFRASTRUCTURE_LLM_PROVIDER:-}" \
    --embedding "${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:-}" \
    --vector-db "${AI_INFRASTRUCTURE_VECTOR_DATABASE:-}" \
    --min-success-rate "${AI_PROVIDERS_REAL_API_MINIMUM_SUCCESS_RATE:-0.85}" \
    --min-considered-tests "${AI_PROVIDERS_REAL_API_MINIMUM_CONSIDERED_TESTS:-20}"
gate_exit=$?
set -e

print_header "Test Execution Complete"
print_info "Duration: ${duration}s"
print_info "Scorecard: ${SCORECARD_PATH}"

if [ $gate_exit -eq 0 ]; then
    print_success "Relationship-query RealAPI suite meets thresholds"
    exit 0
fi

print_error "Relationship-query RealAPI suite below thresholds (exit=${gate_exit})"
exit 1
