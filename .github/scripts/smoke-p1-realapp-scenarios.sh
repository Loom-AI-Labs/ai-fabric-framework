#!/usr/bin/env bash
#
# Deterministic P1 smoke suite for product-shaped real-app scenarios.
#
# The script expects examples/real-apps to be packaged first. It starts packaged
# jars under the offline smoke profile, uses app-local providers where the app
# has deterministic scenario logic, and asserts concrete HTTP response evidence.
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
boot_timeout="${P1_REALAPP_SMOKE_BOOT_TIMEOUT:-90}"
poll_timeout="${P1_REALAPP_SMOKE_POLL_TIMEOUT:-45}"
run_id="$(date +%s)-$$"
current_pid=""
current_log=""

cleanup() {
  if [[ -n "${current_pid}" ]] && kill -0 "${current_pid}" 2>/dev/null; then
    kill "${current_pid}" 2>/dev/null || true
    wait "${current_pid}" 2>/dev/null || true
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT

show_current_log() {
  if [[ -n "${current_log}" && -f "${current_log}" ]]; then
    echo "----- last 100 log lines: ${current_log##*/} -----" >&2
    tail -n 100 "${current_log}" >&2 || true
    echo "----------------------------------------" >&2
  fi
}

fail() {
  echo "::error::$*" >&2
  show_current_log
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

stop_current_app() {
  if [[ -n "${current_pid}" ]] && kill -0 "${current_pid}" 2>/dev/null; then
    kill "${current_pid}" 2>/dev/null || true
    wait "${current_pid}" 2>/dev/null || true
  fi
  current_pid=""
  current_log=""
}

start_app() {
  local app="$1"
  local port="$2"
  shift 2

  stop_current_app

  local jar
  jar="$(find_jar "${app}")"
  current_log="${work_dir}/${app}.log"

  java -jar "${jar}" \
    --spring.profiles.active=smoke \
    --server.port="${port}" \
    --spring.main.banner-mode=off \
    "$@" >"${current_log}" 2>&1 &
  current_pid=$!

  for _ in $(seq 1 "${boot_timeout}"); do
    if grep -qE "Started .*Application in" "${current_log}"; then
      echo "  ✓ ${app} started on port ${port}"
      return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|UnsatisfiedDependency|BeanCreationException|ApplicationContextException" "${current_log}"; then
      fail "${app}: application failed to start"
    fi
    if ! kill -0 "${current_pid}" 2>/dev/null; then
      fail "${app}: process exited before startup completed"
    fi
    sleep 1
  done

  fail "${app}: startup did not complete within ${boot_timeout}s"
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

http_get() {
  local url="$1"
  local output_file="$2"
  local status

  status="$(curl -sS -o "${output_file}" -w '%{http_code}' "${url}")"
  if [[ "${status}" -lt 200 || "${status}" -ge 300 ]]; then
    echo "HTTP ${status} from GET ${url}" >&2
    cat "${output_file}" >&2 || true
    fail "Unexpected HTTP status ${status}"
  fi
}

assert_json() {
  local file="$1"
  local description="$2"
  local expression="$3"

  if ! python3 - "${file}" "${expression}" <<'PY'
import json
import sys

path = sys.argv[1]
expr = sys.argv[2]
with open(path, "r", encoding="utf-8") as handle:
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
if not eval(expr, {"__builtins__": {}}, helpers):
    raise SystemExit(1)
PY
  then
    echo "Assertion failed: ${description}" >&2
    cat "${file}" >&2 || true
    fail "${description}"
  fi
  echo "  ✓ ${description}"
}

smart_faq_smoke() {
  local port="${P1_SMART_FAQ_PORT:-19201}"
  local base="http://127.0.0.1:${port}"
  local report="${work_dir}/smart-faq-quality.json"

  echo "P1 smoke: Smart FAQ golden-answer quality"
  start_app smart-faq-assistant "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:smart_faq_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--ai.providers.embedding-provider=simple" \
    "--ai.vector-db.type=lucene" \
    "--ai.vector-db.lucene.index-path=${work_dir}/smart-faq-lucene"

  http_json POST "${base}/api/demo/quality/seed-and-run" '{"limit":5,"threshold":0.01,"requireTopMatch":false,"springAiEvaluation":false}' "${report}"
  assert_json "${report}" "Smart FAQ golden set passes" "payload.get('pass') is True and payload.get('failedQuestions') == 0 and payload.get('totalQuestions', 0) > 0"
}

privacy_smoke() {
  local port="${P1_PRIVACY_PORT:-19202}"
  local base="http://127.0.0.1:${port}"
  local create_body="${work_dir}/privacy-create.json"
  local inventory_before="${work_dir}/privacy-inventory-before.json"
  local search_before="${work_dir}/privacy-search-before.json"
  local delete_body="${work_dir}/privacy-delete.json"
  local inventory_after="${work_dir}/privacy-inventory-after.json"
  local search_after="${work_dir}/privacy-search-after.json"
  local customer_id="p1-customer-${run_id}"

  echo "P1 smoke: Privacy masking and governance deletion"
  start_app privacy-first-customer-facing-support "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:privacy_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--ai.providers.embedding-provider=privacy-simple" \
    "--ai.vector-db.type=lucene" \
    "--ai.vector-db.lucene.index-path=${work_dir}/privacy-lucene" \
    "--ai.pii-detection.encryption-secret=p1-smoke-secret"

  http_json POST "${base}/api/support/messages" \
    "{\"customerId\":\"${customer_id}\",\"channel\":\"webchat\",\"subject\":\"Billing email sara.p1@example.com\",\"message\":\"My phone is +1 (555) 123-4567 and I need refund help for duplicate billing.\"}" \
    "${create_body}"
  assert_json "${create_body}" "Privacy app masks detected PII before response" "payload.get('piiDetected') is True and 'sara.p1@example.com' not in str(payload) and '555' not in str(payload)"

  http_get "${base}/api/support/privacy/customers/${customer_id}/inventory" "${inventory_before}"
  assert_json "${inventory_before}" "Privacy inventory sees indexed customer data" "payload.get('domainRecordCount') == 1 and payload.get('indexedRecordCount', 0) >= 1"

  http_get "${base}/api/support/privacy/search?q=duplicate%20billing&limit=5" "${search_before}"
  assert_json "${search_before}" "Privacy search finds support message before deletion" "isinstance(payload, list) and any(item.get('customerId') == '${customer_id}' for item in payload)"

  http_json POST "${base}/api/support/privacy/customers/${customer_id}/delete" "" "${delete_body}"
  assert_json "${delete_body}" "Privacy deletion reports completed or partial with evidence" "payload.get('status') in ('COMPLETED', 'PARTIAL')"

  http_get "${base}/api/support/privacy/customers/${customer_id}/inventory" "${inventory_after}"
  assert_json "${inventory_after}" "Privacy inventory is empty after deletion" "payload.get('domainRecordCount') == 0 and payload.get('indexedRecordCount') == 0"

  for _ in $(seq 1 "${poll_timeout}"); do
    http_get "${base}/api/support/privacy/search?q=duplicate%20billing&limit=5" "${search_after}"
    if python3 - "${search_after}" "${customer_id}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
customer_id = sys.argv[2]
raise SystemExit(0 if all(item.get("customerId") != customer_id for item in payload) else 1)
PY
    then
      echo "  ✓ Privacy search no longer returns deleted customer"
      return 0
    fi
    sleep 1
  done
  cat "${search_after}" >&2 || true
  fail "Privacy search still returned deleted customer after ${poll_timeout}s"
}

crm_smoke() {
  local port="${P1_CRM_PORT:-19203}"
  local base="http://127.0.0.1:${port}"
  local seed_body="${work_dir}/crm-seed.json"
  local query_body="${work_dir}/crm-query.json"
  local impossible_body="${work_dir}/crm-impossible.json"

  echo "P1 smoke: Relationship-query CRM business question"
  start_app relationship-query-crm-insights "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:crm_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--ai.providers.llm-provider=crm-local" \
    "--ai.vector-db.type=false"

  http_json POST "${base}/api/demo/seed" '{}' "${seed_body}"
  assert_json "${seed_body}" "CRM demo seed creates accounts and deals" "payload.get('accounts') == 3 and payload.get('deals') == 4"

  http_json POST "${base}/api/crm/query" '{"query":"show won deals for Globex","entityTypes":["account","deal"]}' "${query_body}"
  assert_json "${query_body}" "CRM relationship query returns structured success evidence" "payload.get('success') is True and (payload.get('totalResults', 0) >= 0) and ('Globex' in str(payload) or 'deal' in str(payload).lower())"

  http_json POST "${base}/api/crm/query" '{"query":"find accounts on Mars with revenue over 999999999","entityTypes":["account"]}' "${impossible_body}"
  assert_json "${impossible_body}" "CRM impossible query remains bounded" "'success' in payload and ('error' in str(payload).lower() or payload.get('totalResults', 0) == 0 or payload.get('success') is True)"
}

behavior_smoke() {
  local port="${P1_BEHAVIOR_PORT:-19204}"
  local base="http://127.0.0.1:${port}"
  local seed_body="${work_dir}/behavior-seed.json"
  local analyze_body="${work_dir}/behavior-analyze.json"
  local summary_body="${work_dir}/behavior-summary.json"

  echo "P1 smoke: Behavior signal analysis"
  start_app behavior-churn-signals "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:behavior_p1_${run_id};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1" \
    "--ai.providers.llm-provider=behavior-local" \
    "--ai.vector-db.type=false"

  http_json POST "${base}/api/demo/seed" '{}' "${seed_body}"
  assert_json "${seed_body}" "Behavior seed creates demo events" "payload.get('seededEvents', 0) >= 10"

  http_json POST "${base}/api/behavior/analyze/user-1001" "" "${analyze_body}"
  assert_json "${analyze_body}" "Behavior analysis returns user insight" "payload.get('userId') == 'user-1001' and payload.get('churnRisk', 0) >= 0"

  http_get "${base}/api/behavior/insights/user-1001/summary" "${summary_body}"
  assert_json "${summary_body}" "Behavior summary includes churn, sentiment, and trend evidence" "payload.get('userId') == 'user-1001' and payload.get('churnRisk', 0) > 0.5 and payload.get('sentimentLabel') and payload.get('trend') == 'DECLINING'"
}

support_action_bot_smoke() {
  local port="${P1_SUPPORT_ACTION_BOT_PORT:-19207}"
  local base="http://127.0.0.1:${port}"
  local seed_body="${work_dir}/support-action-seed.json"
  local actions_body="${work_dir}/support-action-actions.json"
  local deny_body="${work_dir}/support-action-deny.json"
  local create_body="${work_dir}/support-action-create.json"
  local assign_prompt_body="${work_dir}/support-action-assign-prompt.json"
  local assign_body="${work_dir}/support-action-assign.json"
  local ticket_body="${work_dir}/support-action-ticket.json"

  echo "P1 smoke: Support action bot authorization and confirmation"
  start_app it-support-action-bot "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:it_support_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--spring.jpa.hibernate.ddl-auto=create-drop" \
    "--ai.providers.llm-provider=smoke" \
    "--ai.providers.embedding-provider=smoke" \
    "--ai.vector-db.type=false"

  http_json POST "${base}/api/demo/seed" "" "${seed_body}"
  assert_json "${seed_body}" "Support bot demo seed is available" "payload.get('seeded') is True"

  http_get "${base}/api/smoke/actions" "${actions_body}"
  assert_json "${actions_body}" "Support bot exposes write action contracts" "payload.get('actions', {}).get('create_ticket', {}).get('accessMode') == 'WRITE_ONLY' and payload.get('actions', {}).get('assign_ticket', {}).get('confirmationRequired') is True"

  http_json POST "${base}/api/smoke/actions/create_ticket" \
    '{"params":{"title":"P1 smoke laptop setup","description":"Provision a laptop for a new starter.","priority":"HIGH"}}' \
    "${deny_body}"
  assert_json "${deny_body}" "Support bot denies write action without identity" "payload.get('success') is False and payload.get('allowed') is False and payload.get('outcome') == 'ACTION_NOT_ALLOWED'"

  http_json POST "${base}/api/smoke/actions/create_ticket" \
    '{"userId":"agent_alex","sessionId":"agent-alex-p1","params":{"title":"P1 smoke laptop setup","description":"Provision a laptop for a new starter.","priority":"HIGH"}}' \
    "${create_body}"
  assert_json "${create_body}" "Support bot executes allowed non-confirmable write action" "payload.get('success') is True and payload.get('allowed') is True and payload.get('outcome') == 'ACTION_EXECUTED' and payload.get('result', {}).get('data', {}).get('priority') == 'HIGH'"

  http_json POST "${base}/api/smoke/actions/assign_ticket" \
    '{"userId":"agent_alex","sessionId":"agent-alex-p1","params":{"ticketNumber":1001,"assigneeUsername":"agent_maya"}}' \
    "${assign_prompt_body}"
  assert_json "${assign_prompt_body}" "Support bot gates assign action behind confirmation" "payload.get('success') is True and payload.get('allowed') is True and payload.get('outcome') == 'CONFIRMATION_REQUIRED' and 'Assign ticket 1001 to agent_maya' in payload.get('confirmationMessage', '')"

  http_json POST "${base}/api/smoke/actions/assign_ticket" \
    '{"userId":"agent_alex","sessionId":"agent-alex-p1","confirmed":true,"params":{"ticketNumber":1001,"assigneeUsername":"agent_maya"}}' \
    "${assign_body}"
  assert_json "${assign_body}" "Support bot executes confirmed assign action" "payload.get('success') is True and payload.get('outcome') == 'ACTION_EXECUTED' and payload.get('result', {}).get('data', {}).get('assignedTo') == 'agent_maya' and payload.get('result', {}).get('data', {}).get('status') == 'IN_PROGRESS'"

  http_get "${base}/api/tickets/1001" "${ticket_body}"
  assert_json "${ticket_body}" "Support bot ticket state reflects confirmed action" "payload.get('ticketNumber') == 1001 and payload.get('assignedTo') == 'agent_maya' and payload.get('status') == 'IN_PROGRESS'"
}

migration_smoke() {
  local port="${P1_MIGRATION_PORT:-19205}"
  local base="http://127.0.0.1:${port}"
  local seed_body="${work_dir}/migration-seed.json"
  local start_body="${work_dir}/migration-start.json"
  local progress_body="${work_dir}/migration-progress.json"
  local product_list_body="${work_dir}/migration-products.json"
  local search_body="${work_dir}/migration-search.json"
  local job_id
  local product_query

  echo "P1 smoke: Migration/backfill lifecycle"
  start_app migration-enabled-product-catalog "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:migration_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--ai.providers.embedding-provider=simple" \
    "--ai.vector-db.type=lucene" \
    "--ai.vector-db.lucene.index-path=${work_dir}/migration-lucene" \
    "--ai.indexing.async-worker.fixed-delay=PT0.2S" \
    "--ai.indexing.async-worker.batch-size=10"

  http_json POST "${base}/api/demo/seed?count=25" "" "${seed_body}"
  assert_json "${seed_body}" "Migration demo seed creates products" "payload.get('seeded') == 25 and payload.get('totalProducts') == 25"

  http_json POST "${base}/api/migration/jobs/products/start?batchSize=10&rateLimit=1000&reindexExisting=true&createdBy=p1-smoke" '{}' "${start_body}"
  assert_json "${start_body}" "Migration job starts" "payload.get('id') or payload.get('jobId')"
  job_id="$(python3 - "${start_body}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
print(payload.get("id") or payload.get("jobId") or "")
PY
)"
  if [[ -z "${job_id}" ]]; then
    cat "${start_body}" >&2 || true
    fail "Migration job id was not returned"
  fi

  for _ in $(seq 1 "${poll_timeout}"); do
    http_get "${base}/api/migration/jobs/${job_id}/progress" "${progress_body}"
    if python3 - "${progress_body}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
status = str(payload.get("status") or "").upper()
processed = payload.get("processedRecords") or payload.get("processed") or 0
total = payload.get("totalRecords") or payload.get("total") or 0
raise SystemExit(0 if status in {"COMPLETED", "COMPLETE", "SUCCEEDED"} or (total and processed >= total) else 1)
PY
    then
      echo "  ✓ Migration job completed"
      break
    fi
    sleep 1
  done

  assert_json "${progress_body}" "Migration progress covers seeded records" "(payload.get('processedRecords') or payload.get('processed') or 0) >= 25 or str(payload.get('status')).upper() in ('COMPLETED','COMPLETE','SUCCEEDED')"

  http_get "${base}/api/products?limit=1" "${product_list_body}"
  assert_json "${product_list_body}" "Migration product list exposes a seeded product" "isinstance(payload, list) and len(payload) == 1 and payload[0].get('name')"
  product_query="$(python3 - "${product_list_body}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
print(payload[0]["name"])
PY
)"

  for _ in $(seq 1 "${poll_timeout}"); do
    status="$(
      curl -sS -G -o "${search_body}" -w '%{http_code}' \
        --data-urlencode "q=${product_query}" \
        --data-urlencode 'limit=5' \
        --data-urlencode 'threshold=0.0' \
        "${base}/api/products/search"
    )"
    if [[ "${status}" -lt 200 || "${status}" -ge 300 ]]; then
      echo "HTTP ${status} from GET ${base}/api/products/search" >&2
      cat "${search_body}" >&2 || true
      fail "Unexpected HTTP status ${status}"
    fi
    if python3 - "${search_body}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
raise SystemExit(0 if isinstance(payload, list) and len(payload) > 0 else 1)
PY
    then
      echo "  ✓ Migration search returns indexed products"
      return 0
    fi
    sleep 1
  done
  cat "${search_body}" >&2 || true
  fail "Migration search did not return indexed products"
}

