# Security And Access Policy

AI Fabric integrations should be secure by construction: retrieval and actions must use the current
authenticated subject, not user-supplied tenant or account identifiers.

## Core Rules

- Derive `tenantId`, `userId`, and account scope from server-side auth context.
- Store access metadata with indexed documents.
- Apply metadata filters or access policies before user-visible retrieval.
- Do not expose raw vector admin operations to public users.
- Side-effect actions require explicit confirmation.
- Public anonymous actions must opt in with `anonymousAllowed = true`.

## Retrieval Metadata

Store scope metadata with vectors:

```java
Map<String, Object> metadata = Map.of(
    "tenantId", currentTenantId,
    "ownerId", currentUserId,
    "visibility", "team");
```

Then build retrieval requests from trusted server context:

```java
RAGRequest request = RAGRequest.builder()
    .query(userQuery)
    .entityType("knowledge-document")
    .filters(Map.of("tenantId", currentTenantId))
    .build();
```

Provider support differs. Verify that your chosen vector provider can preserve the filter semantics
your app needs.

## Actions

Action handlers should use current account context:

```java
public Map<String, Object> execute(Map<String, Object> params) {
    Account account = accountContext.currentAccount();
    return service.updateAddress(account.id(), params);
}
```

Avoid asking the user for IDs that the backend already has.

## UI Rules

- UI can display evidence and confirmation cards.
- UI should not decide the action by keyword.
- UI should not inject hidden target IDs unless the user selected a visible target.
- UI should show provider/framework errors honestly.

## What To Test

- Tenant A cannot retrieve Tenant B documents.
- User A cannot confirm User B pending action.
- Missing auth context fails closed.
- Public actions are unavailable unless explicitly allowed.
- Action confirmation contains clear domain text.

## Real App Reference

Copy from:

- `examples/real-apps/tenant-knowledge-portal`
- `examples/real-apps/ai-fabric-account-resolver`

