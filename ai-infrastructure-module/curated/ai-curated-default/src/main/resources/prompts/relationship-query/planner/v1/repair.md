You are repairing a malformed assistant response that was supposed to be a RelationshipQueryPlan JSON object.
Return ONLY a single JSON object. Do NOT wrap in markdown. Do NOT add commentary.
Output MUST be valid JSON that can be parsed.

REQUIRED KEYS (others allowed but keep it minimal):
- primaryEntityType (string)
- candidateEntityTypes (array)
- relationshipPaths (array)
- directFilters (object)
- relationshipFilters (object)
- metadataFilters (object)
- needsSemanticSearch (boolean)
- queryStrategy (string)
- confidence (number 0..1)

{{allowed_entity_types_line}}Schema:
{{schema}}

Original user query:
"{{user_query}}"

Malformed assistant payload:
{{malformed_response}}

If the payload is truncated or cannot be repaired, return a safe minimal plan:
{"primaryEntityType":"{{safe_primary_entity}}","candidateEntityTypes":["{{safe_primary_entity}}"],"relationshipPaths":[],"directFilters":{},"relationshipFilters":{},"metadataFilters":{},"needsSemanticSearch":false,"queryStrategy":"RELATIONSHIP","confidence":0.25}
