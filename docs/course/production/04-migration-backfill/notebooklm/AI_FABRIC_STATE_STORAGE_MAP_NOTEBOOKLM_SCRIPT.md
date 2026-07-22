# NotebookLM Video Script: State And Storage In An AI Fabric Application

## Production Instruction

Produce an eleven-minute technical video for Java and Spring Boot developers. Use this script as the
complete source. Keep the Support Knowledge Assistant example central and distinguish durable
business state, durable AI workflow state, derived evidence, and ephemeral runtime state.

## Opening

Adding AI Fabric to a Spring Boot app does not create one new universal AI database. A production AI
workflow crosses stores with different owners and recovery rules. Confusing them creates stale
answers, leaked evidence, lost confirmations, and misleading readiness checks.

The central rule is simple: the application database remains business truth. AI Fabric adds
specialized state around it.

## The Storage Map

Describe this diagram:

```text
Application database
  knowledge articles, users, tickets, raw business events
       |
       +--> migration job --> indexing queue --> vector provider
       |                                          derived evidence
       |
       +--> application actions mutate business truth

AI workflow state
  chat sessions and turns
  pending confirmations and action drafts
  behavior insights
  optional registered actions

Runtime state
  caches, model clients, diagnostics snapshots
```

The application database owns article text, tenant ownership, publication status, and internal
notes. The vector provider owns a derived semantic projection with stable entity IDs and retrieval
metadata. It must be possible to rebuild vectors from source truth.

## Initial Backfill

When AI Fabric is introduced to an existing system, rows already exist. A migration job scans them
in bounded batches and records progress. It creates durable indexing work. The indexing worker then
projects allowed content, generates embeddings, and updates the vector provider.

There are therefore three different completion questions: has the source scan completed, has the
queue drained without dead letters, and are the expected vectors retrievable? A production readiness
API should answer all three.

## Continuous Synchronization

Backfill is not live synchronization. After initial migration, trusted create, update, and delete
events keep derived evidence aligned with source truth. An update reuses the stable entity ID. A
delete removes the corresponding vector. Failed synchronization remains visible and retryable.

The next lesson implements that boundary directly.

## Conversation And Action State

Chat sessions and turns are durable application conversations, not vector evidence. Pending actions
and drafts preserve confirmation context. Production systems should use a durable implementation
when a confirmation must survive restart. The browser sends only the new user message; the backend
loads authorized history.

Action execution still belongs to the application transaction. AI Fabric may discover, validate,
and gate an action, but the domain database records the actual business mutation.

## Behavior And Registry State

Raw behavior events remain in the application or event platform and are supplied through
`ExternalEventProvider`. AI Fabric can store derived behavior insights. The optional action registry
stores approved action definitions; it does not replace domain authorization or execution code.

## Ephemeral State

Caches, model clients, and diagnostics snapshots may be rebuilt after restart. Correctness cannot
depend on an ephemeral cache. A model cache may improve connection reuse; it cannot become a hidden
session store or authorization source.

## Incorrect Architecture

An incorrect design writes business updates only to vectors. A user then changes an article in the
source database, but search still returns the old vector. Another design calls a migration job
complete as soon as rows are scanned while the indexing worker is stopped. Both make derived state
look authoritative or ready when it is not.

## Visible Failure

If indexing fails, keep the source row, migration progress, queue failure, and missing vector
observable. Do not return a canned AI answer or silently write a different store. Operators need to
know which stage failed and whether retry is safe.

## Lab Bridge

In PROD-04, you will bind an existing repository, run a bounded admin migration, inspect source,
job, queue, and vector state separately, and prove an idempotent rerun. PROD-05 then applies trusted
create, update, and delete synchronization to keep that evidence current.
