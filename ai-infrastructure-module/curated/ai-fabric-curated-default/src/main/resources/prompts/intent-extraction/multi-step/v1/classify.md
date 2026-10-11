You are classifying a user request into one or more intents.
Output MUST be valid JSON and MUST match the following schema:

{
  "intents": [
    {
      "obligationId": "obligation-1",
      "type": "ACTION | INFORMATION | OUT_OF_SCOPE | CONFIRMATION_POSITIVE | CONFIRMATION_NEGATIVE",
      "intent": "canonical_intent_name",
      "userGoal": "concise semantic goal",
      "actionHint": "short verb phrase (only when type=ACTION)",
      "requiresRetrieval": true,
      "requiresGeneration": false,
      "responseProfile": "CONCISE | STANDARD | DEEP",
      "requiresTargetResolution": false,
      "directAnswer": "required for OUT_OF_SCOPE and when INFORMATION requiresRetrieval=false (customer-safe short reply)",
      "generationInstructions": "optional follow-up instruction when requiresGeneration is true",
      "needsAdvancedRAG": false,
      "optimizedQuery": "optional optimized query",
      "vectorSpace": "exact registered domain when retrieval is required",
      "missingInformation": [],
      "clarificationQuestion": null,
      "uncertaintyReason": null
    }
  ],
  "metadata": {
    "retrievalQueryHint": "optional keywords/identifiers to improve retrieval (only when exactly one intent uses retrieval)"
  }
}

Rules:
- Preserve every user obligation. Give each intent a stable obligationId; this is mandatory when more than one intent is emitted.
- Set requiresRetrieval, requiresGeneration, requiresTargetResolution, and needsAdvancedRAG explicitly for every applicable intent.
- When requiresRetrieval=true, choose vectorSpace only from the exact KNOWLEDGE BASE OVERVIEW catalog in the system prompt. If no safe decision is possible, leave it null and populate missingInformation, clarificationQuestion, and uncertaintyReason.
- Do NOT invent action names; for ACTION use actionHint only.
- Highest priority: if the USER REQUEST asks about assistant implementation, infrastructure, internal status, runtime behavior, tool status, retrieval/vectorization, providers, platform internals, logs, deployments, or secrets, output OUT_OF_SCOPE. Do not classify these requests as INFORMATION and do not set requiresRetrieval=true.
- The USER REQUEST may include a "PENDING ACTION (requires confirmation)" section describing an action awaiting approval.
  - If the user is clearly approving/confirming the pending action, output a single intent with type=CONFIRMATION_POSITIVE.
  - If the user is clearly rejecting/cancelling the pending action, output a single intent with type=CONFIRMATION_NEGATIVE.
  - For confirmation intents: set requiresRetrieval=false, requiresGeneration=false, requiresTargetResolution=false, and leave actionHint/optimizedQuery/vectorSpace empty.
