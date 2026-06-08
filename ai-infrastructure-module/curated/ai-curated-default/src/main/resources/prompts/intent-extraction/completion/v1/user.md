You are completing an intent extraction response.
Your job is to output corrected JSON that satisfies all VALIDATION ISSUES while preserving the user's meaning.

ALLOWED ACTIONS (do NOT invent):
{{allowed_actions}}

VALIDATION ISSUES (must be resolved if possible):
{{validation_issues}}

USER REQUEST:
{{user_request}}

PARTIAL JSON (to complete):
{{partial_json}}

Notes:
- If the USER REQUEST includes attachments, use their metadata/contentText as the primary source for completing identifiers instead of guessing.
- Optional actionParams may be completed when their parameter description says they improve presentation, confirmation copy, or safe display and the value can be faithfully derived from the USER REQUEST or authoritative attachments.
- Presentation-only optional params must not invent executable identifiers, prices, availability, discounts, or order data.
- For catalog/search actions with a valid required `query` parameter, fill `query` from the shopper's product-search phrase, including explicit category, price, size, or preference words when no dedicated structured parameter exists.
