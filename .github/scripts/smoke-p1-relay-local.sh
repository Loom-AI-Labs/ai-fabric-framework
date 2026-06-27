#!/usr/bin/env bash
#
# Packaged local smoke for the AI Fabric Relay Customer Connector API.
#
# The script starts a tiny local internal service plus the packaged relay jar and
# proves API-key auth, action forwarding, idempotency replay/conflict, retrieval
# forwarding, and documents-only retrieval rejection.
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
relay_target="${repo_root}/ai-infrastructure-module/ai-fabric-relay/target"
work_dir="$(mktemp -d)"
relay_port="${P1_RELAY_SMOKE_PORT:-19311}"
stub_port="${P1_RELAY_STUB_PORT:-19310}"
boot_timeout="${P1_RELAY_SMOKE_BOOT_TIMEOUT:-90}"
api_key="${P1_RELAY_SMOKE_API_KEY:-p1-relay-key}"

stub_pid=""
relay_pid=""
stub_log="${work_dir}/internal-stub.log"
relay_log="${work_dir}/relay.log"

cleanup() {
  if [[ -n "${relay_pid}" ]] && kill -0 "${relay_pid}" 2>/dev/null; then
    kill "${relay_pid}" 2>/dev/null || true
    wait "${relay_pid}" 2>/dev/null || true
  fi
  if [[ -n "${stub_pid}" ]] && kill -0 "${stub_pid}" 2>/dev/null; then
    kill "${stub_pid}" 2>/dev/null || true
    wait "${stub_pid}" 2>/dev/null || true
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT

fail() {
  echo "::error::$*" >&2
  if [[ -f "${stub_log}" ]]; then
    echo "----- internal stub log -----" >&2
    tail -n 80 "${stub_log}" >&2 || true
  fi
  if [[ -f "${relay_log}" ]]; then
    echo "----- relay log -----" >&2
    tail -n 120 "${relay_log}" >&2 || true
  fi
  exit 1
}

find_relay_jar() {
  local jar
  jar="$(locate_relay_jar)"
  if [[ -n "${jar}" ]]; then
    printf '%s\n' "${jar}"
    return 0
  fi

  if [[ "${P1_RELAY_SMOKE_BUILD_IF_MISSING:-true}" == "true" ]]; then
    echo "  • relay boot jar not found; packaging ai-fabric-relay with tests" >&2
    if ! mvn -B -V --no-transfer-progress -f "${repo_root}/ai-infrastructure-module/pom.xml" \
        -pl ai-fabric-relay -am package >"${work_dir}/relay-package.log" 2>&1; then
      echo "----- relay package log -----" >&2
      tail -n 120 "${work_dir}/relay-package.log" >&2 || true
      fail "Relay package build failed"
    fi
    jar="$(locate_relay_jar)"
  fi

  if [[ -z "${jar}" ]]; then
    fail "No relay boot jar found under ${relay_target}; run 'mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-relay -am package' first."
  fi

  printf '%s\n' "${jar}"
}

locate_relay_jar() {
  local jar
  jar="$(
    find "${relay_target}" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*.original' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      | sort \
      | head -n 1
  )"
  printf '%s\n' "${jar}"
}

