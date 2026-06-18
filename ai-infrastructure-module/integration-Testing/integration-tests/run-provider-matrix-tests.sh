#!/bin/bash

###############################################################################
# Dynamic Provider Matrix Integration Test Runner
#
# This script runs integration tests dynamically with different provider
# combinations (LLM provider, embedding provider, and optionally vector database).
#
# Usage:
#   ./run-provider-matrix-tests.sh [COMBINATION] [VECTOR_DB] [TEST_CHUNK]
#
# Examples:
#   # Run with default (OpenAI + ONNX, all tests)
#   ./run-provider-matrix-tests.sh
#
#   # Run with specific combination
#   ./run-provider-matrix-tests.sh "openai:onnx"
#   ./run-provider-matrix-tests.sh "anthropic:openai"
#   ./run-provider-matrix-tests.sh "azure:azure"
#
#   # Run multiple combinations
#   ./run-provider-matrix-tests.sh "openai:onnx,anthropic:openai,azure:azure"
#
#   # Run with vector database specification
#   ./run-provider-matrix-tests.sh "openai:onnx" "pinecone"
#   ./run-provider-matrix-tests.sh "openai:onnx:memory"
#   ./run-provider-matrix-tests.sh "openai:onnx:lucene"
#
#   # Run specific test chunk (faster execution)
#   ./run-provider-matrix-tests.sh "openai:onnx" "" "core"
#   ./run-provider-matrix-tests.sh "openai:onnx" "" "vector"
#   ./run-provider-matrix-tests.sh "openai:onnx" "" "intent-actions"
#   ./run-provider-matrix-tests.sh "openai:onnx" "" "advanced"
#   ./run-provider-matrix-tests.sh "openai:onnx" "" "core,vector"
#
# Prerequisites:
#   - Java 21+
#   - Maven 3.8+
#   - Dependencies must be built and installed (run 'mvn clean install -DskipTests' from parent)
#   - API key environment variable set based on selected providers:
#     * OPENAI_API_KEY (for OpenAI)
#     * ANTHROPIC_API_KEY (for Anthropic)
#     * GEMINI_API_KEY (for Gemini)
#     * COHERE_API_KEY (for Cohere)
#     * AZURE_API_KEY + AZURE_ENDPOINT (for Azure)
#   - ONNX provider doesn't require API keys
#
# Note: This script assumes dependencies are already built. In CI/CD workflows,
#       the build step should run 'mvn clean install -DskipTests' first.
#
# Environment Variables:
#   OPENAI_API_KEY           - OpenAI API key (required if using OpenAI)
#   ANTHROPIC_API_KEY        - Anthropic API key (required if using Anthropic)
#   GEMINI_API_KEY           - Gemini API key (required if using Gemini)
#   COHERE_API_KEY           - Cohere API key (required if using Cohere)
#   AZURE_API_KEY            - Azure API key (required if using Azure)
#   AZURE_ENDPOINT            - Azure endpoint URL (required if using Azure)
#   AZURE_DEPLOYMENT_NAME     - Azure deployment name (required if using Azure for LLM)
#   AZURE_EMBEDDING_DEPLOYMENT_NAME - Azure embedding deployment (required if using Azure for Embedding)
#   SKIP_TESTS               - Set to skip tests (default: false)
#   MAVEN_LOGGING_LEVEL      - Maven logging level: quiet, normal, verbose, debug (default: quiet)
#   AI_PROVIDERS_REAL_API_TEST_CHUNK - Test chunk: core, vector, intent-actions, advanced, all (default: all)
#
# Test Chunks:
#   core            - Core functionality (3 test classes)
#   vector          - Vector operations (3 test classes)
#   intent-actions  - Intent & Actions (3 test classes)
#   advanced        - Advanced features (4 test classes)
#   all             - All tests (13 test classes, default)
#
###############################################################################

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

# Configuration
MATRIX_SPEC="${1:-openai:onnx}"
VECTOR_DB="${2:-}"
TEST_CHUNK="${3:-all}"
LOGGING_LEVEL="${MAVEN_LOGGING_LEVEL:-quiet}"
# Use SPRING_PROFILES_ACTIVE if set (e.g., from GitHub Actions), otherwise default to real-api-test
PROFILE="${SPRING_PROFILES_ACTIVE:-real-api-test}"
TEST_CLASS="RealAPIProviderMatrixIntegrationTest"
CONNECTIVITY_TEST_CLASS="RealApiConnectivityVerificationTest"
SKIP_TESTS="${SKIP_TESTS:-false}"

