# AI Fabric Retrieval Connector Boundary Lab

This packaged Spring Boot app proves that an external customer retrieval
service remains a read-only, documents-only evidence source.

The app contains a deliberately co-located customer fixture at
`POST /fixture/retrieval/search`. AI Fabric still calls it over HTTP through
`ai-fabric-retrieval-connector`; the fixture is not injected as a Java mock.

## What It Proves

- Valid evidence is projected, bounded, and sent to AI Fabric generation.
- Unapproved metadata is removed before evidence reaches the response.
- Tenant denial remains visible.
- Generated-answer injection is rejected.
- Cross-vector-space evidence is rejected.
- Unsafe URLs and reserved `_aifabric*` metadata are rejected.
- Rejected evidence never reaches the LLM.
- Connector failures are never replaced with a fallback answer.

## Run Offline

From `examples/real-apps`:

```bash
mvn -B --no-transfer-progress \
  -pl retrieval-connector-boundary-lab -am package

java -jar retrieval-connector-boundary-lab/target/\
retrieval-connector-boundary-lab-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

Then run the accepted path:

```bash
curl -sS -X POST http://localhost:8105/api/retrieval-boundary/run \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"VALID","question":"Can I return an opened laptop?"}'
```

Run a rejected path:

```bash
curl -sS -X POST http://localhost:8105/api/retrieval-boundary/run \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"CROSS_VECTOR_SPACE","question":"Show the policy"}'
```

The smoke profile is explicitly deterministic and local. It proves packaging
and control flow; it is not presented as real model intelligence.

## Verify An Unpublished Framework Build

The default Docker target consumes the selected AI Fabric version from Maven
Central. To prove the exact locally tested source before publication, first
package the app against the locally installed framework, then build the
explicit source-artifact target:

```bash
mkdir -p /tmp/retrieval-boundary-candidate
cp retrieval-connector-boundary-lab/target/\
retrieval-connector-boundary-lab-1.0.0-SNAPSHOT.jar \
  /tmp/retrieval-boundary-candidate/

docker build \
  --target release-candidate \
  --build-context \
  release-candidate-artifact=/tmp/retrieval-boundary-candidate \
  -f retrieval-connector-boundary-lab/Dockerfile \
  -t retrieval-connector-boundary-lab:candidate .
```

This target prevents a source-candidate test from silently rebuilding against
the previously published framework version.

## Optional Live Generation

Set:

```bash
OPENAI_ENABLED=true
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o-mini
APP_DEMO_REQUIRE_REAL_AI=true
```

Do not activate the `smoke` profile for this run. Retrieval remains
customer-owned and documents-only; OpenAI is used only after AI Fabric accepts
the evidence.

## Use A Separate Customer Connector

Point the lab at a separately deployed connector:

```bash
RETRIEVAL_CONNECTOR_BASE_URL=https://connector.customer.example
```

The service must expose `POST /retrieval/search` and authorize the canonical
`trace.authContext` before returning evidence.
