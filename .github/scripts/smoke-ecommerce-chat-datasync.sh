#!/usr/bin/env bash
#
# Deterministic P0 smoke for the cross-app Data Sync proof:
#
#   ecommerce-store -> /api/ai/data-sync -> chat-capabilities-demo runtime search
#
# The script expects examples/real-apps to be packaged first. It starts the two
# packaged jars under the offline smoke profile, creates a unique product through
# ecommerce-store, waits until chat-capabilities-demo can retrieve it from the
# vector index, deletes the product through ecommerce-store, then proves the same
# runtime search query no longer returns the stale vector.
set -euo pipefail

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "::error::Required command not found: $1" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd java
require_cmd python3

apps_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../examples/real-apps" && pwd)"
work_dir="$(mktemp -d)"

chat_port="${CHAT_DATASYNC_SMOKE_CHAT_PORT:-19197}"
store_port="${CHAT_DATASYNC_SMOKE_STORE_PORT:-19196}"
boot_timeout="${CHAT_DATASYNC_SMOKE_BOOT_TIMEOUT:-90}"
poll_timeout="${CHAT_DATASYNC_SMOKE_POLL_TIMEOUT:-45}"

chat_base="http://127.0.0.1:${chat_port}"
store_base="http://127.0.0.1:${store_port}"
chat_log="${work_dir}/chat-capabilities-demo.log"
store_log="${work_dir}/ecommerce-store.log"
search_body="${work_dir}/runtime-search.json"
create_body="${work_dir}/created-product.json"
delete_body="${work_dir}/deleted-product.json"

chat_pid=""
store_pid=""
product_id=""

show_logs() {
  for log in "${chat_log}" "${store_log}"; do
    if [[ -f "${log}" ]]; then
      echo "----- last 80 log lines: ${log##*/} -----" >&2
      tail -n 80 "${log}" >&2 || true
      echo "----------------------------------------" >&2
    fi
  done
}

cleanup() {
  if [[ -n "${store_pid}" ]] && kill -0 "${store_pid}" 2>/dev/null; then
    kill "${store_pid}" 2>/dev/null || true
    wait "${store_pid}" 2>/dev/null || true
  fi
  if [[ -n "${chat_pid}" ]] && kill -0 "${chat_pid}" 2>/dev/null; then
    kill "${chat_pid}" 2>/dev/null || true
    wait "${chat_pid}" 2>/dev/null || true
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT

fail() {
  echo "::error::$*" >&2
  show_logs
  exit 1
}

find_jar() {
  local app="$1"
  local target_dir="${apps_dir}/${app}/target"
  local jar

  jar="$(
    find "${target_dir}" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*.original' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      | sort \
      | head -n 1
  )"

  if [[ -z "${jar}" ]]; then
    fail "${app}: no boot jar found under ${target_dir}; run 'mvn -f examples/real-apps/pom.xml install' first"
  fi

  printf '%s\n' "${jar}"
}

wait_for_health() {
  local name="$1"
  local base_url="$2"
  local pid="$3"
  local health_file="${work_dir}/${name}-health.json"

  for _ in $(seq 1 "${boot_timeout}"); do
    if curl -fsS "${base_url}/actuator/health" -o "${health_file}" >/dev/null 2>&1 \
      && grep -q '"status":"UP"' "${health_file}"; then
      echo "  ✓ ${name} is healthy (${base_url})"
      return 0
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
      fail "${name}: process exited before health became UP"
    fi
    sleep 1
  done

  fail "${name}: health did not become UP within ${boot_timeout}s"
}

http_json() {
  local method="$1"
  local url="$2"
  local body="$3"
  local output_file="$4"
  local status

  if [[ -n "${body}" ]]; then
    status="$(
      curl -sS -o "${output_file}" -w '%{http_code}' \
        -X "${method}" \
        -H 'Content-Type: application/json' \
        --data "${body}" \
        "${url}"
    )"
  else
    status="$(
      curl -sS -o "${output_file}" -w '%{http_code}' \
        -X "${method}" \
        "${url}"
    )"
  fi

  if [[ "${status}" -lt 200 || "${status}" -ge 300 ]]; then
    echo "HTTP ${status} from ${method} ${url}" >&2
    cat "${output_file}" >&2 || true
    fail "Unexpected HTTP status ${status}"
  fi
}

search_runtime() {
  local query="$1"
  local status

  status="$(
    curl -sS -G -o "${search_body}" -w '%{http_code}' \
      --data-urlencode 'vectorSpace=product' \
      --data-urlencode "q=${query}" \
      --data-urlencode 'limit=5' \
      --data-urlencode 'threshold=0.0' \
      "${chat_base}/api/runtime/vector-search" || true
  )"

  [[ "${status}" == "200" ]]
}

