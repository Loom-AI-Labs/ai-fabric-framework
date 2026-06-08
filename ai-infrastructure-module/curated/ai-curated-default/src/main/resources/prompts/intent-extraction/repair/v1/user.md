Convert the malformed assistant response into valid JSON that matches the schema in the system prompt.
This is a STRUCTURAL repair step only: fix JSON/schema correctness, do NOT infer or guess semantic fields.
Do NOT guess vectorSpace or other routing fields. If a semantic field is missing, leave it unset/null and keep the schema intact.
If the assistant response cannot be repaired into a valid schema, choose a safe default (e.g., OUT_OF_SCOPE with neutral confidence).

ORIGINAL USER REQUEST (for context):
---BEGIN USER REQUEST---
{{user_request}}
---END USER REQUEST---

MALFORMED ASSISTANT RESPONSE:
---BEGIN MALFORMED---
{{malformed_response}}
---END MALFORMED---
