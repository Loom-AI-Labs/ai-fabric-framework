#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="${script_dir}/verify-vector-readiness-health.sh"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

write_json() {
  local name="$1"
  shift
  cat >"${tmp_dir}/${name}.json"
}

expect_pass() {
  local name="$1"
  shift
  env VECTOR_READINESS_JSON_FILE="${tmp_dir}/${name}.json" "$@" "${verifier}" >/tmp/vector-readiness-test.out 2>/tmp/vector-readiness-test.err
}

expect_fail() {
  local name="$1"
  shift
  if env VECTOR_READINESS_JSON_FILE="${tmp_dir}/${name}.json" "$@" "${verifier}" >/tmp/vector-readiness-test.out 2>/tmp/vector-readiness-test.err; then
    echo "::error::Expected ${name} to fail" >&2
    echo "stdout:" >&2
    cat /tmp/vector-readiness-test.out >&2
    echo "stderr:" >&2
    cat /tmp/vector-readiness-test.err >&2
    exit 1
  fi
}

write_json rag_ready <<'JSON'
{
  "status": "UP",
  "vectorDatabase": {
    "provider": "qdrant",
    "readiness": {
      "status": "READY",
      "operational": true,
      "productionReady": true,
      "reasons": [],
      "warnings": []
    }
  }
}
JSON

write_json actuator_warn <<'JSON'
{
  "status": "UP",
  "details": {
    "readinessStatus": "WARN",
    "productionReady": false,
    "reasons": [],
    "warnings": ["Pinecone clear consistency waiting is disabled"]
  }
}
JSON

write_json raw_ready_with_qdrant_fallback_counter <<'JSON'
{
  "diagnosticsAvailable": true,
  "provider": "qdrant",
  "metadataFilterFallbacks": {
    "document": 1
  },
  "readiness": {
    "status": "READY",
    "operational": true,
    "productionReady": true,
    "reasons": [],
    "warnings": []
  }
}
JSON

write_json raw_ready_with_zero_fallback_counter <<'JSON'
{
  "diagnosticsAvailable": true,
  "provider": "qdrant",
  "metadataFilterFallbacks": {
    "document": 0
  },
  "countFallbacks": {},
  "readiness": {
    "status": "READY",
    "operational": true,
    "productionReady": true,
    "reasons": [],
    "warnings": []
  }
}
JSON

write_json advanced_health_with_weaviate_fallback <<'JSON'
{
  "status": "UP",
  "vectorDatabase": {
    "diagnosticsAvailable": true,
    "provider": "weaviate",
    "aggregateCountFallbacks": {
      "Document": 1
    },
    "aggregateCountFallbackReasons": {
      "Document": "aggregate endpoint returned unsupported"
    },
    "readiness": {
      "status": "WARN",
      "operational": true,
      "productionReady": false,
      "reasons": [],
      "warnings": [
        "Weaviate aggregate-count compatibility fallback has been used: {Document=1}"
      ]
    }
  }
}
JSON

write_json actuator_health_with_count_fallback <<'JSON'
{
  "status": "UP",
  "details": {
    "readinessStatus": "WARN",
    "productionReady": false,
    "reasons": [],
    "warnings": [
      "Vector provider count compatibility fallback has been used: {products=1}"
    ],
    "vectorDatabase": {
      "diagnosticsAvailable": true,
      "provider": "milvus",
      "countFallbacks": {
        "products": 1
      },
      "countFallbackReasons": {
        "products": "collection statistics did not include row_count"
      }
    }
  }
}
JSON

write_json not_ready <<'JSON'
{
  "status": "DOWN",
  "details": {
    "readinessStatus": "NOT_READY",
    "productionReady": false,
    "reasons": ["Vector provider diagnostics are unavailable: provider down"],
    "warnings": []
  }
}
JSON

write_json missing_verdict <<'JSON'
{
  "status": "UP",
  "provider": "custom"
}
JSON

expect_pass rag_ready
expect_fail actuator_warn
expect_pass actuator_warn VECTOR_READINESS_ALLOW_WARN=true
expect_fail raw_ready_with_qdrant_fallback_counter
expect_pass raw_ready_with_qdrant_fallback_counter VECTOR_READINESS_ALLOW_FALLBACKS=true
expect_pass raw_ready_with_zero_fallback_counter
expect_fail advanced_health_with_weaviate_fallback VECTOR_READINESS_ALLOW_WARN=true
expect_pass advanced_health_with_weaviate_fallback VECTOR_READINESS_ALLOW_WARN=true VECTOR_READINESS_ALLOW_FALLBACKS=true
expect_fail actuator_health_with_count_fallback VECTOR_READINESS_ALLOW_WARN=true
expect_pass actuator_health_with_count_fallback VECTOR_READINESS_ALLOW_WARN=true VECTOR_READINESS_ALLOW_FALLBACKS=true
expect_fail not_ready
expect_fail missing_verdict

echo "Vector readiness verifier tests passed."
