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

- Do not infer natural-language meaning with keywords, substrings, regexes, token overlap, punctuation, query length, or phrase order in frontend or backend production code.
- Do not use text matching to select an intent, action, parameter, target, vector space, knowledge source, specialist, retrieval mode, evidence, or policy category.
- Do not ask users for server-known identifiers such as current subscription ID.
- Do not make policy docs unreadable just to steer prompts.
- Do not dump raw action result objects into UI cards.
- Do not hide, correct, or replace an LLM decision through deterministic semantic fallback.
- Do not permit semantic recovery unless a named server-owned allow-policy explicitly enables it and diagnostics expose the original and effective decisions.
- Do not store private credentials in public docs.
- Do not widen tenant/user retrieval filters when provider metadata filtering is unsupported.

## Natural-Language Decision Boundary

- Natural-language semantic decisions must come from validated structured LLM output, trusted structured application input, or an explicitly configured retrieval/classification provider.
- Improve the prompt, schema, catalog descriptions, examples, and model when output quality is weak. Occasional measured model error is preferable to a phrase-specific code path that hides the defect.
- A bounded LLM repair/replan call is permitted when configured. If repair is not allowed or remains invalid, clarify or fail with a typed reason.
- Configuration constrains capabilities, authority, sources, actions, budgets, and recovery permission. It does not guess user meaning or silently override a valid model result.
- Security checks may deny or constrain an operation. They must not reinterpret the request.
- Deterministic parsing remains valid for JSON/schema contracts, exact identifiers/enums/allowlists, authorization/provenance, bounds, hashes, paths, MIME/protocol values, and formally declared detectors.
- Declared vector similarity, BM25/hybrid retrieval, and configured rerankers are retrieval algorithms. Ad hoc query-term scoring inside orchestration is not.
- Test/smoke fake providers may use deterministic fixtures only when they cannot start under a production posture and are clearly reported as non-AI behavior.
- A diagnostic only observes. Code that mutates intent, routing, evidence, authority, or the semantic result is production behavior and must follow these rules.
- Presentation-only matching is allowed for labels, icons, renderer selection, and illustrative progress when it cannot alter the request, backend decision, authority, diagnostics, or audit truth.

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