# Local convenience: allow loading OPENAI_API_KEY from an ignored file so developers
# don't have to export secrets manually (CI should use GitHub Secrets/Vars).
maybe_load_openai_api_key() {
    if [ -n "${OPENAI_API_KEY:-}" ]; then
        return
    fi

    # Prefer explicit env var pointing to a key file.
    local candidates=()
    if [ -n "${OPENAI_API_KEY_FILE:-}" ]; then
        candidates+=("${OPENAI_API_KEY_FILE}")
    fi

    # Common local patterns (all ignored by .gitignore).
    candidates+=("${PROJECT_ROOT}/.env.local" "${PROJECT_ROOT}/dev.env" "${PROJECT_ROOT}/dev2.env")

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

# If a local OpenAI key is present, enable the OpenAI provider for Spring Boot config that uses
# `openai.enabled: ${OPENAI_ENABLED:false}` (see application-real-api-test.yml).
if [ -n "${OPENAI_API_KEY:-}" ] && [ -z "${OPENAI_ENABLED:-}" ]; then
    export OPENAI_ENABLED=true
fi

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
# Parse matrix spec to determine which providers are being used
check_provider_api_keys() {
    local matrix_spec="$1"
    local missing_keys=()
    local providers_checked=()
    local providers_needed_llm=()
    local providers_needed_embedding=()

    # Handle comma-separated combinations - check all unique providers needed
    IFS=',' read -ra COMBINATIONS <<< "$matrix_spec"

    for combo in "${COMBINATIONS[@]}"; do
        # Extract providers from each combination (format: llm:embedding[:vectordb[:storage]])
        IFS=':' read -r llm_provider embedding_provider _vector_db _storage_strategy <<< "$combo"

        # Trim whitespace
        llm_provider=$(echo "$llm_provider" | xargs)
        embedding_provider=$(echo "$embedding_provider" | xargs)

        # Collect unique providers needed
        if [[ ! " ${providers_needed_llm[@]} " =~ " ${llm_provider} " ]]; then
            providers_needed_llm+=("$llm_provider")
        fi
        if [[ ! " ${providers_needed_embedding[@]} " =~ " ${embedding_provider} " ]]; then
            providers_needed_embedding+=("$embedding_provider")
        fi
    done

    # Check API keys for all unique LLM providers needed
    for llm_provider in "${providers_needed_llm[@]}"; do
        # Check LLM provider API key
        case "$llm_provider" in
        openai)
            if [ -z "$OPENAI_API_KEY" ]; then
                missing_keys+=("OPENAI_API_KEY (for OpenAI LLM)")
            else
                providers_checked+=("OpenAI LLM")
            fi
            ;;
        anthropic)
            if [ -z "$ANTHROPIC_API_KEY" ]; then
                missing_keys+=("ANTHROPIC_API_KEY (for Anthropic LLM)")
            else
                providers_checked+=("Anthropic LLM")
            fi
            ;;
        gemini)
            if [ -z "$GEMINI_API_KEY" ]; then
                missing_keys+=("GEMINI_API_KEY (for Gemini LLM)")
            else
                providers_checked+=("Gemini LLM")
            fi
            ;;
        cohere)
            if [ -z "$COHERE_API_KEY" ]; then
                missing_keys+=("COHERE_API_KEY (for Cohere LLM)")
            else
                providers_checked+=("Cohere LLM")
            fi
            ;;
        azure)
            if [ -z "$AZURE_API_KEY" ]; then
                missing_keys+=("AZURE_API_KEY (for Azure LLM)")
            elif [ -z "$AZURE_ENDPOINT" ]; then
                missing_keys+=("AZURE_ENDPOINT (for Azure LLM)")
            else
                providers_checked+=("Azure LLM")
            fi
            ;;
        onnx)
            # ONNX and REST don't require API keys
            providers_checked+=("$llm_provider LLM (no API key required)")
            ;;
        *)
            print_warning "Unknown LLM provider: $llm_provider (skipping API key check)"
            ;;
    esac
    done

    # Check API keys for all unique Embedding providers needed
    for embedding_provider in "${providers_needed_embedding[@]}"; do
        # Check Embedding provider API key
        case "$embedding_provider" in
        openai)
            if [ -z "$OPENAI_API_KEY" ]; then
                missing_keys+=("OPENAI_API_KEY (for OpenAI Embedding)")
            else
                providers_checked+=("OpenAI Embedding")
            fi
            ;;
        anthropic)
            if [ -z "$ANTHROPIC_API_KEY" ]; then
                missing_keys+=("ANTHROPIC_API_KEY (for Anthropic Embedding)")
            else
                providers_checked+=("Anthropic Embedding")
            fi
            ;;
        gemini)
            if [ -z "$GEMINI_API_KEY" ]; then
                missing_keys+=("GEMINI_API_KEY (for Gemini Embedding)")
            else
                providers_checked+=("Gemini Embedding")
            fi
            ;;
        cohere)
            if [ -z "$COHERE_API_KEY" ]; then
                missing_keys+=("COHERE_API_KEY (for Cohere Embedding)")
            else
                providers_checked+=("Cohere Embedding")
            fi
            ;;
        azure)
            if [ -z "$AZURE_API_KEY" ]; then
                missing_keys+=("AZURE_API_KEY (for Azure Embedding)")
            elif [ -z "$AZURE_ENDPOINT" ]; then
                missing_keys+=("AZURE_ENDPOINT (for Azure Embedding)")
            else
                providers_checked+=("Azure Embedding")
            fi
            ;;
        onnx)
            # ONNX and REST don't require API keys
            providers_checked+=("$embedding_provider Embedding (no API key required)")
            ;;
        *)
            print_warning "Unknown Embedding provider: $embedding_provider (skipping API key check)"
            ;;
    esac
    done

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

