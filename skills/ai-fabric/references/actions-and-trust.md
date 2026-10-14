# Actions And Trust

## Action Shape

Expose a narrow application capability through the public action contracts. Use typed parameters, useful descriptions, bounded results, and application-owned services. Keep model reasoning separate from authorization and execution.

- `@AIAction` declares the capability and its model-facing contract.
- `@ActionAllowed` performs application authorization.
- `@ActionConfirmation` defines confirmation when the effect requires it.
- `@ActionExecute` invokes the application-owned effect.
- `ActionResult` returns a concise message plus bounded structured data suitable for a client renderer.

Inspect the current annotations and result contracts in [`ai-fabric-core`](../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action) and the account resolver's maintained handlers in [`ai-fabric-account-resolver`](../../../examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/action/handler).

## Read And Write Boundaries

- Read actions may query current application facts that are not vectorized. They are governed by the action catalog, action allowlist, identity, tenant/deployment scope, and application authorization.
- Retrieval is a separate evidence path governed by retrieval allowlists and provider filters. Do not require a read action to name a vector space unless the action itself intentionally performs retrieval.
- Write actions require trusted resource resolution, authorization, validation, idempotency where applicable, and confirmation/review according to declared policy.
- The model proposes typed parameters. The application validates and executes; it does not trust model-supplied ownership, tenant IDs, hidden resource IDs, prices, roles, or policy decisions.
- User-entered values may proceed under an explicitly configured validation posture, but hidden/system-owned targets must come from trusted context or fail closed.

## Connectors

Use `ai-fabric-actions-connector` when execution belongs behind an HTTP/service connector rather than in-process. Keep credentials server-side, use an allowlisted operation catalog, authenticate both sides, propagate idempotency and correlation identifiers, and normalize remote failures without exposing secrets.

Read [Actions Connector And Relay](../../../docs/Framework-Dev-Guides/actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md) and [Actions And Confirmation Interceptors](../../../docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md) before introducing a connector-backed effect.

## Semantic Integrity

The model chooses among the actions the server permits using validated structured output. Do not select an action or fill its semantic parameters with phrase matching. If output is incomplete, use the framework's configured clarification or bounded repair flow. If a required framework contract is absent, report the gap; do not add a controller that bypasses orchestration merely to make the scenario pass.

## Verification

Test allowed and denied callers, missing trusted targets, invalid and unknown fields, confirmation accept/decline, duplicate submission, connector timeout/failure, result projection, and an unrelated request that executes no action. Verify that a read action can return structured facts without a vector-space declaration and that retrieval authorization remains unchanged.