start_internal_stub() {
  python3 - "${stub_port}" >"${stub_log}" 2>&1 <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
action_calls = 0

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_POST(self):
        global action_calls
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8")
        try:
            payload = json.loads(raw or "{}")
        except Exception:
            self._json(400, {"success": False, "errorCode": "BAD_JSON", "message": "bad json"})
            return

        if self.path == "/actions/execute":
            action_calls += 1
            self._json(200, {
                "success": True,
                "message": "stub action ok",
                "data": {
                    "echo": payload.get("params", {}).get("input"),
                    "callCount": action_calls,
                    "requestId": payload.get("trace", {}).get("requestId")
                }
            })
            return

        if self.path == "/retrieval/search":
            query = str(payload.get("query", ""))
            if "generated" in query.lower():
                self._json(200, {
                    "success": True,
                    "answer": "This generated answer must be rejected by the relay.",
                    "documents": [
                        {"id": "doc-generated", "content": "unsafe shape", "score": 0.91}
                    ],
                    "count": 1
                })
                return
            self._json(200, {
                "success": True,
                "documents": [
                    {
                        "id": "doc-1",
                        "content": "Refunds are available within 30 days.",
                        "score": 0.93,
                        "source": "policy",
                        "vectorSpace": payload.get("vectorSpace"),
                        "metadata": {"locale": "en_US"}
                    }
                ],
                "count": 1,
                "totalCount": 1
            })
            return

        self._json(404, {"success": False, "errorCode": "NOT_FOUND", "message": self.path})

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
print(f"stub-ready:{port}", flush=True)
server.serve_forever()
PY
  stub_pid=$!

  for _ in $(seq 1 30); do
    if grep -q "stub-ready:${stub_port}" "${stub_log}"; then
      echo "  ✓ internal stub started on port ${stub_port}"
      return 0
    fi
    if ! kill -0 "${stub_pid}" 2>/dev/null; then
      fail "Internal stub exited before startup completed"
    fi
    sleep 1
  done
  fail "Internal stub did not start"
}

start_relay() {
  local jar
  jar="$(find_relay_jar)"

  java -jar "${jar}" \
    --server.port="${relay_port}" \
    --spring.main.banner-mode=off \
    --relay.auth.apiKey.enabled=true \
    --relay.auth.apiKey.value="${api_key}" \
    --relay.audit.enabled=false \
    --relay.routing.mode=DISPATCHER \
    --relay.routing.dispatcher.url="http://127.0.0.1:${stub_port}/actions/execute" \
    --relay.routing.retrieval.url="http://127.0.0.1:${stub_port}/retrieval/search" \
    --relay.rate-limits.per-user.max-requests=1000 \
    --relay.idempotency.in-progress-max-wait-ms=0 \
    >"${relay_log}" 2>&1 &
  relay_pid=$!

  for _ in $(seq 1 "${boot_timeout}"); do
    if grep -qE "Started .*Application in" "${relay_log}"; then
      echo "  ✓ relay started on port ${relay_port}"
      return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|UnsatisfiedDependency|BeanCreationException|ApplicationContextException" "${relay_log}"; then
      fail "Relay failed to start"
    fi
    if ! kill -0 "${relay_pid}" 2>/dev/null; then
      fail "Relay process exited before startup completed"
    fi
    sleep 1
  done
  fail "Relay startup did not complete within ${boot_timeout}s"
}

post_json() {
  local url="$1"
  local body="$2"
  local output="$3"
  local expected_status="${4:-200}"
  shift 4 || true
  local status
  status="$(
    curl -sS -o "${output}" -w '%{http_code}' \
      -X POST \
      -H 'Content-Type: application/json' \
      "$@" \
      --data "${body}" \
      "${url}"
  )"
  if [[ "${status}" != "${expected_status}" ]]; then
    echo "HTTP ${status} from POST ${url}, expected ${expected_status}" >&2
    cat "${output}" >&2 || true
    fail "Unexpected HTTP status"
  fi
}

assert_json() {
  local file="$1"
  local description="$2"
  local expression="$3"
  if ! python3 - "${file}" "${expression}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)

helpers = {
    "payload": payload,
    "len": len,
    "any": any,
    "all": all,
    "str": str,
    "isinstance": isinstance,
    "list": list,
    "dict": dict,
}
if not eval(sys.argv[2], {"__builtins__": {}}, helpers):
    raise SystemExit(1)
PY
  then
    echo "Assertion failed: ${description}" >&2
    cat "${file}" >&2 || true
    fail "${description}"
  fi
  echo "  ✓ ${description}"
}

