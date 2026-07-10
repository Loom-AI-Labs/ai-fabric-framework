# Troubleshooting Playbook

## "Pipeline step failed: AccessControl"

Likely causes:

- missing auth/subject context
- action or retrieval path requires a tenant/user but request did not provide server context
- app-side access policy blocks the operation

Check:

- request headers/session identity
- app access policy implementation
- whether the action should be public or authenticated only
- logs around the named pipeline step

## RAG Answer Is Generic

Likely causes:

- vector store is empty
- wrong entity type/vector space
- threshold too high
- embeddings disabled or failed
- retrieved docs do not contain the answer

Check:

- vector counts by entity type
- retrieved evidence panel/logs
- indexing seed/migration status
- embedding dimensions and vector index path

## Follow-Up Turn Loses Context

Likely causes:

- `ai-fabric-chat-session` not enabled
- frontend sends a new conversation ID every turn
- backend endpoint bypasses the chat session pipeline
- conversation owner id changed

Check:

- `ai.chat.enabled`
- `chat_sessions` and `chat_turns`
- `conversationId` and owner id stability
- whether history was enriched before intent resolution

## Action Asks For IDs The App Knows

Likely causes:

- action parameter schema includes backend-known IDs
- prompt/action description suggests user-provided target IDs
- handler does not use current account context

Fix:

- remove server-known IDs from user-facing action params
- resolve current account in handler
- update tests to assert no current-user IDs are requested

## Action Result Dumps Raw JSON

Likely causes:

- handler returns nested domain object without display projection
- UI renders all fields by default

Fix:

- return concise `message` plus selected display fields
- keep raw debug data out of public cards
- add a UI projection test if the app has a shared chat component

## Real Provider Fails But Smoke Passes

Likely causes:

- missing API key
- disabled provider flag
- model name mismatch
- embedding dimensions mismatch
- network timeout

Check:

- provider env vars
- `/actuator/health` or app health diagnostics
- logs for provider/model/purpose

