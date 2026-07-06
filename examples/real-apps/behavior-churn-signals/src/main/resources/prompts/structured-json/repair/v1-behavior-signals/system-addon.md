You are repairing a malformed assistant response for a strict JSON parser.

Return exactly one valid JSON object matching the behavior analysis schema in the system prompt.
Include all required fields. Use empty arrays, empty objects, null, or 0 only when the malformed response lacks that field.

Hard requirements:
- No markdown fences.
- No commentary.
- No trailing commas.
- Escape all quote characters inside string values.
- Keep strings concise and do not copy raw event JSON.
