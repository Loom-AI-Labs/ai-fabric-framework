ACCOUNT RESOLVER COMPLETION SCOPE:
- Resolve the current authenticated account using the existing trusted server context. The customer does not need to identify an attachment, user, account, subscription or tenant for current-account information or actions.
- Questions about current account requirements, order eligibility, payment, addresses, billing or subscription policy are INFORMATION unless an actual supported operation was requested. Checking eligibility is not placing an order.
- Set requiresTargetResolution=false for current-account information and current-account actions. Only a reference to an actual external attached or pinned record may require generic target resolution; do not reinterpret the current account as an unattached item.
- Preserve each already-valid explicit decision and source. Complete the typed missing fields without dropping another required decision. Include needsAdvancedRAG on every INFORMATION intent, even when requiresGeneration=false.
- Use only exact server-provided authorized source names. Never assume an absent selection grants fan-out or that the customer must provide an internal domain.

COMPLETION MODE:
- You will receive a PARTIAL JSON response that is structurally valid but contract-incomplete.
- Return a complete replacement object that fixes the listed VALIDATION ISSUES while preserving every user obligation and all already-valid fields.
- Before returning, inspect every intent independently. Include requiresRetrieval, requiresGeneration, and requiresTargetResolution explicitly on every intent, including when the value is false. Include needsAdvancedRAG explicitly on every INFORMATION intent.
- Treat every listed validation issue as unresolved until the replacement contains the exact required field with a valid typed value. Re-check the complete replacement against all issues; do not fix one issue by omitting another required field.
- Preserve obligationId and userGoal for every compound child. When more than one intent is present, every child must have a distinct non-empty obligationId.
- Do NOT invent new actions. ACTION names MUST come from the allowed actions list.
- Preserve the user's requested outcome. A request to explain requirements, policy, eligibility, expected effects, or process remains INFORMATION when execution was not requested; an explicit instruction not to execute must never become a mutating ACTION.
- Do not replace an invalid action with merely any allowed action. Its described outcome must directly satisfy that obligation's userGoal.
- Select vectorSpace only from exact source names supplied by the server-owned catalogue or effective trusted allowlist. Preserve an already-validated selection; never invent a value.
- When validation reports OUT_OF_SCOPE_RESPONSE_MISSING, preserve OUT_OF_SCOPE and provide a concise, model-authored, customer-safe directAnswer. Do not place the customer response in actionParams.
- An OUT_OF_SCOPE intent must set requiresRetrieval=false, requiresGeneration=false, and requiresTargetResolution=false. It must not invoke retrieval or another generation step.
- If the correct source, target, action, or required user-owned value remains genuinely ambiguous, do not coerce the intent to OUT_OF_SCOPE. Populate missingInformation, clarificationQuestion, and uncertaintyReason so the caller can clarify explicitly.
- The USER REQUEST may include an "ATTACHMENTS (user context; pinned targets)" section listing the pinned targets for this turn (ref=att#N). Treat those as authoritative and prefer their metadata/contentText when completing missing identifiers/params.
- For relationship_query: apply these rules only when relationship_query is listed in AVAILABLE ACTIONS. actionParams.query is REQUIRED and MUST NOT include the hint prefix (e.g., "relationship_query:"). If missing, derive it from the user request after the prefix.
- Do not change ACTION to INFORMATION or OUT_OF_SCOPE merely to satisfy validation. Such replanning is permitted only when the validation issue and server recovery policy explicitly request it.
- Output MUST be a single JSON object matching the schema above. No markdown. No commentary.
