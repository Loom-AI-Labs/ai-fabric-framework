# AI Fabric MCP Operations Reference Server

This is the authenticated remote MCP boundary used by the AI Fabric MCP Operations Assistant. It
is deliberately narrow: it exposes three typed tools over Streamable HTTP and can mutate only
ephemeral `mcp-demo-*` sandbox state for `catalog`, `checkout`, or `payments`.

It does not expose shell commands, arbitrary HTTP, SQL, deployment APIs, production credentials,
or model access.

## Tools

```text
get_sandbox_service_status
list_recent_sandbox_incidents
restart_sandbox_service
```

The restart tool requires the current `expectedRevision`, providing optimistic concurrency at the
remote system-of-record boundary. Confirmation and authorization remain the caller application's
responsibility; this server independently enforces its resource allowlist and protocol key.

## Build And Test

From `examples/real-apps`:

```bash
mvn -B -V --no-transfer-progress \
  -pl mcp-operations-reference-server -am test
```

## Run

```bash
MCP_SERVER_API_KEY=local-mcp-secret \
java -jar mcp-operations-reference-server/target/mcp-operations-reference-server-1.0.0-SNAPSHOT.jar
```

The MCP endpoint is `http://localhost:8106/mcp`. Requests without the
`X-MCP-API-KEY` header receive `401`.

## Hosted Environment

```text
PORT=8106
MCP_SERVER_API_KEY=<strong protected shared key>
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=<protected>
SPRING_DATASOURCE_PASSWORD=<protected>
SPRING_JPA_HIBERNATE_DDL_AUTO=update
APP_SANDBOX_TTL=PT6H
APP_SANDBOX_CLEANUP_CRON=0 41 * * * *
JAVA_OPTS=-Xms128m -Xmx512m
```

Use an independent database or schema from the assistant. The API key is server-to-server only and
must never be sent to the browser.

## Health

```text
GET /actuator/health
GET /api/demo/health
```

The health response reports safe build and storage readiness. It never returns the MCP key,
database URL, or sandbox records.
