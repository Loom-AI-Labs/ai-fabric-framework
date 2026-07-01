# MCP Operations Assistant

## Scenario

This app demonstrates governed MCP-style operations tool execution behind AI Fabric action policy.

It uses a deterministic local MCP executor that implements the same `McpActionExecutor` abstraction
used by the framework's Spring AI MCP bridge. The app focuses on governance: read-only tools can run
directly, while write/destructive tools require confirmation.

## AI Fabric Capabilities Proved

- MCP-style tool catalog can be represented as AI Fabric actions.
- Read-only tool execution returns sanitized output.
- Write/destructive tool execution requires confirmation.
- Unknown tools return structured failures.
- Unavailable MCP executors return structured failures.
- Hidden connector context is stripped from user-facing tool output.
- Action access modes stay meaningful even when the underlying tool source is MCP.

## Framework Surfaces

- `ai-fabric-actions-connector`
- `McpActionExecutor`
- action access modes
- action confirmation policy
- structured action result handling
- Spring AI MCP bridge compatibility shape

## Runtime Posture

This app is local and deterministic:

- no external MCP server
- no model keys
- no vector DB
- in-app executor fixture implementing framework interface

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl mcp-operations-assistant -am package
java -jar examples/real-apps/mcp-operations-assistant/target/mcp-operations-assistant-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl mcp-operations-assistant -am test
```

Use `requests/demo.http` to run the scenario.

## Demo Flow

1. Run a read-only service-health tool.
2. Run a read-only runbook/search tool.
3. Attempt a write/destructive rollback tool.
4. Observe confirmation-required evidence.
5. Confirm and execute the tool.
6. Try an unknown tool and verify structured failure.

## What This App Does Not Cover

- Live MCP server discovery.
- Live Spring AI model tool-calling.
- Runtime DB action registry.

Those are separate from this governance bridge proof.
