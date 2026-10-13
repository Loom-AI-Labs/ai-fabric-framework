# Conversation Handover Demo

This standalone application proves AI Fabric's model-selected conversation
handover and conversation-control mechanics without LoomAI Platform, a LoomAI
runtime, or the Basic LoomAI Inbox service. It uses released AI Fabric
provider/core artifacts for orchestration and `ai-fabric-chat-session` for the
durable authority state machine.

The page shows a visitor and a deliberately small application-owned operator
view side by side. It proves:

- deployment-local durable conversation control;
- model selection and execution of the application-owned
  `request_conversation_handover` `@AIAction`;
- typed `HandoverIntakeSchema` admission with required email, optional name and
  phone, and explicit consent before response authority changes;
- dynamic confirmation: an explicit user request does not add a redundant
  action confirmation, while a model recommendation still requires one; both
  paths remain subject to the deterministic intake gate;
- `AI_ACTIVE -> HUMAN_PENDING -> HUMAN_ACTIVE -> AI_RESUME_PENDING -> AI_ACTIVE`;
- ordered `USER`, `AI`, `HUMAN`, and `SYSTEM` participant messages;
- AI responses blocked while human authority is pending or active;
- explicit, versioned, idempotent transitions; and
- conversation-owner and operator-session isolation.

AI Fabric supplies the state machine, typed intake/admission contracts, JDBC
store, AI turn lease, role gates, and message ordering. This demo owns intake
validation and persistence, HTTP routes, browser sessions, the tiny operator
login, and UI. The demo H2 contact store is intentionally simple and must not
be treated as a production encryption or retention design. Those host-owned
pieces are not a model for an enterprise workforce integration.

## Run

```bash
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl conversation-handover-demo -am package

APP_DEMO_OPERATOR_ACCESS_CODE=demo-handover \
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
java -jar examples/real-apps/conversation-handover-demo/target/conversation-handover-demo-1.0.0-SNAPSHOT.jar
```

Open `http://localhost:8110`. Ask, for example, “Please transfer me to a human
operator.” The model-selected action returns `HANDOVER_INTAKE_REQUIRED`; the
host renders the same reviewed form used by the explicit button. The control
state remains `AI_ACTIVE` until a valid email and consent are submitted. The
local default operator code is `demo-handover`; hosted deployments must
override it.

## Verify

```bash
mvn -B --no-transfer-progress \
  -f examples/real-apps/conversation-handover-demo/pom.xml test

.github/scripts/smoke-conversation-handover-demo.sh
```

The HTTP test executes the full AI-to-human-to-AI lifecycle, proves missing
required intake fails before the state transition, verifies that no AI message
is produced while a human owns the conversation, tests the governed handover
action directly, and checks both owner and operator boundaries. The hosted demo
is the real-provider proof that natural language selects the action; tests do
not substitute phrase matching for model intent extraction.