# Check API keys based on matrix spec
if ! check_provider_api_keys "$MATRIX_SPEC"; then
    exit 1
fi

# Check if dependencies are built (skip in CI/CD - already built by workflow)
if [ "${CI:-false}" == "true" ] || [ "${GITHUB_ACTIONS:-false}" == "true" ]; then
    print_info "Running in CI/CD - skipping dependency build check (already built by workflow)"
else
    # Build dependencies from the Maven reactor root.
    PARENT_DIR="${PROJECT_ROOT}/ai-infrastructure-module"
    if [ ! -f "${PARENT_DIR}/pom.xml" ]; then
        # Fallback in case PROJECT_ROOT resolution changes.
        PARENT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
    fi

    CORE_TARGET="${PARENT_DIR}/ai-fabric-core/target"
    if [ ! -d "$CORE_TARGET" ] || [ ! -f "$CORE_TARGET/ai-fabric-core-*.jar" ] 2>/dev/null; then
        print_warning "Dependencies may not be built. Attempting to build..."
        cd "$PARENT_DIR" || exit 1
        # Use quiet logging for dependency build unless debug is requested
        BUILD_LOG_FLAG="-q"
        if [ "$LOGGING_LEVEL" == "verbose" ] || [ "$LOGGING_LEVEL" == "debug" ]; then
            BUILD_LOG_FLAG=""
        fi
        if ! mvn clean install -DskipTests -B $BUILD_LOG_FLAG; then
            print_error "Failed to build dependencies. Please run 'mvn clean install -DskipTests' from the parent module first."
            exit 1
        fi
        cd "$SCRIPT_DIR" || exit 1
        print_success "Dependencies built successfully"
    else
        print_success "Dependencies appear to be built"
    fi
fi

# Build matrix specification
print_header "Test Configuration"

print_info "Matrix Specification: $MATRIX_SPEC"

# Parse matrix spec - supports llm:embedding[:vectordb[:storage]]
if [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+):([^:]+):([^:]+)$ ]]; then
    LLM_PROVIDER="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER="${BASH_REMATCH[2]}"
    VECTOR_DB_FROM_SPEC="${BASH_REMATCH[3]}"
    STORAGE_STRATEGY_FROM_SPEC="${BASH_REMATCH[4]}"
    export AI_INFRASTRUCTURE_VECTOR_DATABASE="$VECTOR_DB_FROM_SPEC"
    export AI_INFRASTRUCTURE_STORAGE_STRATEGY="$STORAGE_STRATEGY_FROM_SPEC"
    print_info "Extracted Vector DB: $VECTOR_DB_FROM_SPEC"
    print_info "Extracted Storage Strategy: $STORAGE_STRATEGY_FROM_SPEC"
elif [[ "$MATRIX_SPEC" =~ ^([^:]+):([^:]+):([^:]+)$ ]]; then
    LLM_PROVIDER="${BASH_REMATCH[1]}"
    EMBEDDING_PROVIDER="${BASH_REMATCH[2]}"
    VECTOR_DB_FROM_SPEC="${BASH_REMATCH[3]}"
    export AI_INFRASTRUCTURE_VECTOR_DATABASE="$VECTOR_DB_FROM_SPEC"
    print_info "Extracted Vector Database from matrix spec: $VECTOR_DB_FROM_SPEC"
