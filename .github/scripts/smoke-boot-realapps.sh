#!/usr/bin/env bash
#
# Boot every example app under examples/real-apps with the offline "smoke" Spring profile and assert
# that each one reaches the Spring "Started ...Application" marker. No API keys or external services
# are required: the smoke profile (provided by the smoke-support module) wires in no-op AI providers,
# a deterministic in-process embedding provider, an in-memory vector store and H2.
#
# Expects the real-apps suite to be packaged first (jars present under each module's target/), e.g.:
#   mvn -f examples/real-apps/pom.xml install
#
set -uo pipefail

apps_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../examples/real-apps" && pwd)"
apps=(
  behavior-churn-signals
  chat-capabilities-demo
  cloud-qdrant-openai-vector-search
  ecommerce-store
  it-support-action-bot
  migration-enabled-product-catalog
  privacy-first-customer-facing-support
  retrieval-connector-boundary-lab
  relationship-query-crm-insights
  smart-faq-assistant
  sub-management-hub-simple
)

boot_timeout="${SMOKE_BOOT_TIMEOUT:-90}"
port=19000
failures=0

echo "Smoke boot-test: ${#apps[@]} apps (profile=smoke, timeout=${boot_timeout}s each)"
for app in "${apps[@]}"; do
  port=$((port + 1))
  jar="$(ls "${apps_dir}/${app}"/target/*.jar 2>/dev/null | grep -v -- '-original' | head -1)"
  if [[ -z "${jar}" ]]; then
    echo "::error::${app}: no boot jar found (was the suite packaged?)"
    failures=$((failures + 1))
    continue
  fi

  log="$(mktemp)"
  java -jar "${jar}" \
    --spring.profiles.active=smoke \
    --server.port="${port}" \
    --spring.main.banner-mode=off >"${log}" 2>&1 &
  pid=$!

  result="TIMEOUT"
  for _ in $(seq 1 "${boot_timeout}"); do
    if grep -qE "Started .*Application in" "${log}"; then result="PASS"; break; fi
    if grep -qE "APPLICATION FAILED TO START|UnsatisfiedDependency|BeanCreationException|ApplicationContextException" "${log}"; then result="FAIL"; break; fi
    if ! kill -0 "${pid}" 2>/dev/null; then result="EXITED"; break; fi
    sleep 1
  done

  kill "${pid}" 2>/dev/null
  wait "${pid}" 2>/dev/null

  if [[ "${result}" == "PASS" ]]; then
    echo "  ✓ ${app} (port ${port})"
  else
    echo "::error::${app}: ${result} under smoke profile"
    echo "----- last 25 log lines for ${app} -----"
    tail -n 25 "${log}"
    echo "----------------------------------------"
    failures=$((failures + 1))
  fi
  rm -f "${log}"
done

if [[ "${failures}" -ne 0 ]]; then
  echo "Smoke boot-test failed: ${failures} app(s) did not start."
  exit 1
fi
echo "Smoke boot-test passed: all ${#apps[@]} apps started."
