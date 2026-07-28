# Agentic AI Action Resolver Implementation Summary

## Status

The independent `agentic-ai-action-resolver` app is the P0/P1 reference proof
for AI Fabric's bounded specialist execution layer.

It was copied from the Account Resolver domain so behavior could be compared,
but it has its own artifact, package, port, database, Lucene index, sessions,
Dockerfile, and tests. The original `ai-fabric-account-resolver` tracked source
is unchanged.

## Approved Specialist

```text
ID: account-resolver@1
Mode: resolver
Strategy: BOUNDED_ITERATIVE
Read action: get_account_profile
Vector space: account-resolution-policy
Write enabled: false
Output: AccountResolutionResult
```

The same immutable definition serves:

- `POST /api/agentic-resolver/evaluate`, a typed application call with no chat
  read or persistence; and
- `POST /api/agentic-resolver/chat`, an interactive call using backend-owned
  conversation memory.

## Intelligence Flow

```text
public question
  -> server-owned demo session
  -> trusted initiator and bound account subject
  -> account-resolver@1
  -> effective capability intersection
  -> existing AI Fabric pipeline
       -> get_account_profile
       -> account-resolution-policy RAG
       -> OpenAI orchestration
  -> strict evidence projection
  -> one structured output call
  -> schema and authoritative-fact validation
  -> application-owned public normalization
  -> typed result
```

No request body may set account/user/subscription/tenant identity, scopes,
Mode, specialist, action catalog, or vector spaces.

## Scenarios

| Scenario | Expected result |
| --- | --- |
| `ready-account` | `READY`, no blockers |
| `missing-payment` | `BLOCKED`, `VERIFIED_PAYMENT_METHOD` |
| `missing-address` | `BLOCKED`, `VALIDATED_BILLING_ADDRESS` |

The scenario metadata is navigation only. It does not contain a prebuilt model
answer.

## Failure Behavior

The app does not manufacture a successful assessment when a dependency fails.
It returns typed failures for:

- capability or authority denial;
- missing/unscoped evidence;
- insufficient grounding;
- provider failure;
- invalid structured JSON;
- output decisions that conflict with current profile facts;
- deadline exhaustion; and
- conversation recording failure.

The offline smoke provider cannot produce the full grounded specialist
contract, so the packaged smoke call is expected to return
`GROUNDING_VALIDATION_FAILED`. This proves visible failure, not successful AI
behavior. Real intelligence is verified separately with OpenAI.

## Verification Snapshot

Focused framework:

```bash
mvn -B --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am test
```

Focused app:

```bash
mvn -B --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl agentic-ai-action-resolver -am test
```

Docker:

```bash
docker build \
  -f examples/real-apps/agentic-ai-action-resolver/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.4.0 \
  --build-arg BUILD_COMMIT="$(git rev-parse HEAD)" \
  --build-arg BUILD_BRANCH="$(git branch --show-current)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t agentic-ai-action-resolver:source \
  .
```

The Docker build compiles framework source and the app with tests enabled.

## Deployment

- port: `8105`
- Dockerfile:
  `examples/real-apps/agentic-ai-action-resolver/Dockerfile`
- required real-provider variables:
  `OPENAI_ENABLED=true`, `OPENAI_API_KEY`, and `OPENAI_MODEL`
- CORS:
  `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`

The current Dockerfile is source-based because `ai-fabric-execution` is not yet
published. It may switch to released Maven artifacts after the target framework
release.
