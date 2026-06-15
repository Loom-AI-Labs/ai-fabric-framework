Request query:
{{query}}

Resolved mode:
{{mode}}

Extracted intent JSON:
{{intent_json}}

Eligible READ actions JSON:
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
- If live READ actions are helpful but indexed knowledge is still needed, return decision=EXECUTE_READ_ACTIONS_AND_RAG.
- If READ actions are not the right tool, return decision=USE_RAG_ONLY.
- For compound requests, select all needed read actions within budget. Example: a current-status + policy/rule question should include both the direct status action and the direct policy/rule action when both are eligible.
- Prefer the most specific purpose-built READ action when one directly matches the request.
- For Commerce shopper-owned context discovery, use commerce_get_customer_context_summary when the request asks for a mixed or implicit owned resource state such as "what is in my cart", "show my customer context", "latest order", "do I have store credit", or "return my last order" and the exact downstream action lacks required context. It is a read-only summary action; never use it as a substitute for confirmed mutations.
- For shopper product search, product discovery, catalog lookup, availability, price, or add-to-cart target resolution requests, prefer an eligible catalog search READ action before answering that no product exists. Use params.query as the shopper's product/category/preference phrase, not a generic word such as "products".
- For commerce catalog requests, commerce_search_catalog is the preferred catalog search READ action when eligible. For generic commerce requests, prefer search_products or list_products when eligible.
- Do not answer "no products found", "not available", or similar absence claims from empty indexed/RAG context until an eligible catalog search READ action has been attempted or no catalog search action is eligible.
- For write actions that need a concrete product target, let the configured action parameter resolver use catalog search evidence. Keep the read query minimal and grounded in the shopper request.
- For relationship_query, apply this rule only when relationship_query is listed in Eligible READ actions JSON. Derive params.query from the user's ask plus supplied page/record/context evidence. The user must never be asked to provide "the relationship query"; if you cannot derive a useful relationship query, choose USE_RAG_ONLY or EXECUTE_READ_ACTIONS_AND_RAG with another eligible action.
- For policy/rule/procedure questions, prefer the direct policy/rule action when it is eligible. Do not rely on an unrelated record/status action to answer policy evidence.
- Do not decompose a compare request into multiple generic detail lookups if a single comparison action exists.
- Do not decompose a similar/alternative request into generic detail lookups if a single related-records action exists.
- Do not decompose a current-status check into broader catalog reads if a direct status action exists.
- When prior evidence answers only part of a compound request, propose the remaining read action(s) or use RAG; do not stop early.
- Keep params minimal and only use values that are directly implied by the request.
- For product-detail reads that require a concrete product identifier, do not select the action when the request only says "this product" and the request/context does not include a product id. Use RAG instead when available, or leave the missing evidence for answer generation in natural shopper-facing language.
- Put material filters and criteria into typed params whenever the eligible action schema exposes them. For example, use numeric bound params for price ceilings and boolean params for availability instead of placing those constraints in query text.
- Keep free-text query/search params focused on the subject to retrieve or compare. If a material constraint cannot be represented by typed params and the action result may be insufficient, combine the read action with RAG or use RAG according to the rag cooperation mode.
