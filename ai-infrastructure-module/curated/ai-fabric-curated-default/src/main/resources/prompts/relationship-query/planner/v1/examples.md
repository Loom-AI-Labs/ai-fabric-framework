Example plan for query "Find all brands":
{
  "primaryEntityType": "brand",
  "candidateEntityTypes": ["brand"],
  "relationshipPaths": [],
  "directFilters": {},
  "relationshipFilters": {},
  "metadataFilters": {},
  "queryStrategy": "RELATIONSHIP",
  "needsSemanticSearch": false,
  "confidence": 0.9,
  "context": {}
}

Example plan for query "Show me blue shoes under $100 from Nike":
{
  "primaryEntityType": "product",
  "candidateEntityTypes": ["product", "brand"],
  "relationshipPaths": [
    {
      "fromEntityType": "product",
      "relationshipType": "brand",
      "toEntityType": "brand",
      "direction": "FORWARD",
      "optional": false,
      "conditions": [
        {"field": "name", "operator": "EQUALS", "value": "Nike", "entityType": "brand"}
      ]
    }
  ],
  "directFilters": {
    "product": [
      {"field": "color", "operator": "LIKE", "value": "%blue%", "entityType": "product"},
      {"field": "price", "operator": "LESS_THAN", "value": 100, "entityType": "product"}
    ]
  },
  "relationshipFilters": {}
}

Example plan for query "Find suspicious wires over $25k routed through the same counterparty":
{
  "primaryEntityType": "transaction",
  "candidateEntityTypes": ["transaction"],
  "relationshipPaths": [
    {
      "fromEntityType": "transaction",
      "relationshipType": "destinationAccount",
      "toEntityType": "destination-account",
      "direction": "FORWARD",
      "optional": false,
      "conditions": [
        {"field": "region", "operator": "ILIKE", "value": "%high-risk%", "entityType": "destination-account"}
      ]
    },
    {
      "fromEntityType": "transaction",
      "relationshipType": "sourceAccount",
      "toEntityType": "origin-account",
      "direction": "FORWARD",
      "optional": false,
      "conditions": [
        {"field": "riskScore", "operator": "GREATER_THAN_OR_EQUAL", "value": 0.7, "entityType": "origin-account"},
        {"field": "ownerName", "operator": "EQUALS", "value": "destination-account.ownerName", "entityType": "origin-account"}
      ]
    }
  ],
  "directFilters": {
    "transaction": [
      {"field": "amount", "operator": "GREATER_THAN", "value": 25000, "entityType": "transaction"}
    ]
  },
  "relationshipFilters": {}
}

Example plan for query "Find all contracts related to John Smith in Q4 2023":
{
  "primaryEntityType": "document",
  "candidateEntityTypes": ["document"],
  "relationshipPaths": [
    {
      "fromEntityType": "document",
      "relationshipType": "author",
      "toEntityType": "user",
      "direction": "FORWARD",
      "optional": false,
      "conditions": [
        {"field": "fullName", "operator": "EQUALS", "value": "John Smith", "entityType": "user"}
      ]
    }
  ],
  "directFilters": {
    "document": [
      {"field": "creationDate", "operator": "GREATER_THAN_OR_EQUAL", "value": "2023-10-01T00:00:00", "entityType": "document"},
      {"field": "creationDate", "operator": "LESS_THAN_OR_EQUAL", "value": "2023-12-31T23:59:59", "entityType": "document"}
    ]
  },
  "relationshipFilters": {}
}

Example plan for query "Show active Nike or Adidas running shoes priced between $80 and $120 available in red or blue":
{
  "primaryEntityType": "product",
  "candidateEntityTypes": ["product", "brand"],
  "relationshipPaths": [
    {
      "fromEntityType": "product",
      "relationshipType": "brand",
      "toEntityType": "brand",
      "direction": "FORWARD",
      "optional": false,
      "conditions": [
        {"field": "name", "operator": "IN", "value": ["Nike", "Adidas"], "entityType": "brand"}
      ]
    }
  ],
  "directFilters": {
    "product": [
      {"field": "status", "operator": "EQUALS", "value": "ACTIVE", "entityType": "product"},
      {"field": "price", "operator": "BETWEEN", "value": 80, "secondaryValue": 120, "entityType": "product"},
      {"field": "color", "operator": "IN", "value": ["red", "blue"], "entityType": "product"}
    ]
  },
  "relationshipFilters": {}
}
