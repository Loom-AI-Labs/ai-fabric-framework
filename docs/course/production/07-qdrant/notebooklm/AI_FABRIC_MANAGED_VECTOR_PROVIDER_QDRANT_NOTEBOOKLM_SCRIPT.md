# NotebookLM Video Script: Managed Vector Providers And Qdrant Lifecycle

## Production Instruction

Produce a ten-minute technical video for Java and Spring Boot developers. Use only this script.
Keep AI Fabric and the Support Knowledge Assistant central. Explain provider substitution through
the AI Fabric vector contract, then explain Qdrant collections, dimensions, metadata filtering,
lifecycle parity, readiness, durability, and visible failure.

## Opening

Moving from an embedded vector store to a managed provider should not force application services to
rewrite retrieval, migration, Data Sync, tenant policy, or quality tests. The provider changes. The
application contract should remain stable.

In PROD-07, the Support Knowledge Assistant keeps its source database, ONNX embedding model,
tenant rules, stable evidence IDs, and golden RAG cases. It replaces the selected
`VectorDatabaseService` implementation with Qdrant.

## Provider Substitution Boundary

Describe this diagram:

```text
controllers and application services
       |
       v
AI Fabric RAG, migration, and Data Sync
       |
       v
VectorDatabaseService contract
       |
       +----> local Lucene profile
       |
       +----> Qdrant profile -> Qdrant REST or gRPC client -> Qdrant
```

Application business code does not import a Qdrant client. It depends on AI Fabric contracts. A
Spring profile selects the provider for a deployment environment.

## The Contract Is Broader Than Similarity Search

AI Fabric's vector contract includes more than storing an embedding and finding nearest neighbors.
It also describes capabilities needed by real application and operational workflows:

- exact store, update, fetch, and delete behavior;
- metadata-filtered similarity search;
- metadata-filtered paged scans;
- counts and existence checks;
- clear-by-entity-type behavior;
- provider diagnostics and readiness;
- batch lifecycle operations;
- durability and production-profile safety.

The contract advertises search filtering and scan filtering separately. A provider must not claim
portable filtering if it cannot preserve the requested exact-match semantics safely.

## Embedding Dimensions Are A Storage Contract

The Support Knowledge Assistant keeps the local ONNX embedding model at 384 dimensions. Qdrant
collections are created with that vector size and cosine distance.

Changing to an embedding model with another dimension is not a harmless configuration toggle for
an existing collection. Use a separately named collection or a controlled reindex. Mixed vector
dimensions must not be written into one collection.

## Collections, Prefixes, And Stable IDs

The course profile uses a collection prefix so its resources do not collide with unrelated
applications on the same Qdrant endpoint. Each entity type maps to a scoped collection name.

The prefix is operational isolation, not tenant authorization. Both course tenants can share the
knowledge-article collection because every record carries approved metadata and every request uses
verified tenant filters plus application post-hit checks.

Stable application entity IDs produce stable vector IDs. Updating an article replaces the same
logical point. Deleting the article addresses that point. Random IDs on each update would leave
stale versions retrievable.

## Native Metadata Filtering

Qdrant payload indexes support efficient exact filtering for fields such as AI Fabric's source
handle reference. The adapter translates AI Fabric's portable scalar exact-match subset into
Qdrant payload filters. Readiness diagnostics identify:

- whether search and scan filtering are supported;
- the active REST or gRPC transport;
- the collection prefix;
- required payload-index fields;
- provider durability and consistency behavior;
- any controlled client-side filtering fallback.

A filter translation must fail closed when it cannot preserve requested semantics. It must never
widen a tenant-scoped query silently.

## Readiness Is Capability Evidence

Process health only proves that the Spring Boot process started. Provider readiness should also
prove the selected implementation, native execution path, endpoint reachability, collection shape,
dimension, payload-index state, and safe vector counts.

Do not expose API keys in readiness output. Safe diagnostics can show provider name, transport,
scope, capabilities, and counts.

## Lifecycle Parity

Provider migration is complete only when the existing application behavior remains true:

1. Backfill writes all expected vectors.
2. Tenant Blue and Tenant Red retrieve only their approved evidence.
3. A trusted create writes source and vector state.
4. An update keeps the stable ID and replaces old content.
5. Delete removes retrievable evidence and restores expected counts.
6. Admin scans and counts preserve metadata semantics.
7. The PROD-06 golden scorecard passes without changing expected IDs.

Having the expected number of Qdrant points is necessary but insufficient. Quality and access
contracts must pass unchanged.

## Durability And Source Ownership

Qdrant stores durable provider state, but vector evidence remains derived and rebuildable. The
application database is the source of truth. A network response can be lost after Qdrant accepts a
write, or the source transaction can fail after an external write. Stable IDs, idempotent retries,
queue state, reconciliation, and reindex procedures remain necessary.

Local Docker Qdrant uses a named volume so restart tests exercise durable state rather than process
memory. Qdrant Cloud uses the same application contract, with endpoint and API key supplied only by
the runtime secret store.

## Visible Failure And No Hidden Fallback

The release smoke starts an application against an unreachable Qdrant endpoint. Source seeding can
succeed because the application owns those rows. Indexing must fail visibly because evidence could
not be written.

The application must not switch to Lucene, return a fake indexed count, delete source rows, or leak
endpoint credentials. Provider fallback would make the deployment appear healthy while serving a
different storage system than the operator selected.

## Incorrect Architecture

An incorrect migration injects Qdrant SDK calls into controllers and creates one collection per
browser session. Another design trusts a client-supplied tenant payload or accepts point counts as
proof that tenant filtering works. These designs bind business code to one provider and weaken
security and operability.

The correct architecture keeps provider details behind AI Fabric, derives scope on the backend,
and reruns the same lifecycle and quality contracts against every selected provider.

## Lab Bridge

In PROD-07, you will add the AI Fabric Qdrant module, start pinned local Qdrant with Docker, select
it through a profile, inspect collection and payload-index readiness, rerun the golden RAG suite,
prove stable create/update/delete behavior, and test an unreachable provider. Qdrant Cloud remains
an optional separately keyed exercise.

## Closing

A managed vector provider is valuable when it changes deployment capability without changing
application meaning. AI Fabric makes that boundary explicit: provider-native operations underneath,
portable lifecycle, access, quality, and readiness contracts above.
