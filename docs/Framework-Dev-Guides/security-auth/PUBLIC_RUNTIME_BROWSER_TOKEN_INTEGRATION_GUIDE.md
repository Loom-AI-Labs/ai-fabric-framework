# Public Runtime Browser Token Integration Guide

Status: current branch guide (2026-04-07)

This guide explains the opt-in public-runtime posture:

- browser -> public runtime
- runtime -> private connector

Use this mode only when you deliberately want browser-direct chat integration.

Related guides:

- `PRIVATE_RUNTIME_CUSTOMER_INTEGRATION_GUIDE.md`
- `RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md`
- `../../max-mode-widget/docs/WIDGET_AUTH_MODES_AND_CUSTOMER_INTEGRATION_PLAN.md`

---

## 1) When to use this mode

Use public runtime when:

- you need a low-friction embed
- you cannot rely on a customer backend for every chat call
- you accept browser-held short-lived runtime tokens

Do not use this mode just because a runtime URL exists.

Public runtime is opt-in.

---

## 2) Two supported public sub-modes

### 2.1 Anonymous public chat

Use when:

- there is no logged-in user
- the chatbot is limited to low-risk read-only help

Flow:

1. Browser calls runtime bootstrap endpoint.
2. Runtime validates bootstrap origin and abuse limits.
3. Runtime issues a short-lived anonymous bearer token.
4. Browser calls runtime with that token.

The browser never self-issues anonymous identity.

### 2.2 Authenticated public chat

Use when:

- the embedding site has a logged-in user
- the site or backend can issue a browser-safe short-lived token

Flow:

1. Trusted issuer creates a short-lived token.
2. Browser presents that token to runtime.
3. Runtime validates signature, issuer, audience, expiry, and scopes.

---

## 3) Public bootstrap endpoint

Current bootstrap route:

- `POST /api/public/chat/session`

What it does:

- validates origin
- enforces anonymous bootstrap rate limits
- issues a short-lived anonymous token

What it does not do:

- create privileged identity
- bypass authz
- make the connector public

If bootstrap is enabled, configure:

- signing key
- allowed origins
- TTL
- scope policy

Avoid:

- `allow-missing-origin=true` unless your embedding environment truly cannot send `Origin`

---

## 4) Token expectations

### 4.1 Anonymous token

Expected claims include:

- `sub=anon:<session-id>`
- `subjectType=ANONYMOUS_SESSION`
- `authMode=PUBLIC_RUNTIME_ANONYMOUS`
- `callerType=PUBLIC_BROWSER`
- `sessionId`
- optional deployment/customer defaults
- `iss`
- `aud`
- `exp`
- `scopes`

### 4.2 Authenticated public token

Expected claims include:

- `sub`
- `subjectType=END_USER`
- `authMode=PUBLIC_RUNTIME_AUTHENTICATED`
- `callerType=PUBLIC_BROWSER`
- optional `sessionId`
- optional `customerId`
- optional `tenantId`
- `iss`
- `aud`
- `exp`
- `scopes`

---

## 5) Required runtime configuration

At minimum:

- `ai.fabric.runtime.auth.public-tokens.signing-key`
- `ai.fabric.runtime.auth.public-tokens.accepted-issuers`
- `ai.fabric.runtime.auth.public-tokens.accepted-audiences`

For anonymous bootstrap:

- `ai.fabric.runtime.auth.public-tokens.bootstrap.enabled=true`
- `ai.fabric.runtime.auth.public-tokens.bootstrap.allowed-origins`

Recommended:

- keep anonymous scopes small
- keep authenticated scopes on an allowlist
- set a short TTL

---

## 6) Security rules

Public runtime must fail closed when:

- token is missing
- token signature is invalid
- issuer is not allowed
- audience is not allowed
- token is expired
- token scopes exceed configured policy

Anonymous public chat should stay limited to low-risk scopes such as:

- `chat:query`
- `chat:suggestions`
- `chat:conversations`

Do not use anonymous public chat for:

- customer-specific order history
- account data
- privileged actions

---

## 7) Widget expectations

For widget integrations:

- use `integrationMode=public-runtime-anonymous` for anonymous bootstrap
- use `integrationMode=public-runtime-authenticated` for browser-safe authenticated tokens
- do not rely on browser `userId` or `ownerId`
- treat runtime as the external chat and operational read surface
- do not call the connector directly

If the platform public provisioning API exposes:

- `integration.publicRuntimeBootstrapUrl`
- `integration.publicRuntimeAuthorizationHeader`
- `integration.publicRuntimeTokenScheme`
- `integration.publicRuntimeTokenIssuerHint`
- `integration.publicRuntimeDefaultAudience`
- `integration.preferredAuthContextUrl`

use those fields instead of guessing.

---

## 8) Example anonymous bootstrap

```http
POST /api/public/chat/session HTTP/1.1
Host: runtime-dep-example.up.railway.app
Origin: https://store.example
Content-Type: application/json

{
  "sessionId": "anon-storefront-001"
}
```

Response:

```json
{
  "success": true,
  "tokenType": "Bearer",
  "token": "<runtime-public-token>",
  "authMode": "PUBLIC_RUNTIME_ANONYMOUS",
  "subjectType": "ANONYMOUS_SESSION",
  "sessionId": "anon-storefront-001",
  "expiresAt": "2026-04-07T12:00:00Z"
}
```

---

## 9) Example authenticated browser call

```http
POST /api/chat/me/query HTTP/1.1
Host: runtime-dep-example.up.railway.app
Authorization: Bearer <short-lived-public-runtime-token>
Content-Type: application/json

{
  "query": "What is your return policy?"
}
```

Do not send:

- request `userId`
- request `ownerId`
- prefer the `/api/chat/me/*` surface for verified browser-token callers

Use the response `authContext` as the effective identity signal.

For auth smoke verification, call:

```http
GET /api/chat/me/auth-context HTTP/1.1
Host: runtime-dep-example.up.railway.app
Authorization: Bearer <short-lived-public-runtime-token>
```

The response should show the effective public auth mode, subject type, session id, deployment scope, and any compatibility warnings. When available from the platform public provisioning API, use `integration.preferredAuthContextUrl` for this probe rather than guessing the path.

---

## 10) Verification checklist

Must prove:

- anonymous bootstrap enforces allowed origins
- anonymous bootstrap rate limiting works
- anonymous token is short-lived
- authenticated token validation enforces issuer and audience policy
- missing token fails closed
- connector remains private
- runtime-backed connector overview and health remain available to admins without exposing direct connector endpoints