- The USER REQUEST may include an "ATTACHMENTS (user context; pinned targets)" section listing pinned targets (ref=att#N).
  - Treat these attachments as user-provided context for this turn.
  - Prefer identifiers/attributes from attachments (id and/or metadata/contentText) when setting optimizedQuery and actionParams.
  - Retrieval (RAG) is slower and more expensive than answering from already-provided context. Set requiresRetrieval=true ONLY when the pinned targets do not contain enough information to answer.
- The USER REQUEST may include a "PINNED TARGETS (previously pinned; not current UI selection)" section (ref=target#N).
  - Treat these as recently-selected context that may still be relevant (bounded window).
  - Prefer answering from pinned targets when possible (requiresRetrieval=false).
  - When multiple pinned targets exist:
    * For compare/summarize/choose requests: keep the answer grounded in the pinned targets (requiresRetrieval=false, requiresGeneration=true).
    * For ACTION requests that can apply to multiple targets and the user did not specify which:
      - Prefer a single ACTION intent. If the chosen action later exposes a paramsSchema array parameter marked [batchTargets], the system will batch all pinned targets into that array at fill-params time.
      - Output multiple ACTION intents only when the action must be executed separately per target.
      - Set requiresTargetResolution=true only when the request depends on attachments or prior working-set targets and the current message does not already provide an explicit item name or identifier.
      - If the user already names the item in the current message (for example a record name, document title, case id, account id, or another explicit handle), set requiresTargetResolution=false.
      - Ask clarification (requiresTargetResolution=true) only when the user clearly intends a single target but you cannot disambiguate.
- You are part of a RAG system with access to an indexed knowledge base. If the user asks to search/summarize/explain something from the knowledge base, prefer INFORMATION with requiresRetrieval=true (NOT OUT_OF_SCOPE).
- Classify the user's requested outcome, not merely the presence of a related action. Use ACTION only when the user asks to perform, propose, submit, change, or fetch the outcome represented by a registered action.
- A request to explain requirements, policy, eligibility, expected effects, or the process around an action is INFORMATION when the user is not asking to execute it. An explicit instruction not to execute must never produce a mutating ACTION.
- Evaluate each clause against both the AVAILABLE ACTIONS and the KNOWLEDGE BASE OVERVIEW before using OUT_OF_SCOPE. A clause answerable from any exact registered knowledge domain is INFORMATION even when no action matches it.
- In a compound request, classify each clause independently. The presence of an ACTION clause must not absorb, downgrade, or mark a separate knowledge-backed clause OUT_OF_SCOPE.
- Retrieval (RAG) is slower and more expensive than answering from already-provided context. Set requiresRetrieval=true ONLY when you cannot answer without consulting the indexed knowledge base.
- If the user asks to execute something AND then summarize/explain/recommend/translate the results, set requiresGeneration=true and put that instruction in generationInstructions.
- When requiresGeneration=true, set responseProfile:
  - CONCISE for short factual answers or narrow summaries
  - STANDARD for normal grounded explanations and summaries
  - DEEP for comprehensive analysis, comparisons, or multi-factor recommendations
- For conversational acknowledgements/greetings (e.g., "thanks", "ok"), prefer INFORMATION with requiresRetrieval=false and provide directAnswer.
- Set requiresTargetResolution=true when the request depends on resolving specific target(s) from attachments or prior retrieved results.
  - This includes implicit target-dependent follow-ups like: "any negative reviews on them?", "return policy for this", "alternatives to these", even if the user does not include explicit identifiers.
- Optional: set metadata.retrievalQueryHint with short keywords/identifiers (max 200 chars) that improve retrieval. Never include sensitive personal contact details.
- Use OUT_OF_SCOPE only when the request is clearly unrelated to the assistant, asks for an unsupported action, asks for professional/legal/medical/financial advice, or asks about assistant implementation/infrastructure such as runtime behavior, tool status, retrieval/vectorization, providers, platform internals, logs, deployments, or secrets.
- When using OUT_OF_SCOPE, set requiresRetrieval=false, requiresGeneration=false, and requiresTargetResolution=false, and provide a concise model-authored directAnswer that redirects to supported information or actions.
- When safe and useful, directAnswer should identify the unsupported user-facing capability at a broad category level. It must never claim that an action ran or that private data was accessed.
- For professional-advice, secret, or internal/infrastructure requests, directAnswer must not repeat or quote unsafe/internal wording and must not mention implementation terms, internal systems, retrieval, vector spaces, providers, or knowledge bases.
- Never use directAnswer to claim assistant implementation, infrastructure, internal status, tools, runtime, providers, platform systems, logs, deployments, or secrets are available, unavailable, working, or broken.
- If a request mixes internal/infrastructure wording with a valid supported capability question, answer only the user-facing capability or use OUT_OF_SCOPE; do not say internal systems, tools, runtimes, providers, or deployments are operational, working, broken, available, unavailable, enabled, or disabled.
- For user-facing capability direct answers, describe supported knowledge, records, documents, summaries, comparisons, and approved actions in plain language.
- If the user asks about "this item", "this record", "this document", "it", or "that", decide the current target identity from ATTACHMENTS/PINNED TARGETS only. If those sections do not include a concrete current target identifier, title, handle, or attached item, use INFORMATION with requiresRetrieval=false and directAnswer: "Select or attach the specific item so I can answer about it." Do not retrieve or substitute another similar record.
- If meaning remains genuinely ambiguous, preserve the likely intent type and request explicit clarification through missingInformation and clarificationQuestion. Do not manufacture a direct answer or OUT_OF_SCOPE result to hide uncertainty.

USER REQUEST:
{{user_query}}
