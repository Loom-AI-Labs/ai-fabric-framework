# Conversation Handover Demo

This standalone application proves AI Fabric's conversation-control mechanics
without LoomAI Platform, a LoomAI runtime, or the Basic LoomAI Inbox service.
It depends only on the released `ai-fabric-chat-session` artifact and standard
Spring Boot infrastructure.

The page shows a visitor and a deliberately small application-owned operator
view side by side. It proves:

- deployment-local durable conversation control;
- `AI_ACTIVE -> HUMAN_PENDING -> HUMAN_ACTIVE -> AI_RESUME_PENDING -> AI_ACTIVE`;
- ordered `USER`, `AI`, `HUMAN`, and `SYSTEM` participant messages;
- AI responses blocked while human authority is pending or active;
- explicit, versioned, idempotent transitions; and
- conversation-owner and operator-session isolation.

AI Fabric supplies the state machine, JDBC store, AI turn lease, role gates,
and message ordering. This demo owns HTTP routes, browser sessions, the tiny
operator login, and UI. Those host-owned pieces are intentionally not a model
for an enterprise workforce integration.

## Run

```bash
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl conversation-handover-demo -am package

APP_DEMO_OPERATOR_ACCESS_CODE=demo-handover \
java -jar examples/real-apps/conversation-handover-demo/target/conversation-handover-demo-1.0.0-SNAPSHOT.jar
```

Open `http://localhost:8110`. The local default operator code is
`demo-handover`; hosted deployments must override it.

## Verify

```bash
mvn -B --no-transfer-progress \
  -f examples/real-apps/conversation-handover-demo/pom.xml test

.github/scripts/smoke-conversation-handover-demo.sh
```

The HTTP test executes the full AI-to-human-to-AI lifecycle, verifies that no
AI message is produced while a human owns the conversation, and checks both
owner and operator boundaries.