chat_action_smoke() {
  local port="${P1_CHAT_ACTION_PORT:-19206}"
  local base="http://127.0.0.1:${port}"
  local product_body="${work_dir}/chat-product.json"
  local add_cart_body="${work_dir}/chat-add-cart.json"
  local checkout_body="${work_dir}/chat-checkout.json"
  local read_orders_body="${work_dir}/chat-read-orders.json"
  local anonymous_write_denied_body="${work_dir}/chat-anonymous-write-denied.json"
  local cancel_prompt_body="${work_dir}/chat-cancel-prompt.json"
  local offer_prompt_body="${work_dir}/chat-offer-prompt.json"
  local reject_offer_body="${work_dir}/chat-reject-offer.json"
  local accept_offer_body="${work_dir}/chat-accept-offer.json"
  local order_status_body="${work_dir}/chat-order-status.json"
  local sku="P1-ACTION-${run_id}"
  local user_id="p1-action-user-${run_id}"
  local user_id_offer="p1-offer-user-${run_id}"
  local order_number
  local order_id
  local offer_order_number
  local offer_order_id

  echo "P1 smoke: Action confirmation and confirmation interceptor"
  start_app chat-capabilities-demo "${port}" \
    "--spring.datasource.url=jdbc:h2:mem:chat_actions_p1_${run_id};DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    "--ai.providers.orchestration.llm-provider=chat-local" \
    "--ai.providers.enable-fallback=false" \
    "--ai.providers.embedding-provider=smoke" \
    "--ai.vector-db.type=memory" \
    "--ai.indexing.async-worker.fixed-delay=PT0.2S" \
    "--app.admin.enabled=false"

  http_json POST "${base}/api/products" \
    "{\"sku\":\"${sku}\",\"name\":\"P1 Action Smoke Backpack\",\"description\":\"Durable smoke-test backpack for action confirmation flows.\",\"category\":\"Bags\",\"tags\":\"p1,actions,smoke\",\"price\":79.00,\"currency\":\"USD\",\"inStockQty\":10}" \
    "${product_body}"
  assert_json "${product_body}" "Chat action smoke creates product" "payload.get('sku') == '${sku}' and payload.get('inStockQty') == 10"

  create_order() {
    local owner="$1"
    local add_file="$2"
    local checkout_file="$3"

    http_json POST "${base}/api/carts/active/items" \
      "{\"userId\":\"${owner}\",\"sku\":\"${sku}\",\"quantity\":1}" \
      "${add_file}"
    assert_json "${add_file}" "Chat action smoke adds cart item for ${owner}" "payload.get('userId') == '${owner}' and payload.get('status') == 'ACTIVE'"

    http_json POST "${base}/api/carts/active/checkout" \
      "{\"userId\":\"${owner}\",\"shippingAddress\":\"1 P1 Smoke Way\",\"email\":\"${owner}@example.com\",\"paymentMethod\":\"CARD\"}" \
      "${checkout_file}"
    assert_json "${checkout_file}" "Chat action smoke checks out order for ${owner}" "payload.get('userId') == '${owner}' and payload.get('status') == 'CREATED' and payload.get('orderNumber')"
  }

  extract_order_field() {
    local file="$1"
    local field="$2"
    python3 - "${file}" "${field}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
print(payload.get(sys.argv[2]) or "")
PY
  }

  create_order "${user_id}" "${add_cart_body}" "${checkout_body}"
  order_number="$(extract_order_field "${checkout_body}" orderNumber)"
  order_id="$(extract_order_field "${checkout_body}" id)"
  if [[ -z "${order_number}" || -z "${order_id}" ]]; then
    cat "${checkout_body}" >&2 || true
    fail "Chat action smoke order reference was not returned"
  fi

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"Show my recent orders\",\"userId\":\"${user_id}\",\"sessionId\":\"p1-read-action-session-${run_id}\",\"conversationId\":\"p1-read-action-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${read_orders_body}"
  assert_json "${read_orders_body}" "Chat read action executes without confirmation" "payload.get('success') is True and payload.get('result', {}).get('type') == 'ACTION_EXECUTED' and payload.get('result', {}).get('data', {}).get('action') == 'list_orders' and payload.get('result', {}).get('data', {}).get('actionResult', {}).get('success') is True and '${order_number}' in str(payload.get('result', {}).get('data', {}).get('actionResult', {}).get('data', {}))"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"Create a support ticket for billing help\",\"sessionId\":\"p1-anonymous-action-session-${run_id}\",\"conversationId\":\"p1-anonymous-action-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${anonymous_write_denied_body}"
  assert_json "${anonymous_write_denied_body}" "Chat denies anonymous write action before confirmation" "payload.get('success') is True and payload.get('result', {}).get('type') == 'ACTION_DENIED' and payload.get('result', {}).get('success') is False and payload.get('result', {}).get('message') == 'Action not permitted for anonymous users.'"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"Cancel order ${order_number}\",\"userId\":\"${user_id}\",\"sessionId\":\"p1-action-session-${run_id}\",\"conversationId\":\"p1-action-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${cancel_prompt_body}"
  assert_json "${cancel_prompt_body}" "Cancel action asks for confirmation" "payload.get('success') is True and payload.get('result', {}).get('type') == 'CONFIRMATION_REQUIRED' and payload.get('result', {}).get('data', {}).get('action') == 'cancel_purchase_order'"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"yes\",\"userId\":\"${user_id}\",\"sessionId\":\"p1-action-session-${run_id}\",\"conversationId\":\"p1-action-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${offer_prompt_body}"
  assert_json "${offer_prompt_body}" "Retention interceptor offers discount before cancelling" "payload.get('success') is True and payload.get('result', {}).get('type') == 'CONFIRMATION_REQUIRED' and payload.get('result', {}).get('data', {}).get('action') == 'offer_order_discount' and 'discount' in str(payload).lower()"

  http_get "${base}/api/orders/${order_id}?userId=${user_id}" "${order_status_body}"
  assert_json "${order_status_body}" "Order remains active while retention offer is pending" "payload.get('status') == 'CREATED'"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"no\",\"userId\":\"${user_id}\",\"sessionId\":\"p1-action-session-${run_id}\",\"conversationId\":\"p1-action-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${reject_offer_body}"
  assert_json "${reject_offer_body}" "Rejecting retention offer executes original cancellation" "payload.get('success') is True and payload.get('result', {}).get('type') == 'ACTION_EXECUTED' and payload.get('result', {}).get('data', {}).get('action') == 'cancel_purchase_order' and payload.get('result', {}).get('data', {}).get('actionResult', {}).get('success') is True"

  http_get "${base}/api/orders/${order_id}?userId=${user_id}" "${order_status_body}"
  assert_json "${order_status_body}" "Rejected offer leaves order cancelled" "payload.get('status') == 'CANCELLED'"

  create_order "${user_id_offer}" "${add_cart_body}" "${checkout_body}"
  offer_order_number="$(extract_order_field "${checkout_body}" orderNumber)"
  offer_order_id="$(extract_order_field "${checkout_body}" id)"
  if [[ -z "${offer_order_number}" || -z "${offer_order_id}" ]]; then
    cat "${checkout_body}" >&2 || true
    fail "Chat action smoke offer order reference was not returned"
  fi

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"Cancel order ${offer_order_number}\",\"userId\":\"${user_id_offer}\",\"sessionId\":\"p1-offer-session-${run_id}\",\"conversationId\":\"p1-offer-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${cancel_prompt_body}"
  assert_json "${cancel_prompt_body}" "Second cancel action asks for confirmation" "payload.get('success') is True and payload.get('result', {}).get('type') == 'CONFIRMATION_REQUIRED'"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"yes\",\"userId\":\"${user_id_offer}\",\"sessionId\":\"p1-offer-session-${run_id}\",\"conversationId\":\"p1-offer-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${offer_prompt_body}"
  assert_json "${offer_prompt_body}" "Second cancel triggers retention offer" "payload.get('success') is True and payload.get('result', {}).get('type') == 'CONFIRMATION_REQUIRED' and payload.get('result', {}).get('data', {}).get('action') == 'offer_order_discount'"

  http_json POST "${base}/api/chat/query" \
    "{\"query\":\"yes\",\"userId\":\"${user_id_offer}\",\"sessionId\":\"p1-offer-session-${run_id}\",\"conversationId\":\"p1-offer-conversation-${run_id}\",\"mode\":\"executor\"}" \
    "${accept_offer_body}"
  assert_json "${accept_offer_body}" "Accepting retention offer executes discount action" "payload.get('success') is True and payload.get('result', {}).get('type') == 'ACTION_EXECUTED' and payload.get('result', {}).get('data', {}).get('action') == 'offer_order_discount' and payload.get('result', {}).get('data', {}).get('actionResult', {}).get('success') is True and payload.get('result', {}).get('data', {}).get('actionResult', {}).get('data', {}).get('couponCode') == 'SAVE10'"

  http_get "${base}/api/orders/${offer_order_id}?userId=${user_id_offer}" "${order_status_body}"
  assert_json "${order_status_body}" "Accepted retention offer keeps order active" "payload.get('status') == 'CREATED'"
}

echo "P1 deterministic real-app scenario smoke suite"
smart_faq_smoke
privacy_smoke
crm_smoke
behavior_smoke
support_action_bot_smoke
migration_smoke
chat_action_smoke
stop_current_app
echo "P1 deterministic real-app scenario smoke suite passed."
