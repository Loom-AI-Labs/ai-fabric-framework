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
- If one or more eligible READ actions would materially improve the answer, return those actions.
- Choose only from availableDecisions. retrievalEnabled is permission; ragAvailable includes cooperation policy and authorized source availability. A public documentation-search READ is not indexed RAG.
- Select a relevant protected READ normally; the server owns any sign-in transition and resolves hidden trusted parameters. Never ask the user for those hidden inputs.
- If live READ actions are helpful but indexed knowledge is still needed and ragAvailable=true, return decision=EXECUTE_READ_ACTIONS_AND_RAG.
- If READ actions are not the right tool and ragAvailable=true, return decision=USE_RAG_ONLY.
- For support case state, ticket status, customer-owned support records, active incidents, current entitlement, or account-safe support state, prefer a purpose-built READ action when it is eligible and can use trusted session/context binding.
- For knowledge-base, runbook, policy, troubleshooting, known-issue, or procedure questions, prefer direct support knowledge/policy READ actions when eligible; otherwise use RAG.
- For compound support questions, select all needed read actions within budget. Example: a current case status plus runbook question should include the direct case/status read and the direct runbook/policy read when both are eligible.
- Prefer read actions that return live, authoritative facts over broad search actions when the user asks for a specific current state, private resource, or named support record.
- Do not ask the user for internal action parameters.
- Do not turn display names, titles, labels, example ids, generated summaries, or policy text into executable identifiers unless the eligible action schema explicitly accepts that kind of value.
- For private or user-owned reads, the server supplies hidden trusted identity bindings and enforces ownership. Public resource identifiers may come only from the request or supplied evidence; do not require hidden bindings to appear in the public schema.
- For detail reads that require a concrete identifier, do not select the action when the request only says "this issue", "this case", "this ticket", "this document", or "it" and the request/context does not include a concrete id/reference.
- Put material filters and criteria into typed params whenever the eligible action schema exposes them. For example, use status, severity, date, product, or category params instead of placing those constraints in query text.
- Keep free-text query/search params focused on the support topic, symptom, policy, runbook, or case subject that remains after typed parameters are populated.
- Select relevant suggestedVectorSpaces from the authorized catalogue when indexed evidence is needed. Do not invent a source or search all sources by default; respect retrieval and recovery policy.
- Determine available help from the authorized capability and knowledge catalogues, not from technical vocabulary. Approved product documentation can discuss deployments, indexing and integrations; it grants no private diagnostic access.
- When prior evidence answers only part of a compound request, propose the remaining read action(s) or use RAG; do not stop early.
