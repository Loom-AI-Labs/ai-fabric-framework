#!/usr/bin/env bash
set -euo pipefail

provider_name="${1:-}"
provider_type="${2:-}"
registry_file="ai-infrastructure-module/ai-fabric-core/src/main/resources/providers-registry.yml"

if [ -z "$provider_name" ] || [ -z "$provider_type" ]; then
  echo "Usage: get-provider-models.sh <provider-name> <provider-type>" >&2
  exit 1
fi

if [ ! -f "$registry_file" ]; then
  echo "Provider registry not found: $registry_file" >&2
  exit 1
fi

python3 - "$registry_file" "$provider_name" "$provider_type" <<'PY'
import sys
import yaml

registry_file, provider_name, provider_type = sys.argv[1], sys.argv[2], sys.argv[3]

with open(registry_file, "r", encoding="utf-8") as handle:
    registry = yaml.safe_load(handle) or {}

provider = ((registry.get("providers") or {}).get(provider_type) or {}).get(provider_name) or {}
print(provider.get("defaultModel") or "")
PY
