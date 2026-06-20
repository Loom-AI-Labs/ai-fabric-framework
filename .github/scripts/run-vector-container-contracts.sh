#!/usr/bin/env bash
#
# Runs the Docker/Testcontainers-backed VectorDatabaseService contract suite.
#
# Covered providers:
#   - Qdrant REST
#   - Qdrant gRPC
#   - Weaviate
#   - Milvus
#
# Optional image overrides:
#   TESTCONTAINERS_QDRANT_IMAGE=qdrant/qdrant:v1.16.1
#   TESTCONTAINERS_WEAVIATE_IMAGE=semitechnologies/weaviate:1.23.0
#   TESTCONTAINERS_MILVUS_IMAGE=milvusdb/milvus:v2.4.0
set -euo pipefail

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "::error::Required command not found: $1" >&2
    exit 1
  fi
}

require_cmd docker
require_cmd java
require_cmd mvn

if ! docker info >/dev/null 2>&1; then
  echo "::error::Docker is not available. Start Docker before running vector container contracts." >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "${script_dir}/../.." && pwd)"

read -r -a maven_args <<< "${MAVEN_ARGS:--B -V --no-transfer-progress}"
extra_args=()

if [[ -n "${TESTCONTAINERS_QDRANT_IMAGE:-}" ]]; then
  extra_args+=("-Dtestcontainers.qdrant.image=${TESTCONTAINERS_QDRANT_IMAGE}")
fi
if [[ -n "${TESTCONTAINERS_WEAVIATE_IMAGE:-}" ]]; then
  extra_args+=("-Dtestcontainers.weaviate.image=${TESTCONTAINERS_WEAVIATE_IMAGE}")
fi
if [[ -n "${TESTCONTAINERS_MILVUS_IMAGE:-}" ]]; then
  extra_args+=("-Dtestcontainers.milvus.image=${TESTCONTAINERS_MILVUS_IMAGE}")
fi

echo "Vector provider container contracts"
echo "  module: ai-infrastructure-module/integration-Testing/vector-contract-tests"
echo "  providers: qdrant-rest, qdrant-grpc, weaviate, milvus"
docker --version

cd "${project_root}/ai-infrastructure-module"
mvn_command=(mvn "${maven_args[@]}")
if ((${#extra_args[@]})); then
  mvn_command+=("${extra_args[@]}")
fi
mvn_command+=(
  clean
  verify
  -Pcontainer-contract-tests
  -pl
  integration-Testing/vector-contract-tests
  -am
)

"${mvn_command[@]}" "$@"
