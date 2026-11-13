Request query:
{{query}}

Resolved mode:
{{mode}}

Extracted intent JSON:
{{intent_json}}

Authorized knowledge-source catalogue JSON (server-owned; names are retrieval choices, not evidence):
{{knowledge_sources_json}}

READ planning catalog JSON (public inputs only; the server owns authentication and hidden inputs):
{{eligible_actions_json}}

Prior read-action evidence JSON:
{{prior_evidence_json}}

Planner budgets:
- iteration: {{iteration}} / {{max_iterations}}
- max actions this iteration: {{max_actions_per_iteration}}
- max total actions: {{max_total_actions}}
- rag cooperation mode: {{rag_cooperation_mode}}

Instructions:
- If current evidence is already sufficient, return decision=ANSWER_FROM_CONTEXT with no actions.
- Determine available help from the authorized capability and knowledge catalogues, not from whether the request uses technical vocabulary. Approved product setup and troubleshooting documentation may discuss deployments, indexing, providers, or integrations. This never authorizes undisclosed internals, secrets, or private diagnostic reads without their declared permission.
- If one or more eligible READ actions would materially improve the answer, return those actions.
- Select the relevant protected READ with EXECUTE_READ_ACTIONS; the server will request sign-in before execution if needed. Supply only public params that can be derived. Hidden identity bindings are server-owned and intentionally absent from the public schema.
- Choose only from availableDecisions in the knowledge-source catalogue. retrievalEnabled is permission; ragAvailable describes whether indexed retrieval can actually be used in this plan. Prefer a relevant documentation/policy READ over an unavailable indexed route.
- If live READ actions are helpful but indexed knowledge is still needed and ragAvailable=true, return decision=EXECUTE_READ_ACTIONS_AND_RAG.
- If READ actions are not the right tool and ragAvailable=true, return decision=USE_RAG_ONLY.
- When indexed knowledge is needed, select its relevant vectorSpace name(s) from the authorized catalogue in suggestedVectorSpaces, even if extraction initially requested no retrieval. Do not repeat an absent source selection merely because it is absent from the extracted intent.
- An empty catalogue or retrievalEnabled=false does not authorize invented sources. Never interpret omission as permission to search every source; respect the stated selection, fan-out and missing-source recovery policy.
- For compound requests, select all needed read actions within budget. Example: a current-status + policy/rule question should include both the direct status action and the direct policy/rule action when both are eligible.
- Prefer the most specific purpose-built READ action when one directly matches the request.
- Prefer read actions that return live, authoritative facts over broad search actions when the user asks for a specific current state, private resource, or named record.
- For relationship_query, apply this rule only when relationship_query is listed in the READ planning catalogue. Derive params.query from the user's ask plus supplied page/record/context evidence. Never ask the user to provide an internal query. Other actions or indexed retrieval must be eligible under the same catalogue and availability rules.
- For policy/rule/procedure questions, prefer the direct policy/rule action when it is eligible. Do not rely on an unrelated record/status action to answer policy evidence.
- Do not decompose a compare request into multiple generic detail lookups if a single comparison action exists.
- Do not decompose a similar/alternative request into generic detail lookups if a single related-records action exists.
- Do not decompose a current-status check into broader catalog reads if a direct status action exists.
- When prior evidence answers only part of a compound request, propose the remaining read action(s) or use RAG; do not stop early.
- Keep params minimal and only use values that are directly implied by the request.
- Do not turn display names, titles, labels, example ids, or generated summaries into executable identifiers unless the eligible action schema explicitly accepts that kind of value.
- For detail reads that require a concrete identifier, do not select the action when the request only says "this item", "this record", "this document", or "it" and the request/context does not include a concrete id/reference. Use RAG instead when available, or leave the missing evidence for answer generation in natural user-facing language.
- For private or user-owned reads, select the relevant action without asking for hidden identity inputs or deciding whether to sign in. The server supplies trusted bindings and enforces ownership. Only public resource identifiers declared by the schema may come from the request or supplied evidence; conversation text is not an ownership grant.
- For current-user/current-tenant/current-session reads, hidden bindings are resolved by the server after its authentication check, not reconstructed from the public action schema.
- Never ask the user for internal action parameters such as userId, accountId, tenantId, sessionId, hidden database ids, provider ids, or trace ids.
- Put material filters and criteria into typed params whenever the eligible action schema exposes them. For example, use numeric bound params for price ceilings and boolean params for availability instead of placing those constraints in query text.
- Keep free-text query/search params focused on the subject to retrieve or compare. If a material constraint cannot be represented by typed params and the action result may be insufficient, combine the read action with RAG or use RAG according to the rag cooperation mode.
