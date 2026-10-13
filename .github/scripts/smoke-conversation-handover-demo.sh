#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app_dir="${repo_root}/examples/real-apps/conversation-handover-demo"
jar="$(find "${app_dir}/target" -maxdepth 1 -name 'conversation-handover-demo-*.jar' ! -name '*.original' | head -1)"
port="${HANDOVER_SMOKE_PORT:-19110}"
base_url="http://127.0.0.1:${port}"
cookie_jar="$(mktemp)"
log="$(mktemp)"

cleanup() {
  if [[ -n "${pid:-}" ]]; then
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  fi
  rm -f "${cookie_jar}" "${log}"
}
trap cleanup EXIT

if [[ -z "${jar}" ]]; then
  echo "Conversation handover demo jar is missing. Package the real-app suite first." >&2
  exit 1
fi

APP_DEMO_OPERATOR_ACCESS_CODE=smoke-operator \
java -jar "${jar}" \
  --server.port="${port}" \
  '--spring.datasource.url=jdbc:h2:mem:handover-smoke;DB_CLOSE_DELAY=-1' \
  --spring.main.banner-mode=off >"${log}" 2>&1 &
pid=$!

for _ in $(seq 1 60); do
  if curl -fsS "${base_url}/actuator/health/liveness" >/dev/null 2>&1; then break; fi
  if ! kill -0 "${pid}" 2>/dev/null; then
    tail -n 60 "${log}" >&2
    exit 1
  fi
  sleep 1
done
curl -fsS "${base_url}/actuator/health/liveness" >/dev/null

session="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' -d '{}' "${base_url}/api/demo/session")"
conversation_id="$(jq -r '.conversationId' <<<"${session}")"
version="$(jq -r '.conversation.control.version' <<<"${session}")"

ai_turn="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"text":"Hello","clientMessageId":"smoke-user-1"}' \
  "${base_url}/api/demo/conversations/${conversation_id}/messages")"
ai_count_before="$(jq '[.messages[] | select(.role == "AI")] | length' <<<"${ai_turn}")"
test "${ai_count_before}" -eq 1

pending="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":${version},\"idempotencyKey\":\"smoke-request-human\"}" \
  "${base_url}/api/demo/conversations/${conversation_id}/handover")"
test "$(jq -r '.control.state' <<<"${pending}")" = "HUMAN_PENDING"

pending_message="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"text":"Waiting for a person","clientMessageId":"smoke-user-2"}' \
  "${base_url}/api/demo/conversations/${conversation_id}/messages")"
test "$(jq '[.messages[] | select(.role == "AI")] | length' <<<"${pending_message}")" -eq "${ai_count_before}"

curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"accessCode":"smoke-operator"}' \
  "${base_url}/api/demo/operator/session" >/dev/null

version="$(jq -r '.control.version' <<<"${pending}")"
active="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":${version},\"idempotencyKey\":\"smoke-claim\"}" \
  "${base_url}/api/demo/operator/conversations/${conversation_id}/claim")"
test "$(jq -r '.control.state' <<<"${active}")" = "HUMAN_ACTIVE"

human="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"text":"A human reply","clientMessageId":"smoke-human-1"}' \
  "${base_url}/api/demo/operator/conversations/${conversation_id}/messages")"
test "$(jq '[.messages[] | select(.role == "HUMAN")] | length' <<<"${human}")" -eq 1

version="$(jq -r '.control.version' <<<"${active}")"
resumed="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":${version},\"idempotencyKey\":\"smoke-return\"}" \
  "${base_url}/api/demo/operator/conversations/${conversation_id}/return-to-ai")"
test "$(jq -r '.control.state' <<<"${resumed}")" = "AI_ACTIVE"

final_turn="$(curl -fsS -c "${cookie_jar}" -b "${cookie_jar}" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"text":"AI may answer again","clientMessageId":"smoke-user-3"}' \
  "${base_url}/api/demo/conversations/${conversation_id}/messages")"
test "$(jq '[.messages[] | select(.role == "AI")] | length' <<<"${final_turn}")" -eq 2

echo "Conversation handover smoke passed: AI_ACTIVE -> HUMAN_PENDING -> HUMAN_ACTIVE -> AI_ACTIVE"
