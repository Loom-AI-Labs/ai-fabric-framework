# Empty Read-Action Grounding Sufficiency Regression

## Status

Confirmed against AI Fabric `0.8.5` in a live hosted canary on 2026-09-30.

Implemented on `main` for the `0.8.6` patch release. The implementation uses
the typed `ActionListPayload` contract and the explicit trusted
`ActionGroundingSufficiency` result override. It does not inspect action names,
business fields, or answer text.

This is a framework change request. It is not a request for application-specific
matching, dealership-specific behavior, or answer-prompt compensation.

## Problem

A grounding-eligible read action can execute successfully and return a valid
structured collection with zero items. AI Fabric currently records that result
as `groundingUsable=true`. In `RAG_IF_ACTIONS_INSUFFICIENT` mode, that makes the
empty result appear sufficient and prevents the configured retrieval fallback.

Action transport success and answer-grounding sufficiency are different facts:

- the connector call can be technically successful;
- the empty result can be authoritative for the exact filters; and
- the result can still be insufficient for the rest of a compound request,
  such as asking for the nearest evidence-backed alternative.

## Reproduction

Runtime policy:

```yaml
mode: executor
position: search
readActionResolution:
  planningMode: ITERATIVE
  maxIterations: 2
  maxTotalActions: 2
  ragCooperationMode: RAG_IF_ACTIONS_INSUFFICIENT
```

The action is declared as a grounding-eligible, read-resolution-eligible read
action. The user asks a compound question that requires an exact filtered lookup
and, when no match exists, an indexed alternative.

Observed live result:

```text
action transport result: success
structured collection size: 0
groundingUsable: true
read-action iterations: none
independent retrieval documents: 0
response type: ACTION_EXECUTED
```

Live provider requests from two consecutive post-canary runs:

- `rag-04a33c82-3dee-4232-a950-a5e14fa2a49c`
- `rag-9d3b1db5-97b0-4d71-9224-60fd7b245386`

The resulting answer incorrectly generalized the filtered no-match into an
empty overall inventory. Six tenant- and deployment-scoped indexed documents
were available, and a separate semantic query in the same canary retrieved all
six successfully.

## Expected Behavior

1. Preserve the successful action result as evidence for the exact no-match.
2. Evaluate grounding sufficiency independently from connector/action success.
3. Under `RAG_IF_ACTIONS_INSUFFICIENT`, treat an empty structured collection as
   insufficient when the request still has an unanswered evidence need.
4. Continue the bounded plan and retrieve within the configured vector-space,
   tenant, deployment, iteration, and action limits.
5. Generate from both facts: no exact match and the retrieved alternatives.

An action contract may explicitly declare that an empty result fully answers a
request. The default framework behavior must not infer complete sufficiency
solely from HTTP/action success.

## Required Framework Shape

Introduce a generic structured sufficiency decision between normalized action
evidence and read-action plan completion. Suitable designs include an explicit
normalized evidence state or a declarative empty-result policy, provided that:

- it operates on normalized result structure or explicit contract metadata;
- it is independent of action names and business domains;
- it does not use answer text, field-name heuristics, or prompt string matching;
- it retains the empty result as a valid fact rather than converting success to
  failure; and
- it is honored consistently by single-pass and iterative orchestration.

The implemented API shape is:

- a successful empty `ActionListPayload` is insufficient by default;
- a non-empty typed list continues to use its projected facts;
- `ActionResult.groundingSufficiency=SUFFICIENT` explicitly marks even an empty
  result as authoritative and complete;
- `ActionResult.groundingSufficiency=INSUFFICIENT` explicitly requests another
  configured grounding source even when the payload contains records; and
- the action evidence summary remains available to generation when RAG runs.

The important semantic distinction is
`executionSucceeded != groundingSufficient`.

## Regression Coverage

Framework coverage now includes these cases:

1. Non-empty read result is sufficient and does not force unnecessary RAG.
2. Empty read collection plus an unanswered alternative request invokes RAG in
   `RAG_IF_ACTIONS_INSUFFICIENT` mode.
3. Empty read collection remains available to generation as the authoritative
   exact-filter no-match fact.
4. Explicit authoritative-empty contract can complete without RAG.
5. RAG fallback remains tenant-, deployment-, and vector-space-scoped.
6. Iteration and total-action limits remain enforced.
7. A failed action is still represented as failure, not as an empty success.
8. No domain-specific action names, fields, or text matching are introduced.

Focused verification:

- `ReadActionResolutionServiceTest`
- `ActionResultSerializationTest`
- `ActionConnectorExecutorTest`
- `ConnectorAIActionHandlerTest`

## Acceptance Evidence

The downstream canary is green when the same compound request produces:

- the successful zero-item read action;
- at least one independent retrieval document;
- an answer that distinguishes `no exact match` from `no inventory`; and
- no regression in exact-filter, semantic-RAG, tenant isolation, or governed
  write behavior.

Prompt changes must not be used as acceptance evidence unless retrieval evidence
actually reaches generation.
