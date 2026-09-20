#!/usr/bin/env bash
#
# Packaged-runtime proof for the governed document-indexing lifecycle.
#
# Package the real-app reactor before running this script. The smoke starts the
# executable workbench JAR with deterministic local providers, then proves that
# preview is side-effect free, activation follows durable work completion,
# replacement is new-first, deletes use exact manifest identities, and tenant
# boundaries remain visible at retrieval time.
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

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app_dir="${repo_root}/examples/real-apps/document-ingestion-workbench"
work_dir="$(mktemp -d)"
port="${DOCUMENT_INGESTION_SMOKE_PORT:-19098}"
boot_timeout="${DOCUMENT_INGESTION_SMOKE_BOOT_TIMEOUT:-90}"
poll_timeout="${DOCUMENT_INGESTION_SMOKE_POLL_TIMEOUT:-45}"
base_url="http://127.0.0.1:${port}"
app_log="${work_dir}/document-ingestion-workbench.log"
app_pid=""

show_log() {
  if [[ -f "${app_log}" ]]; then
    echo "----- last 120 application log lines -----" >&2
    tail -n 120 "${app_log}" >&2 || true
    echo "------------------------------------------" >&2
  fi
}

cleanup() {
  if [[ -n "${app_pid}" ]] && kill -0 "${app_pid}" 2>/dev/null; then
    kill "${app_pid}" 2>/dev/null || true
    wait "${app_pid}" 2>/dev/null || true
  fi
  if [[ "${DOCUMENT_INGESTION_SMOKE_KEEP_WORK_DIR:-false}" == "true" ]]; then
    echo "Smoke work directory retained at ${work_dir}" >&2
  else
    rm -rf "${work_dir}"
  fi
}
trap cleanup EXIT

fail() {
  echo "::error::$*" >&2
  show_log
  exit 1
}

jar="$({
  find "${app_dir}/target" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*.original' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    | sort \
    | head -n 1
} 2>/dev/null || true)"

if [[ -z "${jar}" ]]; then
  fail "No packaged workbench JAR found. Run the documented Maven package command first."
fi

json_value() {
  local file="$1"
  shift
  python3 - "${file}" "$@" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
for key in sys.argv[2:]:
    value = value[int(key)] if isinstance(value, list) else value[key]
if value is None:
    print("null")
elif isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
PY
}

assert_json() {
  local file="$1"
  local expression="$2"
  local message="$3"
  if ! python3 - "${file}" "${expression}" "${message}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    payload = json.load(stream)
scope = {
    "p": payload,
    "all": all,
    "any": any,
    "len": len,
    "str": str,
    "__builtins__": {},
}
if not eval(sys.argv[2], scope, {}):
    print(json.dumps(payload, indent=2), file=sys.stderr)
    print(sys.argv[3], file=sys.stderr)
    sys.exit(1)
PY
  then
    fail "${message}"
  fi
}

request() {
  local expected="$1"
  local output="$2"
  shift 2
  local status
  status="$(curl -sS -o "${output}" -w '%{http_code}' "$@")"
  if [[ "${status}" != "${expected}" ]]; then
    cat "${output}" >&2 || true
    fail "Expected HTTP ${expected}, received ${status}: curl $*"
  fi
}

query() {
  local text="$1"
  local tenant="$2"
  local output="$3"
  request 200 "${output}" \
    --get \
    --data-urlencode "query=${text}" \
    --data-urlencode "tenantId=${tenant}" \
    --data-urlencode 'limit=10' \
    "${base_url}/api/documents/query"
}

wait_for_source() {
  local source_id="$1"
  local expected_status="$2"
  local expected_active_version="$3"
  local output="$4"

  for _ in $(seq 1 "${poll_timeout}"); do
    if curl -fsS "${base_url}/api/documents/sources/${source_id}" \
      -o "${output}" >/dev/null 2>&1; then
      if python3 - "${output}" "${expected_status}" "${expected_active_version}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    payload = json.load(stream)
source = payload.get("source") or {}
expected_version = None if sys.argv[3] == "null" else int(sys.argv[3])
ok = source.get("status") == sys.argv[2] and source.get("activeVersion") == expected_version
sys.exit(0 if ok else 1)
PY
      then
        return 0
      fi
    fi
    if ! kill -0 "${app_pid}" 2>/dev/null; then
      fail "Workbench exited while waiting for ${expected_status}"
    fi
    sleep 1
  done
  cat "${output}" >&2 || true
  fail "Source ${source_id} did not reach ${expected_status} with active version ${expected_active_version}"
}

