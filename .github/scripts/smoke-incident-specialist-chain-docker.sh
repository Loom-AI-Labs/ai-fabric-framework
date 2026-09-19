#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_DIR="${ROOT_DIR}/examples/real-apps/incident-investigation-room"
IMAGE="ai-fabric-incident-chain-candidate:${GITHUB_RUN_ID:-local}"
CONTAINER="ai-fabric-incident-chain-${GITHUB_RUN_ID:-local}-$$"
POSTGRES_CONTAINER="${CONTAINER}-postgres"
NETWORK="${CONTAINER}-network"
HOST_PORT="${INCIDENT_CHAIN_SMOKE_PORT:-18107}"
BASE_URL="http://127.0.0.1:${HOST_PORT}"
LOG_FILE="${TMPDIR:-/tmp}/incident-chain-docker-${$}.log"
HEALTH_FILE="${TMPDIR:-/tmp}/incident-chain-health-${$}.json"
POSTGRES_PASSWORD="incident-chain-smoke-password"

cleanup() {
  local exit_code=$?
  trap - EXIT
  docker logs "${CONTAINER}" >"${LOG_FILE}" 2>&1 || true
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  exit "${exit_code}"
}
trap cleanup EXIT

if ! compgen -G "${APP_DIR}/target/incident-investigation-room-*.jar" >/dev/null; then
  echo "Packaged incident-investigation-room JAR is required before Docker smoke." >&2
  exit 1
fi

if [ -z "${AI_FABRIC_VERSION:-}" ]; then
  AI_FABRIC_VERSION="$(
    mvn -q -f "${APP_DIR}/pom.xml" help:evaluate \
      -Dexpression=ai-fabric.version \
      -DforceStdout
  )"
fi
if [ -z "${AI_FABRIC_VERSION}" ]; then
  echo "ai-fabric.version is missing" >&2
  exit 1
fi

SOURCE_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
if [ -n "$(git -C "${ROOT_DIR}" status --porcelain)" ]; then
  SOURCE_COMMIT="${SOURCE_COMMIT}-dirty"
fi

docker build \
  -f "${APP_DIR}/Dockerfile.candidate" \
  --build-arg "AI_FABRIC_VERSION=${AI_FABRIC_VERSION}" \
  --build-arg "SOURCE_COMMIT=${SOURCE_COMMIT}" \
  --build-arg "SOURCE_BRANCH=$(git -C "${ROOT_DIR}" branch --show-current)" \
  -t "${IMAGE}" \
  "${APP_DIR}"

docker network create "${NETWORK}" >/dev/null
docker run -d \
  --name "${POSTGRES_CONTAINER}" \
  --network "${NETWORK}" \
  --network-alias postgres \
  -e POSTGRES_DB=incident_chain \
  -e POSTGRES_USER=incident_chain \
  -e "POSTGRES_PASSWORD=${POSTGRES_PASSWORD}" \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "${POSTGRES_CONTAINER}" \
      pg_isready -U incident_chain -d incident_chain >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 60 ]; then
    docker logs "${POSTGRES_CONTAINER}" >&2 || true
    echo "PostgreSQL did not become ready for the chain smoke." >&2
    exit 1
  fi
  sleep 1
done

start_app() {
  docker run -d \
    --name "${CONTAINER}" \
    --network "${NETWORK}" \
    -e SPRING_PROFILES_ACTIVE=smoke \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/incident_chain \
    -e SPRING_DATASOURCE_USERNAME=incident_chain \
    -e "SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}" \
    -p "127.0.0.1:${HOST_PORT}:8107" \
    "${IMAGE}" >/dev/null
}

wait_for_app() {
  for attempt in $(seq 1 90); do
    if curl --fail --silent "${BASE_URL}/api/demo/health" \
        >"${HEALTH_FILE}" \
        && jq -e \
          '.status == "UP" and .runbooks.state == "READY"' \
          "${HEALTH_FILE}" >/dev/null; then
      return
    fi
    if [ "${attempt}" -eq 90 ]; then
      docker logs "${CONTAINER}" >&2 || true
      echo "Incident chain candidate did not become ready." >&2
      exit 1
    fi
    sleep 1
  done
}

