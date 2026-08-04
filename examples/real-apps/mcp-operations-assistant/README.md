# AI Fabric MCP Operations Assistant

This real app proves that an AI Fabric specialist can use a remote Model Context Protocol server
without surrendering application governance. A user can inspect one isolated sandbox service and
request a restart in natural language. AI Fabric owns trusted context, exact server and tool
binding, confirmation, durable receipts, replay protection, backend conversation memory, and safe
result projection.

The live profile never falls back to the local fixture. Authentication, provider, MCP transport,
or tool-contract failures remain visible.

## Architecture

```text
Browser
  | new message + opaque demo session + idempotency key
  v
MCP Operations Assistant (Spring Boot, port 8100)
  | backend-owned identity, selected service, scopes, and conversation
  | AI Fabric mcp-operations-specialist@1
  | AI Fabric action catalog + confirmation receipt
  v
SpringAiMcpActionExecutor
  | authenticated Streamable HTTP, exact serverRef and toolName
  v
MCP Operations Reference Server (Spring Boot, port 8106)
  | isolated mcp-demo-* state only
  v
Persistent JDBC state
```

## AI Fabric Capabilities

- typed specialist manifest with bounded iterative planning;
- backend-owned chat sessions and follow-up context;
- connector action catalog loaded from `ai-actions.yml`;
- exact MCP `serverRef` and tool binding;
- trusted argument construction for sandbox ID, selected service, and optimistic revision;
- read tools that can run directly;
- confirmation-gated write tool with durable JDBC receipt;
- idempotent replay protection;
- safe outcome projection and bounded connector responses;
- per-session invocation audit timeline; and
- explicit connection, authentication, provider, storage, and specialist health.

## Remote Tools

| Tool | Access | Confirmation | Purpose |
| --- | --- | --- | --- |
| `get_sandbox_service_status` | `READ` | No | Read selected sandbox service state |
| `list_recent_sandbox_incidents` | `READ` | No | Read bounded synthetic incident evidence |
| `restart_sandbox_service` | `WRITE_ONLY` | Yes | Restart the selected service once at the expected revision |

The browser cannot provide `sandboxId`, tenant, deployment, scopes, server reference, or revision.
The selected service is resolved again from the backend session by
`McpOperationsTrustedRuntimeContextStep`. A restart proposal obtains its current optimistic
revision through the governed status READ action, so neither the browser nor the model supplies the
concurrency token.

## Local Modes

### Offline contract smoke

The smoke profile uses a deterministic local fixture and is visibly marked `LOCAL_SMOKE_ONLY`:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl mcp-operations-assistant -am test
```

```bash
java -jar examples/real-apps/mcp-operations-assistant/target/mcp-operations-assistant-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

The local executor is rejected outside the smoke profile.

### Real remote MCP transport with smoke LLM

Run the reference server first, then override only the executor and MCP client settings:

```bash
MCP_SERVER_API_KEY=local-mcp-secret \
java -jar examples/real-apps/mcp-operations-reference-server/target/mcp-operations-reference-server-1.0.0-SNAPSHOT.jar
```

```bash
MCP_OPERATIONS_SERVER_API_KEY=local-mcp-secret \
java -jar examples/real-apps/mcp-operations-assistant/target/mcp-operations-assistant-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --app.mcp-operations.executor=remote \
  --spring.ai.mcp.client.enabled=true
```

This mode proves the real authenticated protocol while keeping model output deterministic. It is
not evidence of live LLM intelligence.

## Hosted Environment

Required:

```text
OPENAI_ENABLED=true
OPENAI_API_KEY=<protected>
OPENAI_MODEL=gpt-4o-mini
MCP_OPERATIONS_SERVER_URL=https://<reference-server-host>
MCP_OPERATIONS_SERVER_ENDPOINT=/mcp
MCP_OPERATIONS_SERVER_API_KEY=<same protected key as reference server>
AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET=<strong independent secret>
AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET=<strong independent secret>
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=<protected>
SPRING_DATASOURCE_PASSWORD=<protected>
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
```

Keep `APP_MCP_OPERATIONS_EXECUTOR=remote`, or omit it. Mount `/app/data` only for local H2 use;
hosted durability should use PostgreSQL.

## Docker

Until the connector hardening in this change set is published as an immutable framework patch,
build the source-candidate image from the repository root so the app and connector use the same
revision:

```bash
docker build \
  -f examples/real-apps/mcp-operations-assistant/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.5.3 \
  --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-mcp-operations-assistant:source-candidate \
  .
```

Build the reference server separately with the real-app reactor as its context:

```bash
docker build \
  -f examples/real-apps/mcp-operations-reference-server/Dockerfile \
  -t ai-fabric-mcp-operations-reference-server:source-candidate \
  examples/real-apps
```

After the next framework patch is published, switch the assistant deployment image back to an
immutable Maven Central consumer before claiming release proof.

## Public API

```text
POST   /api/mcp-ops/sessions
GET    /api/mcp-ops/sessions/{sessionId}
PUT    /api/mcp-ops/sessions/{sessionId}/service
DELETE /api/mcp-ops/sessions/{sessionId}
GET    /api/mcp-ops/sessions/{sessionId}/state
GET    /api/mcp-ops/sessions/{sessionId}/history
GET    /api/mcp-ops/sessions/{sessionId}/timeline
POST   /api/mcp-ops/sessions/{sessionId}/chat
POST   /api/mcp-ops/sessions/{sessionId}/actions/decide
POST   /api/mcp-ops/sessions/{sessionId}/binding-canary
GET    /api/mcp-ops/tools
GET    /api/mcp-ops/connection
GET    /api/demo/health
```

Chat accepts only `message` and requires an `Idempotency-Key` header. Confirm and reject use the
durable receipt ID returned by AI Fabric.

## Verification Scenarios

1. Ask for current checkout status and inspect the remote read audit.
2. Ask to restart checkout and verify that state does not change before confirmation.
3. Reject and verify no write audit exists.
4. Request again, confirm once, then replay the decision and verify one restart only.
5. Run the binding canary and verify `MCP_TOOL_NOT_AVAILABLE` with `writeDelta: 0`.
6. Remove the MCP API key and verify an explicit authentication failure.
7. Stop the reference server and verify no local fallback is used.

## Source Map

- action catalog: `src/main/resources/ai-actions.yml`
- specialist manifest: `src/main/resources/ai-specialists/mcp-operations-specialist.yml`
- remote bridge configuration: `McpClientConfiguration`
- trusted execution boundary: `McpOperationsExecutionService` and
  `McpOperationsTrustedRuntimeContextStep`
- safe audit projection: `McpInvocationAuditService`
- deployment image: `Dockerfile`

The companion server is documented in
[`../mcp-operations-reference-server/README.md`](../mcp-operations-reference-server/README.md).