trace_json() {
  local request_id="$1"
  cat <<JSON
{
  "requestId": "${request_id}",
  "conversationId": "p1-relay-conversation",
  "authContext": {
    "subjectId": "p1-relay-user",
    "subjectType": "END_USER",
    "authMode": "PUBLIC_RUNTIME_AUTHENTICATED",
    "callerType": "PUBLIC_BROWSER",
    "sessionId": "p1-relay-session",
    "deploymentId": "p1-relay-deployment",
    "customerId": "p1-customer",
    "tenantId": "p1-tenant",
    "issuer": "p1-smoke",
    "grantedScopes": ["actions:execute", "retrieval:search"],
    "audiences": ["relay-smoke"]
  }
}
JSON
}

main() {
  echo "P1 smoke: packaged relay local Customer Connector API"
  start_internal_stub
  start_relay

  local base="http://127.0.0.1:${relay_port}"
  local auth_header=(-H "X-AIFABRIC-API-KEY: ${api_key}")
  local trace
  trace="$(trace_json "p1-relay-action-1")"

  local action_body
  action_body="$(cat <<JSON
{
  "actionId": "ping",
  "params": {"input": "hello relay"},
  "idempotencyKey": "p1-relay-action-key",
  "trace": ${trace}
}
JSON
)"

  local out="${work_dir}/unauthorized-action.json"
  post_json "${base}/actions/execute" "${action_body}" "${out}" 401
  assert_json "${out}" "Relay rejects missing action API key" "payload.get('success') is False and payload.get('errorCode') == 'UNAUTHORIZED'"

  out="${work_dir}/action-first.json"
  post_json "${base}/actions/execute" "${action_body}" "${out}" 200 "${auth_header[@]}"
  assert_json "${out}" "Relay forwards action and returns internal result" "payload.get('success') is True and payload.get('data', {}).get('echo') == 'hello relay' and payload.get('data', {}).get('callCount') == 1"

  out="${work_dir}/action-replay.json"
  post_json "${base}/actions/execute" "${action_body}" "${out}" 200 "${auth_header[@]}"
  assert_json "${out}" "Relay replays cached idempotent action result" "payload.get('success') is True and payload.get('data', {}).get('callCount') == 1"

  local conflict_body
  conflict_body="$(cat <<JSON
{
  "actionId": "ping",
  "params": {"input": "changed"},
  "idempotencyKey": "p1-relay-action-key",
  "trace": ${trace}
}
JSON
)"
  out="${work_dir}/action-conflict.json"
  post_json "${base}/actions/execute" "${conflict_body}" "${out}" 200 "${auth_header[@]}"
  assert_json "${out}" "Relay detects idempotency conflict" "payload.get('success') is False and payload.get('errorCode') == 'IDEMPOTENCY_CONFLICT'"

  trace="$(trace_json "p1-relay-retrieval-1")"
  local retrieval_body
  retrieval_body="$(cat <<JSON
{
  "query": "refund policy",
  "vectorSpace": "policy",
  "topK": 3,
  "filters": {"locale": "en_US"},
  "trace": ${trace}
}
JSON
)"
  out="${work_dir}/retrieval-ok.json"
  post_json "${base}/retrieval/search" "${retrieval_body}" "${out}" 200 "${auth_header[@]}"
  assert_json "${out}" "Relay forwards documents-only retrieval" "payload.get('success') is True and len(payload.get('documents', [])) == 1 and payload.get('documents', [])[0].get('id') == 'doc-1'"

  trace="$(trace_json "p1-relay-retrieval-generated")"
  local unsafe_retrieval_body
  unsafe_retrieval_body="$(cat <<JSON
{
  "query": "generated answer please",
  "vectorSpace": "policy",
  "topK": 3,
  "trace": ${trace}
}
JSON
)"
  out="${work_dir}/retrieval-rejected.json"
  post_json "${base}/retrieval/search" "${unsafe_retrieval_body}" "${out}" 200 "${auth_header[@]}"
  assert_json "${out}" "Relay rejects generated retrieval responses" "payload.get('success') is False and payload.get('errorCode') == 'INVALID_RESPONSE' and 'documents-only' in payload.get('message', '')"

  echo "P1 packaged relay local smoke passed."
}

main "$@"