search_contains_sku() {
  local sku="$1"
  python3 -c '
import json
import sys

sku = sys.argv[1]
try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(1)

for result in payload.get("results") or []:
    if not isinstance(result, dict):
        continue
    if str(result.get("entityId")) == sku or str(result.get("id")) == sku:
        sys.exit(0)
    if sku in str(result.get("content", "")):
        sys.exit(0)
    metadata = result.get("metadata")
    if isinstance(metadata, dict) and sku in json.dumps(metadata, sort_keys=True):
        sys.exit(0)

sys.exit(1)
' "${sku}" < "${search_body}"
}

wait_for_search_present() {
  local sku="$1"
  local query="$2"

  for _ in $(seq 1 "${poll_timeout}"); do
    if search_runtime "${query}" && search_contains_sku "${sku}"; then
      echo "  ✓ runtime vector search found ${sku}"
      return 0
    fi
    sleep 1
  done

  cat "${search_body}" >&2 || true
  fail "runtime vector search did not return ${sku} within ${poll_timeout}s"
}

wait_for_search_absent() {
  local sku="$1"
  local query="$2"

  for _ in $(seq 1 "${poll_timeout}"); do
    if search_runtime "${query}" && ! search_contains_sku "${sku}"; then
      echo "  ✓ runtime vector search no longer returns ${sku}"
      return 0
    fi
    sleep 1
  done

  cat "${search_body}" >&2 || true
  fail "runtime vector search still returned deleted ${sku} after ${poll_timeout}s"
}

chat_jar="$(find_jar chat-capabilities-demo)"
store_jar="$(find_jar ecommerce-store)"
run_id="$(date +%s)-$$"
sku="P0-SMOKE-${run_id}"
name="P0 Smoke Data Sync Keyboard ${run_id}"
description="P0 deterministic data sync proof product ${run_id}"
category="P0 Data Sync"
tags="p0-smoke,data-sync,vector-cache"
price="424.24"
currency="USD"
quantity="17"

product_payload="$(
  python3 -c '
import json
import sys

sku, name, description, category, tags, price, currency, quantity = sys.argv[1:]
payload = {
    "sku": sku,
    "name": name,
    "description": description,
    "category": category,
    "tags": tags,
    "price": float(price),
    "currency": currency,
    "inStockQty": int(quantity),
}
print(json.dumps(payload, separators=(",", ":")))
' "${sku}" "${name}" "${description}" "${category}" "${tags}" "${price}" "${currency}" "${quantity}"
)"

# The smoke embedding provider is deterministic rather than semantic. Query the
# receiver-owned canonical projection exactly so this verifies the hardened
# Data Sync projection contract instead of relying on the legacy raw JSON body.
indexed_content="$(
  printf 'name: %s\ndescription: %s\nsku: %s\ncategory: %s\ntags: %s\nprice: %s\ncurrency: %s\ninStockQty: %s' \
    "${name}" \
    "${description}" \
    "${sku}" \
    "${category}" \
    "${tags}" \
    "${price}" \
    "${currency}" \
    "${quantity}"
)"

echo "P0 ecommerce -> chat data-sync smoke"
echo "  chat runtime: ${chat_base}"
echo "  ecommerce:    ${store_base}"

java -jar "${chat_jar}" \
  --spring.profiles.active=smoke \
  --server.port="${chat_port}" \
  --spring.main.banner-mode=off \
  --spring.datasource.url="jdbc:h2:mem:chat_datasync_smoke_${run_id};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  --app.admin.enabled=false \
  --ai.data-sync.enabled=true >"${chat_log}" 2>&1 &
chat_pid=$!
wait_for_health "chat-capabilities-demo" "${chat_base}" "${chat_pid}"

java -jar "${store_jar}" \
  --spring.profiles.active=smoke \
  --server.port="${store_port}" \
  --spring.main.banner-mode=off \
  --spring.datasource.url="jdbc:h2:mem:ecommerce_datasync_smoke_${run_id};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  --app.demo.seed-data=false \
  --app.admin.enabled=false \
  --connector.indexing.enabled=true \
  --connector.indexing.runtime-base-url="${chat_base}" >"${store_log}" 2>&1 &
store_pid=$!
wait_for_health "ecommerce-store" "${store_base}" "${store_pid}"

http_json POST "${store_base}/api/products" "${product_payload}" "${create_body}"
product_id="$(
  python3 -c 'import json, sys; print(json.load(sys.stdin).get("id", ""))' < "${create_body}"
)"
if [[ -z "${product_id}" ]]; then
  cat "${create_body}" >&2 || true
  fail "ecommerce-store did not return a product id"
fi
echo "  ✓ ecommerce-store created ${sku} as product id ${product_id}"

wait_for_search_present "${sku}" "${indexed_content}"

http_json DELETE "${store_base}/api/products/${product_id}" "" "${delete_body}"
echo "  ✓ ecommerce-store deleted ${sku}"

wait_for_search_absent "${sku}" "${indexed_content}"

echo "P0 ecommerce -> chat data-sync smoke passed."