wait_for_manifest() {
  local source_id="$1"
  local version="$2"
  local expected_state="$3"
  local output="$4"

  for _ in $(seq 1 "${poll_timeout}"); do
    curl -fsS "${base_url}/api/documents/sources/${source_id}" \
      -o "${output}" >/dev/null 2>&1 || true
    if python3 - "${output}" "${version}" "${expected_state}" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as stream:
        payload = json.load(stream)
except Exception:
    sys.exit(1)
version = int(sys.argv[2])
state = sys.argv[3]
matches = [run for run in payload.get("manifests", []) if run.get("sourceVersion") == version]
sys.exit(0 if matches and all(run.get("state") == state for run in matches) else 1)
PY
    then
      return 0
    fi
    sleep 1
  done
  cat "${output}" >&2 || true
  fail "Source ${source_id} version ${version} did not reach manifest state ${expected_state}"
}

printf '%s\n' \
  'Version one incident policy: rotate compromised credentials within four hours.' \
  'Notify the tenant security owner and preserve audit evidence.' \
  >"${work_dir}/runbook-v1.txt"
printf '%s\n' \
  'Version two incident policy: rotate compromised credentials within two hours.' \
  'Notify security and customer success, preserve audit evidence, and open a review task.' \
  >"${work_dir}/runbook-v2.txt"
printf '%s\n' 'not a trusted document format' >"${work_dir}/unsafe.exe"
cat >"${work_dir}/refund-policy.json" <<'JSON'
{"title":"Refunds","content":"JSON refund policy: approved returns are accepted within thirty days."}
JSON
python3 - "${work_dir}/oversized.txt" <<'PY'
import sys
with open(sys.argv[1], "w", encoding="utf-8") as stream:
    stream.write("x" * 1_000_001)
PY

run_id="$(date +%s)-$$"
echo "Document ingestion packaged smoke"
echo "  runtime: ${base_url}"

java -jar "${jar}" \
  --spring.profiles.active=smoke \
  --server.port="${port}" \
  --spring.main.banner-mode=off \
  --spring.datasource.url="jdbc:h2:mem:document_ingestion_smoke_${run_id};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  --spring.jpa.hibernate.ddl-auto=create-drop \
  --document-workbench.trusted-root="${work_dir}/trusted" \
  >"${app_log}" 2>&1 &
app_pid=$!

for _ in $(seq 1 "${boot_timeout}"); do
  if curl -fsS "${base_url}/actuator/health" \
    -o "${work_dir}/health.json" >/dev/null 2>&1 \
    && grep -q '"status":"UP"' "${work_dir}/health.json"; then
    echo "  + packaged application is healthy"
    break
  fi
  if ! kill -0 "${app_pid}" 2>/dev/null; then
    fail "Workbench exited before becoming healthy"
  fi
  sleep 1
done
grep -q '"status":"UP"' "${work_dir}/health.json" \
  || fail "Workbench did not become healthy within ${boot_timeout}s"

request 201 "${work_dir}/create-v1.json" \
  -X POST \
  -F 'title=Incident response runbook' \
  -F 'tenantId=tenant-acme' \
  -F 'visibility=internal' \
  -F "file=@${work_dir}/runbook-v1.txt;filename=incident-runbook.txt;type=text/plain" \
  "${base_url}/api/documents/sources"
source_id="$(json_value "${work_dir}/create-v1.json" id)"
[[ -n "${source_id}" ]] || fail "Create response did not contain a source id"
echo "  + created trusted source ${source_id}"

request 200 "${work_dir}/preview-v1.json" \
  "${base_url}/api/documents/sources/${source_id}/preview"
assert_json "${work_dir}/preview-v1.json" \
  'p["source"]["status"] == "PENDING" and p["source"]["activeChunks"] == 0 and p["chunkCount"] >= 1 and len(p["previewChunks"]) >= 1 and all(len(c["contentPreview"]) <= 400 for c in p["previewChunks"]) and all(c["safeMetadata"].get("_aiDocumentTenantId") == "tenant-acme" for c in p["previewChunks"])' \
  'Preview was not bounded, side-effect free, or tenant-scoped'