elif [ -n "$VECTOR_DB" ]; then
    # Vector DB passed as separate parameter
    export AI_INFRASTRUCTURE_VECTOR_DATABASE="$VECTOR_DB"
    print_info "Vector Database: $VECTOR_DB"
fi

# Count combinations
COMBO_COUNT=$(echo "$MATRIX_SPEC" | awk -F',' '{print NF}')
print_info "Total Combinations: $COMBO_COUNT"
print_info "Test Chunk: $TEST_CHUNK"
print_info "Logging Level: $LOGGING_LEVEL"

# Build Maven command
print_header "Building Maven Command"

# Change to integration-tests directory for simpler Maven execution
TEST_DIR="$SCRIPT_DIR"
cd "$TEST_DIR"

# Note: This assumes dependencies are already built and installed.
# The workflow should run 'mvn clean install -DskipTests' from the parent module first.
#
# Maven CLI verbosity is separate from Spring logging. Map MAVEN_LOGGING_LEVEL to
# Maven flags so providers (including OpenAI) respect quiet/verbose consistently.
MAVEN_CLI_FLAGS="--no-transfer-progress"
case "$LOGGING_LEVEL" in
    quiet)
        MAVEN_CLI_FLAGS="-q $MAVEN_CLI_FLAGS"
        ;;
    error)
        MAVEN_CLI_FLAGS="-q $MAVEN_CLI_FLAGS"
        ;;
    normal)
        # default flags only
        ;;
    verbose)
        MAVEN_CLI_FLAGS="-e $MAVEN_CLI_FLAGS"
        ;;
    debug)
        MAVEN_CLI_FLAGS="-X -e $MAVEN_CLI_FLAGS"
        ;;
esac

MAVEN_COMMAND="mvn $MAVEN_CLI_FLAGS test"
MAVEN_COMMAND="$MAVEN_COMMAND -Dtest=$TEST_CLASS"
MAVEN_COMMAND="$MAVEN_COMMAND -Dspring.profiles.active=$PROFILE"
MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.real-api.matrix='$MATRIX_SPEC'"
MAVEN_COMMAND="$MAVEN_COMMAND -DforkCount=1"
MAVEN_COMMAND="$MAVEN_COMMAND -DreuseForks=false"

# Add model name properties from environment variables if set
if [ -n "$LLM_MODEL" ]; then
    # Determine which provider is being used and set appropriate property
    first_combo=$(echo "$MATRIX_SPEC" | cut -d',' -f1)
    IFS=':' read -r llm_provider _embedding_provider _vector_db _storage_strategy <<< "$first_combo"
    llm_provider=$(echo "$llm_provider" | xargs)

    case "$llm_provider" in
        openai)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.model=$LLM_MODEL"
            ;;
        anthropic)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.anthropic.model=$LLM_MODEL"
            ;;
        gemini)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.gemini.model=$LLM_MODEL"
            ;;
        cohere)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.model=$LLM_MODEL"
            ;;
        azure)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.deployment-name=$LLM_MODEL"
            ;;
    esac
    print_info "Using custom LLM model: $LLM_MODEL for provider: $llm_provider"
fi

if [ -n "$EMBEDDING_MODEL" ]; then
    # Determine which embedding provider is being used
    first_combo=$(echo "$MATRIX_SPEC" | cut -d',' -f1)
    IFS=':' read -r _llm_provider embedding_provider _vector_db _storage_strategy <<< "$first_combo"
    embedding_provider=$(echo "$embedding_provider" | xargs)

    case "$embedding_provider" in
        openai)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.embedding-model=$EMBEDDING_MODEL"
            ;;
        gemini)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.gemini.embedding-model=$EMBEDDING_MODEL"
            ;;
        cohere)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.cohere.embedding-model=$EMBEDDING_MODEL"
            ;;
        azure)
            MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.azure.embedding-deployment-name=$EMBEDDING_MODEL"
            ;;
    esac
    print_info "Using custom Embedding model: $EMBEDDING_MODEL for provider: $embedding_provider"
fi

