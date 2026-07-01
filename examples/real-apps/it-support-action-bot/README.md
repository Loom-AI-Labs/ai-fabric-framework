# IT Support Action Bot

## Scenario

This app demonstrates provider-only AI Fabric action orchestration for an IT helpdesk. It does not
need vector search, indexing, or RAG to prove that AI Fabric can run a useful action bot around a
ticketing domain.

The app includes ticket actions, authorization hooks, confirmation-required mutations, and a support
operations service that produces runbook-aware, customer-safe summaries.

## AI Fabric Capabilities Proved

- LLM-only orchestration path.
- Local `@AIAction` discovery and execution.
- `@ActionAllowed` authorization checks for support operations.
- `@ActionConfirmation` for ticket mutations.
- Confirmation-required create, assign, close, escalate, and priority update flows.
- Provider-only path remains usable when RAG is disabled.
- Support operations center workflow can combine runbook evidence, severity classification, governed
  actions, and post-action/customer-safe summaries.

## Framework Surfaces

- `ai-fabric-provider-starter`
- `ai-fabric-provider-spring-ai`
- local action annotations
- action access modes
- action confirmation policy
- post-action summary pattern

## Runtime Posture

Default runtime can boot without external keys. To run true LLM-backed action selection, enable an
LLM provider such as OpenAI.

Default database: H2.

Default port: `8082`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl it-support-action-bot -am package
java -jar examples/real-apps/it-support-action-bot/target/it-support-action-bot-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Enable OpenAI

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="..."
```

Then run the app without the smoke profile or with an OpenAI-enabled profile/config.

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl it-support-action-bot -am test
```

Use `requests/demo.http` to run the scenario.

## Demo Flow

1. Seed demo tickets and agents.
2. Ask the bot to create a ticket.
3. Assign or escalate a ticket.
4. Try a mutation that requires confirmation.
5. Confirm the pending action.
6. Inspect the final ticket state.
7. Generate a customer-safe support summary.

## What This App Does Not Cover

- Retrieval/RAG over indexed documents. Use `smart-faq-assistant` or `chat-capabilities-demo`.
- DB-backed action registration. Use `db-action-registry-lab`.
- MCP tool execution. Use `mcp-operations-assistant`.
