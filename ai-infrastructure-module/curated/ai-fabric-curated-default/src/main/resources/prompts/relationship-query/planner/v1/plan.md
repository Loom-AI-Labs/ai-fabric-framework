Analyze the user's request using the provided entity schema. Produce a JSON payload with:
- primaryEntityType (snake-case)
- candidateEntityTypes (array)
- relationshipPaths (array of {fromEntityType, relationshipType, toEntityType, direction, optional, conditions})
- directFilters (map of entity -> array of filters)
- relationshipFilters (map)
- needsSemanticSearch (boolean)
- queryStrategy ("RELATIONSHIP", "SEMANTIC", or "HYBRID")
- confidence (0.0 - 1.0 decimal)
- semanticQuery (string)

Guidelines:
- candidateEntityTypes MUST always include the primaryEntityType.
- Each element inside directFilters/relationshipFilters MUST be an array of objects shaped like {"field":"entity.field","operator":"GREATER_THAN","value":123}. Valid operators: EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, BETWEEN, IN, LIKE.
- relationshipPaths[].relationshipType MUST be the relationship field name exactly as shown under "Relationships" in the schema (e.g., "brand", "destinationAccount", "sourceAccount", "author").
- relationshipPaths[].conditions follows the exact same object structure (arrays of filter objects).
- Use fully-qualified field names such as "transaction.amount" or "destinationAccount.region".
- When a predicate needs to compare two entities (e.g., "same counterparty"), set the filter value to "<entity-slug>.<field>" (example: {"field":"ownerName","operator":"EQUALS","value":"destination-account.ownerName"}).
- When the request lists multiple acceptable values for the same field (e.g., "Nike or Adidas"), prefer the IN operator with an array of values.
- Use the exact field names shown in the schema (e.g., "creationDate", "author.fullName"); do not invent shorthand names like "date" or "author".
- NEVER copy literal values from the example plans. Examples are illustrative only.
- Only include literal filter values that are explicitly stated in the user's query (except for enumerated constants defined in the schema, e.g., statuses).
- For broad list queries like "find all <entity>" or "list all <entity>", return empty filters unless the user explicitly requests constraints.
- Do NOT emit raw strings, bare values, or shorthand expressions for any filter/condition.
- If the user mentions a concept that is not represented as a schema field, do NOT invent a new field. Either omit that constraint or map it to an existing field (commonly "name") if appropriate.

Output requirements:
- Return ONLY a single-line minified JSON object (no markdown, no commentary, no leading/trailing text, no newlines).
- Keep the JSON as small as possible: omit optional keys when empty/unknown (e.g., relationshipFilters, metadataFilters, context).
- Omit filter "entityType" when it can be inferred from its parent map key (e.g., directFilters.product).

Schema:
{{schema}}

User Query: "{{user_query}}"

Example plans:
{{examples}}
{{feedback_section}}