# Add any additional Maven options from environment (set by workflow)
if [ -n "$MAVEN_OPTS" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND $MAVEN_OPTS"
fi

# Add vector database as system property if specified
if [ -n "$AI_INFRASTRUCTURE_VECTOR_DATABASE" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.vector-db.type=$AI_INFRASTRUCTURE_VECTOR_DATABASE"
fi

# Add storage strategy as system property if specified
if [ -n "$AI_INFRASTRUCTURE_STORAGE_STRATEGY" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai-infrastructure.storage.strategy=$AI_INFRASTRUCTURE_STORAGE_STRATEGY"
fi

# Auto-configure OpenAI embedding dimensions for Lucene compatibility
# OpenAI embeddings default to 1536 dimensions, but Lucene supports max 1024
# Check if we're using OpenAI embeddings with Lucene vector database
# Matrix spec format: "llm:embedding" or "llm:embedding:vectordb" (optional deprecated ":storageStrategy" is ignored)
# Check if embedding provider is "openai" (second field in colon-separated spec)
if [ "$AI_INFRASTRUCTURE_VECTOR_DATABASE" == "lucene" ]; then
    # Check if EMBEDDING_PROVIDER is explicitly set to "openai"
    if [ "$EMBEDDING_PROVIDER" == "openai" ]; then
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.embedding-dimensions=512"
        print_info "Auto-configured OpenAI embedding dimensions to 512 for Lucene compatibility"
    # Otherwise, check matrix spec for any combination with "openai" as embedding provider (2nd field)
    # Format: "something:openai" or "something:openai:something" (embedding is 2nd field)
    # Pattern matches: start or comma, then any chars, then colon, then "openai" as whole word, then colon/comma/end
    elif echo "$MATRIX_SPEC" | grep -qE "(^|,)([^:]+:)\bopenai\b(:|,|$)"; then
        MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.openai.embedding-dimensions=512"
        print_info "Auto-configured OpenAI embedding dimensions to 512 for Lucene compatibility (detected in matrix spec)"
    fi
fi

# Add test chunk as system property if specified
if [ -n "$TEST_CHUNK" ] && [ "$TEST_CHUNK" != "all" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -Dai.providers.real-api.test-chunk=$TEST_CHUNK"
fi

# Configure application logging level based on Maven logging level
# Map Maven logging levels to Spring Boot logging levels
# These override the DEBUG settings in application-test.yml
case "$LOGGING_LEVEL" in
    error)
        # Error mode: Only ERROR logs (suppress WARN/INFO/DEBUG)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.root=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.provider=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.core=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.embedding=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.org.springframework=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.org.hibernate=ERROR"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.it.realapi=ERROR"
        ;;
    quiet)
        # Quiet mode: Only WARN and ERROR (suppress all DEBUG/INFO)
        # But keep INFO for test execution logging to see which test classes are running
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.root=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.provider=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.core=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.embedding=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.org.springframework=WARN"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.org.hibernate=WARN"
        # Keep INFO level for test execution logging
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.it.realapi=INFO"
        ;;
    normal)
        # Normal mode: INFO level (suppress DEBUG)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.root=INFO"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric=INFO"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric.embedding=INFO"
        ;;
    verbose|debug)
        # Verbose/Debug mode: DEBUG level (keep all logs)
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.root=DEBUG"
        MAVEN_COMMAND="$MAVEN_COMMAND -Dlogging.level.ai.fabric=DEBUG"
        ;;
esac

