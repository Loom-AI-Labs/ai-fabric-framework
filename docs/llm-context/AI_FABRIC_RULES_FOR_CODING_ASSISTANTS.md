# Rules For Coding Assistants

## Do

- Read the target app's `pom.xml`, `application.yml`, and README before changing code.
- Identify AI Fabric modules and providers already in use.
- Prefer existing real app patterns over new abstractions.
- Use `@EnableAIInfrastructure` once per Spring Boot app.
- Add focused tests for every framework-facing change.
- Keep real provider tests separate from no-key tests.
- Expose missing evidence or provider failures honestly.

## Do Not

- Do not replace AI Fabric orchestration with frontend keyword matching.
- Do not ask users for server-known identifiers such as current subscription ID.
- Do not make policy docs unreadable just to steer prompts.
- Do not dump raw action result objects into UI cards.
- Do not hide LLM failures behind deterministic fallback unless the feature contract says so.
- Do not store private credentials in public docs.
- Do not widen tenant/user retrieval filters when provider metadata filtering is unsupported.

## Preferred Patterns

- Backend owns chat history through `ai-fabric-chat-session`.
- UI sends the latest message plus stable session identity.
- RAG displays evidence and score/metadata diagnostics.
- Actions return domain summaries and structured fields for UI.
- Public demos create per-session users and cleanup old demo data.
- Prompt overlays belong in app resources when app-specific; reusable prompt improvements belong in curated modules.

## Test Discipline

For code changes, report:

- unit tests run
- real app tests run
- smoke profile status
- real provider status if keys were available
- what remains unverified

