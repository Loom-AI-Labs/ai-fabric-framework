Repair the malformed assistant response into valid JSON only.

Preserve the intended meaning where obvious, but prioritize syntactic validity and the required schema.
If a field cannot be recovered safely, use the schema-safe fallback value.

ORIGINAL USER REQUEST:
---BEGIN USER REQUEST---
{{user_request}}
---END USER REQUEST---

MALFORMED ASSISTANT RESPONSE:
---BEGIN MALFORMED---
{{malformed_response}}
---END MALFORMED---