query 'Version one incident policy' 'tenant-acme' "${work_dir}/query-before-index.json"
assert_json "${work_dir}/query-before-index.json" \
  'p["resultCount"] == 0 and p["evidence"] == []' \
  'Preview unexpectedly made evidence retrievable'
echo "  + preview is bounded and performs no index activation"

request 200 "${work_dir}/index-v1.json" \
  -X POST \
  "${base_url}/api/documents/sources/${source_id}/index"
assert_json "${work_dir}/index-v1.json" \
  'p["source"]["status"] == "INDEXING" and p["queuedChunks"] > 0 and len(p["workIds"]) == p["queuedChunks"] and len(p["entityIds"]) == p["queuedChunks"]' \
  'Initial submission did not expose durable work and exact entity identities'
wait_for_source "${source_id}" INDEXED 1 "${work_dir}/status-v1.json"

query "$(<"${work_dir}/runbook-v1.txt")" \
  'tenant-acme' "${work_dir}/query-v1.json"
assert_json "${work_dir}/query-v1.json" \
  'p["resultCount"] >= 1 and all(e["sourceId"] and e["sourceVersion"] == 1 and e["chunkId"] and e["entityId"] for e in p["evidence"])' \
  'Version-one retrieval did not contain source/version/chunk evidence'
query 'Version one incident policy' 'tenant-other' \
  "${work_dir}/query-cross-tenant.json"
assert_json "${work_dir}/query-cross-tenant.json" \
  'p["resultCount"] == 0 and p["evidence"] == []' \
  'Cross-tenant retrieval returned document evidence'
echo "  + version 1 is active, attributable, and tenant-scoped"

request 400 "${work_dir}/failed-replacement.json" \
  -X PUT \
  -F 'title=Unsafe replacement' \
  -F 'tenantId=tenant-acme' \
  -F 'visibility=internal' \
  -F "file=@${work_dir}/unsafe.exe;filename=unsafe.exe;type=application/octet-stream" \
  "${base_url}/api/documents/sources/${source_id}/content"
assert_json "${work_dir}/failed-replacement.json" \
  'p["code"] == "INVALID_DOCUMENT_REQUEST"' \
  'Unsupported replacement did not fail with a stable safe response'
wait_for_source "${source_id}" INDEXED 1 "${work_dir}/status-after-failure.json"
query "$(<"${work_dir}/runbook-v1.txt")" \
  'tenant-acme' "${work_dir}/query-after-failure.json"
assert_json "${work_dir}/query-after-failure.json" \
  'p["resultCount"] >= 1 and all(e["sourceVersion"] == 1 for e in p["evidence"])' \
  'A rejected replacement disturbed the active version-one evidence'
echo "  + rejected replacement leaves version 1 active"

request 200 "${work_dir}/replace-v2.json" \
  -X PUT \
  -F 'title=Incident response runbook v2' \
  -F 'tenantId=tenant-acme' \
  -F 'visibility=internal' \
  -F "file=@${work_dir}/runbook-v2.txt;filename=incident-runbook.txt;type=text/plain" \
  "${base_url}/api/documents/sources/${source_id}/content"
assert_json "${work_dir}/replace-v2.json" \
  'p["sourceVersion"] == 2 and p["activeVersion"] == 1 and p["status"] == "PENDING"' \
  'Replacement did not preserve active version 1 while preparing version 2'
request 200 "${work_dir}/index-v2.json" \
  -X POST \
  "${base_url}/api/documents/sources/${source_id}/index"
assert_json "${work_dir}/index-v2.json" \
  'p["source"]["status"] == "REPLACING" and p["source"]["activeVersion"] == 1 and p["activeChunksBeforeSubmission"] > 0' \
  'Replacement submission did not report the old active version'
wait_for_source "${source_id}" INDEXED 2 "${work_dir}/status-v2.json"
wait_for_manifest "${source_id}" 1 DELETED "${work_dir}/status-retired-v1.json"
query "$(<"${work_dir}/runbook-v2.txt")" \
  'tenant-acme' "${work_dir}/query-v2.json"
assert_json "${work_dir}/query-v2.json" \
  'p["resultCount"] >= 1 and all(e["sourceVersion"] == 2 for e in p["evidence"]) and any("two hours" in e["content"] for e in p["evidence"])' \
  'Version-two retrieval did not replace version-one evidence'
echo "  + version 2 activated before exact retirement of version 1"

