#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  RUNTIME_BASE_URL=https://runtime.example.com .github/scripts/verify-vector-readiness-health.sh
  VECTOR_READINESS_URL=https://runtime.example.com/actuator/health/vectorProvider .github/scripts/verify-vector-readiness-health.sh
  VECTOR_READINESS_JSON_FILE=/path/to/health.json .github/scripts/verify-vector-readiness-health.sh

Environment:
  VECTOR_READINESS_ALLOW_WARN=true       Allow WARN / productionReady=false to pass.
  VECTOR_READINESS_ALLOW_FALLBACKS=true  Allow provider compatibility fallback evidence to pass.

Accepted response shapes:
  - /actuator/health/vectorProvider component response
  - /api/ai/advanced-rag/health response with vectorDatabase.readiness
  - raw vectorDatabase diagnostics with readiness
  - raw readiness object
USAGE
}

json_file="${VECTOR_READINESS_JSON_FILE:-}"
url="${VECTOR_READINESS_URL:-}"

if [[ -z "${url}" && -n "${RUNTIME_BASE_URL:-}" ]]; then
  url="${RUNTIME_BASE_URL%/}/actuator/health/vectorProvider"
fi

tmp_file=""
cleanup() {
  if [[ -n "${tmp_file}" ]]; then
    rm -f "${tmp_file}"
  fi
}
trap cleanup EXIT

if [[ -n "${json_file}" ]]; then
  if [[ ! -f "${json_file}" ]]; then
    echo "::error::Vector readiness JSON file not found: ${json_file}" >&2
    exit 1
  fi
elif [[ -n "${url}" ]]; then
  tmp_file="$(mktemp)"
  curl -fsS "${url}" >"${tmp_file}"
  json_file="${tmp_file}"
else
  usage
  exit 2
fi

python3 - "$json_file" "${VECTOR_READINESS_ALLOW_WARN:-false}" "${VECTOR_READINESS_ALLOW_FALLBACKS:-false}" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
allow_warn = sys.argv[2].lower() in {"1", "true", "yes", "y"}
allow_fallbacks = sys.argv[3].lower() in {"1", "true", "yes", "y"}

try:
    payload = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"::error::Unable to parse vector readiness JSON: {exc}", file=sys.stderr)
    sys.exit(1)


def as_bool(value, default=False):
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y"}
    return default


def as_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def has_entries(value):
    if value is None:
        return False
    if isinstance(value, dict):
        return bool(value)
    if isinstance(value, (list, tuple, set)):
        return bool(value)
    if isinstance(value, str):
        return bool(value.strip())
    return True


def is_positive(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value > 0
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return False
        try:
            return float(text) > 0
        except ValueError:
            return True
    return has_entries(value)


def has_positive_counters(value):
    if isinstance(value, dict):
        return any(is_positive(entry) for entry in value.values())
    return is_positive(value)


def compact(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"))
    except TypeError:
        return str(value)


def find_readiness(root):
    if not isinstance(root, dict):
        return {}

    if isinstance(root.get("readiness"), dict):
        return dict(root["readiness"])

    vector_database = root.get("vectorDatabase")
    if isinstance(vector_database, dict) and isinstance(vector_database.get("readiness"), dict):
        return dict(vector_database["readiness"])

    details = root.get("details")
    if isinstance(details, dict):
        if isinstance(details.get("readiness"), dict):
            return dict(details["readiness"])
        vector_database = details.get("vectorDatabase")
        if isinstance(vector_database, dict) and isinstance(vector_database.get("readiness"), dict):
            return dict(vector_database["readiness"])
        if "readinessStatus" in details:
            return {
                "status": details.get("readinessStatus"),
                "operational": root.get("status") == "UP",
                "productionReady": details.get("productionReady"),
                "reasons": details.get("reasons", []),
                "warnings": details.get("warnings", []),
            }

    if "readinessStatus" in root:
        return {
            "status": root.get("readinessStatus"),
            "operational": root.get("status") == "UP",
            "productionReady": root.get("productionReady"),
            "reasons": root.get("reasons", []),
            "warnings": root.get("warnings", []),
        }

    if "status" in root and ("productionReady" in root or "operational" in root):
        return root

    return {}


def diagnostic_roots(root):
    roots = []

    def add(path_name, value):
        if isinstance(value, dict):
            roots.append((path_name, value))

    add("$", root)
    if isinstance(root, dict):
        add("$.vectorDatabase", root.get("vectorDatabase"))
        details = root.get("details")
        if isinstance(details, dict):
            add("$.details", details)
            add("$.details.vectorDatabase", details.get("vectorDatabase"))
    return roots


def fallback_evidence(root, readiness):
    counter_keys = {
        "metadataFilterFallbacks",
        "aggregateCountFallbacks",
        "countFallbacks",
    }
    reason_keys = {
        "metadataFilterFallbackReasons",
        "aggregateCountFallbackReasons",
        "countFallbackReasons",
    }

    evidence = []
    seen = set()
    for path_name, diagnostics in diagnostic_roots(root):
        for key in sorted(counter_keys):
            if key in diagnostics and has_positive_counters(diagnostics[key]):
                item = f"{path_name}.{key}={compact(diagnostics[key])}"
                if item not in seen:
                    evidence.append(item)
                    seen.add(item)
        for key in sorted(reason_keys):
            if key in diagnostics and has_entries(diagnostics[key]):
                item = f"{path_name}.{key}={compact(diagnostics[key])}"
                if item not in seen:
                    evidence.append(item)
                    seen.add(item)

    for warning in as_list(readiness.get("warnings")):
        text = str(warning)
        normalized = text.lower()
        if "compatibility fallback" in normalized or "fallback has been used" in normalized:
            item = f"readiness.warning={text}"
            if item not in seen:
                evidence.append(item)
                seen.add(item)

    return evidence


readiness = find_readiness(payload)
status = str(readiness.get("status", "")).upper()
operational = as_bool(readiness.get("operational"), status in {"READY", "WARN"})
production_ready = as_bool(readiness.get("productionReady"), status == "READY")
reasons = as_list(readiness.get("reasons"))
warnings = as_list(readiness.get("warnings"))
fallbacks = fallback_evidence(payload, readiness)

if not status:
    print("::error::Vector readiness verdict not found in health response.", file=sys.stderr)
    sys.exit(1)

summary = (
    f"Vector readiness: status={status}, operational={operational}, "
    f"productionReady={production_ready}"
)
print(summary)
if reasons:
    print("Reasons:")
    for reason in reasons:
        print(f"- {reason}")
if warnings:
    print("Warnings:")
    for warning in warnings:
        print(f"- {warning}")
if fallbacks:
    print("Compatibility fallback evidence:")
    for fallback in fallbacks:
        print(f"- {fallback}")

if status == "NOT_READY" or not operational:
    print("::error::Vector provider is not operational.", file=sys.stderr)
    sys.exit(1)

if fallbacks and not allow_fallbacks:
    print("::error::Vector provider compatibility fallback evidence is present. Set VECTOR_READINESS_ALLOW_FALLBACKS=true only for an explicitly accepted release exception.", file=sys.stderr)
    sys.exit(1)

if status != "READY" or not production_ready:
    if allow_warn:
        print("::warning::Vector provider is operational but not clean production-ready.")
        sys.exit(0)
    print("::error::Vector provider is not clean production-ready. Set VECTOR_READINESS_ALLOW_WARN=true to allow WARN.", file=sys.stderr)
    sys.exit(1)

sys.exit(0)
PY
