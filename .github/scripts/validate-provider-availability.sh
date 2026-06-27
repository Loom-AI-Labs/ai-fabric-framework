#!/usr/bin/env bash
set -euo pipefail

llm_provider="${1:-}"
embedding_provider="${2:-}"

if [ -z "$llm_provider" ] || [ -z "$embedding_provider" ]; then
  echo "Usage: validate-provider-availability.sh <llm_provider> <embedding_provider>" >&2
  exit 1
fi

errors=0

has_any_env() {
  for var_name in "$@"; do
    if [ -n "${!var_name:-}" ]; then
      return 0
    fi
  done
  return 1
}

require_any_env() {
  local label="$1"
  shift
  if has_any_env "$@"; then
    echo "OK: ${label}"
  else
    echo "ERROR: missing ${label}; expected one of: $*" >&2
    errors=$((errors + 1))
  fi
}

validate_llm_provider() {
  case "$llm_provider" in
    openai)
      require_any_env "OpenAI API key" OPENAI_API_KEY AI_PROVIDERS_OPENAI_API_KEY
      ;;
    anthropic)
      require_any_env "Anthropic API key" ANTHROPIC_API_KEY AI_PROVIDERS_ANTHROPIC_API_KEY
      ;;
    gemini)
      require_any_env "Gemini API key" GEMINI_API_KEY AI_PROVIDERS_GEMINI_API_KEY
      ;;
    cohere)
      require_any_env "Cohere API key" COHERE_API_KEY AI_PROVIDERS_COHERE_API_KEY
      ;;
    azure)
      require_any_env "Azure API key" AZURE_API_KEY AI_PROVIDERS_AZURE_API_KEY
      require_any_env "Azure endpoint" AZURE_ENDPOINT AI_PROVIDERS_AZURE_ENDPOINT
      require_any_env "Azure LLM deployment name" AZURE_DEPLOYMENT_NAME AI_PROVIDERS_AZURE_DEPLOYMENT_NAME
      ;;
    rest)
      echo "OK: REST LLM provider does not require a built-in API key."
      ;;
    onnx)
      echo "ERROR: ONNX is not a valid LLM provider." >&2
      errors=$((errors + 1))
      ;;
    *)
      echo "ERROR: unknown LLM provider: ${llm_provider}" >&2
      errors=$((errors + 1))
      ;;
  esac
}

validate_embedding_provider() {
  case "$embedding_provider" in
    onnx)
      echo "OK: ONNX embedding provider does not require an API key."
      ;;
    spring-ai-onnx)
      echo "OK: Spring AI ONNX embedding provider does not require an API key."
      ;;
    openai)
      require_any_env "OpenAI API key" OPENAI_API_KEY AI_PROVIDERS_OPENAI_API_KEY
      ;;
    anthropic)
      require_any_env "Anthropic API key" ANTHROPIC_API_KEY AI_PROVIDERS_ANTHROPIC_API_KEY
      ;;
    gemini)
      require_any_env "Gemini API key" GEMINI_API_KEY AI_PROVIDERS_GEMINI_API_KEY
      ;;
    cohere)
      require_any_env "Cohere API key" COHERE_API_KEY AI_PROVIDERS_COHERE_API_KEY
      ;;
    azure)
      require_any_env "Azure API key" AZURE_API_KEY AI_PROVIDERS_AZURE_API_KEY
      require_any_env "Azure endpoint" AZURE_ENDPOINT AI_PROVIDERS_AZURE_ENDPOINT
      require_any_env "Azure embedding deployment name" AZURE_EMBEDDING_DEPLOYMENT_NAME AI_PROVIDERS_AZURE_EMBEDDING_DEPLOYMENT_NAME
      ;;
    rest)
      echo "OK: REST embedding provider does not require a built-in API key."
      ;;
    *)
      echo "ERROR: unknown embedding provider: ${embedding_provider}" >&2
      errors=$((errors + 1))
      ;;
  esac
}

echo "Validating providers: LLM=${llm_provider}, embedding=${embedding_provider}"
validate_llm_provider
validate_embedding_provider

if [ "$errors" -gt 0 ]; then
  echo "Provider availability validation failed with ${errors} error(s)." >&2
  exit 1
fi

echo "Provider availability validation passed."