start_app
wait_for_app

STATE_FILE="${TMPDIR:-/tmp}/incident-chain-state-${$}.json"
python3 - "${BASE_URL}" "${STATE_FILE}" <<'PY'
import json
import sys
import urllib.request

base_url = sys.argv[1]
state_file = sys.argv[2]

def request(path, method="GET", payload=None, headers=None):
    body = None if payload is None else json.dumps(payload).encode()
    values = {"Content-Type": "application/json"}
    values.update(headers or {})
    with urllib.request.urlopen(
        urllib.request.Request(
            base_url + path,
            data=body,
            headers=values,
            method=method,
        ),
        timeout=20,
    ) as response:
        return json.load(response)

health = request("/api/demo/health")
assert health["status"] == "UP", health
assert health["chainsReady"] is True, health
assert health["storage"]["specialistChains"] == "JDBC", health
assert health["chainManifests"]["ready"] is True, health
assert health["chainManifests"]["discovered"] == 1, health
assert health["chainManifests"]["registered"] == 1, health
manifest_chain = next(
    item for item in health["chains"]
    if item["id"] == "incident-declarative-investigation@1"
)
assert manifest_chain["source"] == "MANIFEST", manifest_chain

session = request(
    "/api/incidents/sessions",
    method="POST",
    payload={"scenarioId": "checkout-regression"},
)
session_id = session["sessionId"]
result = request(
    f"/api/incidents/sessions/{session_id}/declarative-investigations",
    method="POST",
    payload={"question": "Check current service latency and errors."},
    headers={
        "X-AI-Fabric-Demo-Session": session_id,
        "Idempotency-Key": "docker-smart-health-1",
    },
)
assert result["status"] == "COMPLETED", result
assert result["durable"] is True, result
assert [item["specialist"] for item in result["results"]] == [
    "service-health-reader@2"
], result
assert [item["directiveType"] for item in result["timeline"]] == [
    "INVOKE_ONE",
    "COMPLETE",
], result

replay = request(
    f"/api/incidents/sessions/{session_id}/declarative-investigations",
    method="POST",
    payload={"question": "Check current service latency and errors."},
    headers={
        "X-AI-Fabric-Demo-Session": session_id,
        "Idempotency-Key": "docker-smart-health-1",
    },
)
assert replay["replayed"] is True, replay
assert replay["executionId"] == result["executionId"], replay
assert replay["timeline"] == result["timeline"], replay
with open(state_file, "w", encoding="utf-8") as stream:
    json.dump({
        "sessionId": session_id,
        "executionId": result["executionId"],
        "timeline": result["timeline"],
    }, stream)
PY

docker rm -f "${CONTAINER}" >/dev/null
start_app
wait_for_app

python3 - "${BASE_URL}" "${STATE_FILE}" <<'PY'
import json
import sys
import urllib.request

base_url = sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as stream:
    state = json.load(stream)

body = json.dumps({
    "question": "Check current service latency and errors."
}).encode()
session_id = state["sessionId"]
request = urllib.request.Request(
    base_url
    + f"/api/incidents/sessions/{session_id}/declarative-investigations",
    data=body,
    headers={
        "Content-Type": "application/json",
        "X-AI-Fabric-Demo-Session": session_id,
        "Idempotency-Key": "docker-smart-health-1",
    },
    method="POST",
)
with urllib.request.urlopen(request, timeout=20) as response:
    replay = json.load(response)

assert replay["replayed"] is True, replay
assert replay["executionId"] == state["executionId"], replay
assert replay["timeline"] == state["timeline"], replay
print("Incident specialist-chain PostgreSQL restart smoke passed.")
PY