# Add optional flags
if [ "$SKIP_TESTS" == "true" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -DskipTests"
fi

if [ "${DEBUG:-false}" == "true" ] && [ "$LOGGING_LEVEL" != "debug" ]; then
    MAVEN_COMMAND="$MAVEN_COMMAND -X"
fi

print_info "Working Directory: $(pwd)"
print_info "Maven Profile: $PROFILE"
print_info "Test Class: $TEST_CLASS"

# ---------------------------------------------------------------------------
# Connectivity pre-check (fail fast on invalid credentials / unreachable provider)
# ---------------------------------------------------------------------------
print_header "Connectivity Verification"

# Use the first provider pair from the matrix spec for the connectivity check.
# (Most manual runs use a single combination.)
CONNECTIVITY_LLM_PROVIDER=""
CONNECTIVITY_EMBEDDING_PROVIDER=""
FIRST_COMBO=$(echo "$MATRIX_SPEC" | awk -F',' '{print $1}' | xargs)
if [[ "$FIRST_COMBO" =~ ^([^:]+):([^:]+) ]]; then
    CONNECTIVITY_LLM_PROVIDER="${BASH_REMATCH[1]}"
    CONNECTIVITY_EMBEDDING_PROVIDER="${BASH_REMATCH[2]}"
fi

CONNECTIVITY_COMMAND="mvn $MAVEN_CLI_FLAGS test"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dtest=$CONNECTIVITY_TEST_CLASS"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dspring.profiles.active=$PROFILE"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -DforkCount=1"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -DreuseForks=false"
CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.realapi.connectivity.check=true"

if [ -n "$CONNECTIVITY_LLM_PROVIDER" ]; then
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.llm-provider=$CONNECTIVITY_LLM_PROVIDER"
fi
if [ -n "$CONNECTIVITY_EMBEDDING_PROVIDER" ]; then
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.embedding-provider=$CONNECTIVITY_EMBEDDING_PROVIDER"
fi

# Ensure required model properties exist for fail-fast config validation.
# IMPORTANT: treat empty env vars as "unset" and fall back to safe defaults.
if [ "$CONNECTIVITY_LLM_PROVIDER" = "openai" ]; then
    OPENAI_MODEL_EFFECTIVE="${OPENAI_MODEL:-${LLM_MODEL:-gpt-4o-mini}}"
    if [ -z "$OPENAI_MODEL_EFFECTIVE" ]; then
        OPENAI_MODEL_EFFECTIVE="gpt-4o-mini"
    fi
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.openai.model=$OPENAI_MODEL_EFFECTIVE"
fi
if [ "$CONNECTIVITY_EMBEDDING_PROVIDER" = "openai" ]; then
    OPENAI_EMBEDDING_MODEL_EFFECTIVE="${OPENAI_EMBEDDING_MODEL:-${EMBEDDING_MODEL:-text-embedding-3-small}}"
    if [ -z "$OPENAI_EMBEDDING_MODEL_EFFECTIVE" ]; then
        OPENAI_EMBEDDING_MODEL_EFFECTIVE="text-embedding-3-small"
    fi
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.openai.embedding-model=$OPENAI_EMBEDDING_MODEL_EFFECTIVE"
fi
if [ "$CONNECTIVITY_LLM_PROVIDER" = "anthropic" ]; then
    ANTHROPIC_MODEL_EFFECTIVE="${ANTHROPIC_MODEL:-${LLM_MODEL:-claude-3-haiku-20240307}}"
    if [ -z "$ANTHROPIC_MODEL_EFFECTIVE" ]; then
        ANTHROPIC_MODEL_EFFECTIVE="claude-3-haiku-20240307"
    fi
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.anthropic.model=$ANTHROPIC_MODEL_EFFECTIVE"
fi
if [ "$CONNECTIVITY_LLM_PROVIDER" = "cohere" ]; then
    COHERE_MODEL_EFFECTIVE="${COHERE_MODEL:-${LLM_MODEL:-command-r7b-12-2024}}"
    if [ -z "$COHERE_MODEL_EFFECTIVE" ]; then
        COHERE_MODEL_EFFECTIVE="command-r7b-12-2024"
    fi
    CONNECTIVITY_COMMAND="$CONNECTIVITY_COMMAND -Dai.providers.cohere.model=$COHERE_MODEL_EFFECTIVE"
fi

# Enable providers explicitly for the check (helps local runs where *_ENABLED isn't set).
case "$CONNECTIVITY_LLM_PROVIDER" in
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

print_info "Connectivity check provider: LLM=${CONNECTIVITY_LLM_PROVIDER:-unknown} Embedding=${CONNECTIVITY_EMBEDDING_PROVIDER:-unknown}"
print_info "Command:"
echo "  $CONNECTIVITY_COMMAND"
echo ""

if eval "$CONNECTIVITY_COMMAND"; then
    print_success "Connectivity verification passed"
else
    print_error "Connectivity verification failed - aborting test run"
    exit 1
fi

# Display the command
print_header "Executing Tests"
echo -e "${BLUE}Command:${NC}"
echo "  $MAVEN_COMMAND"
echo ""

# Execute the tests
start_time=$(date +%s)

if eval "$MAVEN_COMMAND"; then
    end_time=$(date +%s)
    duration=$((end_time - start_time))

    print_header "Test Execution Complete"
    print_success "All tests passed"
    print_info "Duration: ${duration}s"
    print_info "Combinations tested: $COMBO_COUNT"

    exit 0
else
    end_time=$(date +%s)
    duration=$((end_time - start_time))

    print_header "Test Execution Failed"
    print_error "Some tests failed or errored"
    print_info "Duration: ${duration}s"

    exit 1
fi
