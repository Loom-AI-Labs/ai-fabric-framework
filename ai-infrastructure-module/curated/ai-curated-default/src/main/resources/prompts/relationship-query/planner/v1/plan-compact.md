Return ONLY a valid JSON object (no markdown, no commentary).
Output MUST be a single-line minified JSON string (no newlines).

Keep the JSON extremely small (hard goal: <= 350 characters).
Emit ONLY the minimum required keys (omit everything else):
{"primaryEntityType":"...","candidateEntityTypes":["..."],"relationshipPaths":[],"directFilters":{},"confidence":0.7}

IMPORTANT shape rules:
- directFilters / relationshipFilters / metadataFilters MUST be JSON objects mapping entityType -> array of filter objects.
- A filter object shape is: {"field":"<fieldName>","operator":"EQUALS","value":<value>,"entityType":"<entityType>"}
- For cross-entity comparisons, set value to "<entity-slug>.<field>" (example: "destination-account.ownerName").
- To keep output small, omit "entityType" when it can be inferred (e.g., filters under directFilters.product).
- If unsure about relationships or filter fields, return empty relationshipPaths and empty directFilters.
- If the plan would require more than ONE relationshipPath, set relationshipPaths to [] and only use directFilters on the primary entity.
- If a constraint requires cross-entity equality and you cannot express it concisely, omit it (prefer a smaller valid plan over truncation).
- candidateEntityTypes MUST include primaryEntityType.
- Never invent entity types not listed below.

Schema:
{{schema}}

{{allowed_entity_types_line}}User Query: "{{user_query}}"
