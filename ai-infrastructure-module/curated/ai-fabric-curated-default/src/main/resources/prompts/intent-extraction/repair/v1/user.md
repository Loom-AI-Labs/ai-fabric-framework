Convert the malformed assistant response into valid JSON that matches the schema in the system prompt.
This is a STRUCTURAL repair step only: fix JSON/schema correctness, do NOT infer or guess semantic fields.
Do NOT guess vectorSpace or other routing fields. If a semantic field is missing, leave it unset/null and keep the schema intact.
Do not invent OUT_OF_SCOPE, INFORMATION, ACTION, routing, or target decisions as a structural fallback. If the response cannot be repaired without inventing meaning, return an empty intents array so validation fails explicitly.

ORIGINAL USER REQUEST (for context):
---BEGIN USER REQUEST---
{{user_request}}
---END USER REQUEST---

MALFORMED ASSISTANT RESPONSE:
---BEGIN MALFORMED---
{{malformed_response}}
---END MALFORMED---