request 200 "${work_dir}/delete.json" \
  -X DELETE \
  "${base_url}/api/documents/sources/${source_id}"
assert_json "${work_dir}/delete.json" \
  'p["source"]["status"] == "DELETING" and p["queuedDeletes"] > 0 and len(p["entityIds"]) == p["queuedDeletes"]' \
  'Delete did not expose exact asynchronous delete work'
wait_for_source "${source_id}" DELETED null "${work_dir}/status-deleted.json"
query "$(<"${work_dir}/runbook-v2.txt")" \
  'tenant-acme' "${work_dir}/query-deleted.json"
assert_json "${work_dir}/query-deleted.json" \
  'p["resultCount"] == 0 and p["evidence"] == []' \
  'Deleted document evidence remains retrievable'
echo "  + exact delete completed and removed retrieval evidence"

request 201 "${work_dir}/create-json.json" \
  -X POST \
  -F 'title=Refund policy' \
  -F 'tenantId=tenant-json' \
  -F 'visibility=internal' \
  -F "file=@${work_dir}/refund-policy.json;filename=refund-policy.json;type=application/json" \
  "${base_url}/api/documents/sources"
json_source_id="$(json_value "${work_dir}/create-json.json" id)"
request 200 "${work_dir}/preview-json.json" \
  "${base_url}/api/documents/sources/${json_source_id}/preview"
assert_json "${work_dir}/preview-json.json" \
  'p["documentCount"] >= 1 and p["chunkCount"] >= 1 and any("JSON refund policy" in c["contentPreview"] for c in p["previewChunks"])' \
  'JSON source did not produce the expected bounded plan preview'
request 200 "${work_dir}/index-json.json" \
  -X POST \
  "${base_url}/api/documents/sources/${json_source_id}/index"
wait_for_source "${json_source_id}" INDEXED 1 "${work_dir}/status-json.json"
assert_json "${work_dir}/status-json.json" \
  'p["source"]["tenantId"] == "tenant-json" and p["source"]["activeChunks"] >= 1 and any(run["sourceVersion"] == 1 and run["state"] == "ACTIVE" for run in p["manifests"])' \
  'JSON source reached INDEXED without an active tenant-scoped manifest'
query 'content: JSON refund policy: approved returns are accepted within thirty days.' \
  'tenant-json' "${work_dir}/query-json.json"
assert_json "${work_dir}/query-json.json" \
  'p["resultCount"] >= 1 and all(e["sourceId"] == p["evidence"][0]["sourceId"] and e["sourceVersion"] == 1 for e in p["evidence"]) and any("thirty days" in e["content"] for e in p["evidence"])' \
  'JSON source was not retrievable with source/version evidence'
request 200 "${work_dir}/delete-json.json" \
  -X DELETE \
  "${base_url}/api/documents/sources/${json_source_id}"
wait_for_source "${json_source_id}" DELETED null "${work_dir}/status-json-deleted.json"
query 'content: JSON refund policy: approved returns are accepted within thirty days.' \
  'tenant-json' "${work_dir}/query-json-deleted.json"
assert_json "${work_dir}/query-json-deleted.json" \
  'p["resultCount"] == 0 and p["evidence"] == []' \
  'Deleted JSON document evidence remains retrievable'
echo "  + JSON reader follows the same preview, index, evidence, and exact-delete lifecycle"

request 400 "${work_dir}/unsupported.json" \
  -X POST \
  -F 'tenantId=tenant-acme' \
  -F "file=@${work_dir}/unsafe.exe;filename=unsafe.exe;type=application/octet-stream" \
  "${base_url}/api/documents/sources"
assert_json "${work_dir}/unsupported.json" \
  'p["code"] == "INVALID_DOCUMENT_REQUEST"' \
  'Unsupported source type did not fail closed'
request 422 "${work_dir}/oversized.json" \
  -X POST \
  -F 'tenantId=tenant-acme' \
  -F "file=@${work_dir}/oversized.txt;filename=oversized.txt;type=text/plain" \
  "${base_url}/api/documents/sources"
assert_json "${work_dir}/oversized.json" \
  'p["code"] == "DOCUMENT_LIMIT_EXCEEDED"' \
  'Oversized source did not return the typed document limit failure'
echo "  + unsupported and oversized inputs fail closed"

echo "Document ingestion packaged smoke passed"
