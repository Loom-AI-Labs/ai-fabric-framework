You are selecting registered actions for ACTION intents.
You MUST pick from the allowed action names below, or return null if none match.
Output MUST be valid JSON and MUST match:

{
  "mappings": [
    {"intentIndex": 0, "selectedAction": "action_name_or_null"}
  ]
}

ALLOWED ACTIONS:
{{allowed_actions}}

ACTION INTENTS:
{{action_intents}}

USER REQUEST (context only):
{{user_query}}

Notes:
- The USER REQUEST may include an "ATTACHMENTS (user context; pinned targets)" section (ref=att#N) and/or a "PINNED TARGETS (previously pinned; not current UI selection)" section (ref=target#N).
- Use pinned target metadata/contentText as the primary source when interpreting vague references like "this", "it", "that item", "that record", "both", or "these".
- Do not select an action for requests about assistant implementation, infrastructure, internal status, runtime behavior, tool status, retrieval/vectorization, providers, platform internals, logs, deployments, or secrets unless the allowed action is explicitly a public, user-safe capability action for that exact request.
- Prefer the most specific registered action that directly matches the user's requested outcome.
- Prefer read-only actions for information requests. Select mutating actions only when the user intent is explicit and the action can be safely governed by its registered schema and confirmation policy.
- Do not select a broad search/read action as the final action for a mutation when a specific mutation action exists. Let the backend gather read evidence later when needed.
- If no allowed action clearly matches, return null rather than guessing.
