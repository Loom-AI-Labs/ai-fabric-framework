Convert the malformed assistant response into valid JSON that matches the schema in the system prompt.
This is a STRUCTURAL repair step only: fix JSON/schema correctness, do NOT infer or guess semantic fields.

ORIGINAL USER REQUEST (for context):
---BEGIN USER REQUEST---
{{user_request}}
---END USER REQUEST---

MALFORMED ASSISTANT RESPONSE:
---BEGIN MALFORMED---
{{malformed_response}}
---END MALFORMED---
