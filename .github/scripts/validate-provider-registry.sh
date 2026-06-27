#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
registry_file="$project_root/ai-infrastructure-module/ai-fabric-core/src/main/resources/providers-registry.yml"
provider_workflow_file="$project_root/.github/workflows/provider-configuration-check.yml"

if [ ! -f "$registry_file" ]; then
  echo "Provider registry not found: $registry_file" >&2
  exit 1
fi

if [ ! -f "$provider_workflow_file" ]; then
  echo "Provider workflow not found: $provider_workflow_file" >&2
  exit 1
fi

python3 - "$registry_file" "$provider_workflow_file" <<'PY'
import sys
import yaml

registry_file, workflow_file = sys.argv[1], sys.argv[2]

with open(registry_file, "r", encoding="utf-8") as handle:
    registry = yaml.safe_load(handle) or {}

with open(workflow_file, "r", encoding="utf-8") as handle:
    workflow = yaml.safe_load(handle) or {}

providers = registry.get("providers", {})
registry_llm_all = sorted((providers.get("llm") or {}).keys())
registry_embedding_all = sorted((providers.get("embedding") or {}).keys())
registry_llm_enabled = sorted(
    name
    for name, definition in (providers.get("llm") or {}).items()
    if (definition or {}).get("enabled", True)
)
registry_embedding_enabled = sorted(
    name
    for name, definition in (providers.get("embedding") or {}).items()
    if (definition or {}).get("enabled", True)
)

on_block = workflow.get("on") or workflow.get(True) or {}
dispatch = on_block.get("workflow_dispatch") or {}
inputs = dispatch.get("inputs") or {}

workflow_llm = sorted((inputs.get("llm_provider") or {}).get("options") or [])
workflow_embedding = sorted((inputs.get("embedding_provider") or {}).get("options") or [])

errors = []
if registry_llm_enabled != workflow_llm:
    errors.append(
        "Enabled LLM provider mismatch. Registry=%s Workflow=%s"
        % (", ".join(registry_llm_enabled), ", ".join(workflow_llm))
    )

if registry_embedding_enabled != workflow_embedding:
    errors.append(
        "Enabled embedding provider mismatch. Registry=%s Workflow=%s"
        % (", ".join(registry_embedding_enabled), ", ".join(workflow_embedding))
    )

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    sys.exit(1)

print("Provider registry validation passed.")
print("Modeled LLM providers:", ", ".join(registry_llm_all))
print("Enabled LLM providers:", ", ".join(registry_llm_enabled))
print("Modeled embedding providers:", ", ".join(registry_embedding_all))
print("Enabled embedding providers:", ", ".join(registry_embedding_enabled))
PY
