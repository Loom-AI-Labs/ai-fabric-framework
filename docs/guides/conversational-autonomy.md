# Conversational autonomy

An ordinary application can opt into the Framework goal runner without creating
a private authoring service, a second endpoint or an application-owned model loop.
The integration runs after security, access, policy and conversation enrichment,
and before ordinary intent extraction. Existing explicit workflow bindings keep
precedence. It applies to GENERAL conversational requests, not fixed task or
specialist contracts that already have their own execution binding.

```yaml
ai:
  orchestration:
    autonomy:
      enabled: true
      default-enabled: true
      max-elapsed-seconds: 120
      segment-seconds: 30
      max-actions-per-segment: 8
      max-writes-per-segment: 3
      max-model-calls-per-segment: 12
      max-corrections-per-segment: 3
      max-consecutive-no-progress: 8
      max-consecutive-planner-failures: 4
      replan-non-authority-read-failures: true
```

The default is disabled. Once enabled, an exact request mode `autonomous` opts in
and `standard` opts out. These execution choices reuse the configured default
mode's effective capabilities; they do not grant additional actions or sources.
If `default-enabled` is false, only explicit autonomous requests use this path.
Zero writes per segment makes the effective autonomous tool catalogue read-only.

## Execution

The model chooses an authorized action, observes its approved evidence, then
chooses the next step or a customer-facing completion. Segment allowances yield
and continue within the same request; they are not a fixed total-call limit.
Caller expiry, the root elapsed deadline, real provider admission and funding,
turn cancellation and no-progress limits remain enforced. Failed READs with
explicit non-authority validation/not-found codes may be replanned by the model;
authorization failures and uncertain writes are not retried as ordinary reads.

`ai_search_knowledge` exposes the existing authorized RAGProvider as a READ tool.
The planner sees the effective source catalogue, never an unrestricted search.
The provider still receives the caller identity and source restrictions and
retains document authorization, hybrid/reranking policy and retrieval budgets.
Each knowledge READ uses its evidence count and character limits. Multiple
iterative READs can accumulate approved observations within the SDK's bounded
workflow context; this is distinct from one ordinary RAG generation call.
An explicitly configured PARALLEL_ACTIONS_AND_RAG policy remains a completion
requirement after an executed business READ. Ordinary model-led choices do not
require asking visitors for internal knowledge domains.

Automatic/Selected action evidence continues to use each handler's approved
post-action facts. Failed receiver data is not copied into observations. An
executed write remains executed if evidence projection or narration fails;
the runner never replays it to manufacture a better answer.

## Conversation and consent

History and explicit attachments enter through the normal conversation pipeline.
Existing pending-action storage handles write confirmation. A new proposal ends
the active request and waits for actual user consent. A later confirmation or
rejection follows the existing semantic confirmation lifecycle; it does not
silently resume an abandoned multi-step goal or bypass required business input.
Writes whose declared policy needs no confirmation may execute normally.

This is explicitly active-request assistance, with a request-local checkpoint
journal and operation identities. It is not background execution or recovery
after transport/process loss. Durable goal hosts remain separate and unchanged;
there is no fallback from required durable storage to this local journal.
External business effects survive a lost response and must not be blindly
replayed by the application or user. Use receiver idempotency for writes.

## Observation

The normal response includes `workflow` with state, actual usage, bounded recent
step receipts and stop diagnostics. Approved action data and document sources use
the established response fields so applications can retain cards and sources.
No raw credentials, receiver errors or private execution journal are exposed.
Use a full conversational application test, not just a successful mode flag, to
qualify tool choice, useful answers, confirmations and ownership isolation.

## Source qualification

25 November 2026, source candidate 0.8.71: the 33-module Framework reactor passed
locally. After final follow-up-reference and receipt presentation changes, the
core/default-prompt install passed again with 1,306 core tests and 17 curated
prompt tests. Release source guards passed. No provider inference was required
for these deterministic tests. Hosted app quality and immutable Central
availability are separate release gates, not established by this source record.
