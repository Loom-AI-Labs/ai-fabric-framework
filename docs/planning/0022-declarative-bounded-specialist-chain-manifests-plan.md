# ADR 0022 - Declarative Bounded Specialist Chain Manifests

- **Status:** Accepted and implemented for AI Fabric `0.7.0`
- **Date:** 2026-09-18
- **Framework baseline:** AI Fabric `0.6.1`
- **Target release:** AI Fabric `0.7.0`
- **Primary module:** `ai-fabric-execution`
- **Supporting modules:** framework autoconfiguration, integration tests, standalone consumer, and real-app examples
- **Primary adoption:** Immutable deployment-time definition of bounded read-only specialist teams
- **Out of scope:** General workflow DSLs, runtime graph mutation, recursive agents, write-capable chain workers, arbitrary expressions, and compatibility shims

Related current contracts:

- [Specialist Manifest Authoring Guide](../Framework-Dev-Guides/application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md)
- [Bounded Multi-Specialist Chains](../Framework-Dev-Guides/application-patterns/BOUNDED_MULTI_SPECIALIST_CHAINS.md)
- [LoomAI AI Fabric 0.6 Chain Migration Runbook](../Framework-Dev-Guides/application-patterns/LOOMAI_AI_FABRIC_0_6_CHAIN_MIGRATION_RUNBOOK.md)
- [Agentic Application Guide](../Framework-Dev-Guides/application-patterns/AGENTIC_APP_GUIDE.md)
- [Durable Read-Only Specialist Jobs](../Framework-Dev-Guides/application-patterns/DURABLE_READ_ONLY_SPECIALIST_JOBS.md)
- [AI Fabric 0.6.1 Release Notes](../release-notes/0.6.1.md)
- [AI Fabric 0.7.0 Release Notes](../release-notes/0.7.0.md)
- [LoomAI AI Fabric 0.7 Declarative Chain Migration Runbook](../Framework-Dev-Guides/application-patterns/LOOMAI_AI_FABRIC_0_7_DECLARATIVE_CHAIN_MIGRATION_RUNBOOK.md)

## 1. Decision Summary

Add an official, versioned `SpecialistChain` YAML/JSON resource to AI Fabric.

The new resource must compile into the existing immutable
`SpecialistChainDefinition<JsonNode>`, register in the existing
`SpecialistChainRegistry`, and execute through the existing
`SpecialistChainGateway`. It must not introduce a second chain engine, a
different durable state model, or a private deployment-platform workflow
language.

The target lifecycle is:

```text
immutable AI Fabric execution resource bundle
  -> strict YAML/JSON parsing
  -> specialist/schema/prompt compilation
  -> chain manifest structural validation
  -> exact manager and worker resolution
  -> bounded input-mapping and result-projection compilation
  -> immutable SpecialistChainDefinition<JsonNode>
  -> source-aware SpecialistChainRegistration
  -> existing SpecialistChainRegistry
  -> existing SpecialistChainGateway
  -> existing JDBC checkpoints, replay, cancellation, and recovery
```

The first release supports only:

- one exact-version manifest chain identity;
- one exact-version manager specialist;
- one to eight exact-version worker specialists, subject to existing framework ceilings;
- read-only, non-interactive leaf workers;
- existing `ASK_USER`, `INVOKE_ONE`, `INVOKE_PARALLEL`, `HANDOFF`, and `COMPLETE` directives;
- schema-backed JSON chain input;
- bounded JSON Pointer input selection and target mapping;
- bounded allowlist-based result projection into `SpecialistChainResultProjection`;
- existing chain limits and conversation policy;
- startup-only loading from immutable classpath or mounted resources; and
- separate audit-resource, declarative-semantics, and effective-execution
  hashes compatible with durable replay and changed-definition rejection.

Java-defined chains remain supported. Manifest-defined and Java-defined chains
must share the same registry, validation rules, execution behavior, metrics,
durability, and security boundaries. Duplicate exact chain IDs across either
source fail startup.

V1 intentionally does **not** allow a chain manifest to name application Java
adapters, mappers, or projectors. The manifest path is the complete,
framework-owned declarative subset. If a boundary needs custom Java
transformation or authoritative application logic, the chain remains
Java-defined. Application SPIs remain appropriate for authentication,
authorization, trusted-context construction, storage, validation against a
system of record, and reconciliation; they are not a second orchestration DSL.

## 2. Problem Statement

AI Fabric `0.6.1` supports two complementary capabilities:

1. specialists can be defined through immutable YAML/JSON resources; and
2. bounded multi-specialist chains can be defined through Java beans.

The specialist manifest path already lets a host compose existing modes,
schemas, prompts, vector spaces, registered actions, grounding policy, and
execution limits without adding one Java configuration class per specialist.
The chain path intentionally keeps topology, input mapping, result projection,
limits, and target authority in application-owned Java.

That Java-only chain boundary is safe, but it prevents a managed deployment
system from selecting a reviewed specialist team as immutable deployment
configuration while reusing one application artifact. Every new exact team
currently requires source changes, compilation, artifact publication, and a
new application build even when:

- all manager and worker specialists already exist as validated manifests;
- the input and output contracts are JSON Schema;
- mappings require only bounded field selection;
- result projection requires only approved summaries, facts, and evidence references; and
- the desired topology is already supported by the existing chain engine.

The resulting pressure creates two bad alternatives:

- copy Java chain registration code for every deployment composition; or
- invent a private YAML format outside AI Fabric and bridge it into framework internals.

Neither is acceptable for a public framework. The framework should own one
portable, secure, schema-backed representation of the chain semantics it
already supports.

The requested capability is not arbitrary workflow authoring. It is a
declarative representation of the existing bounded chain contract.

## 3. Current Framework Boundary

### 3.1 Manifest-defined specialists

Current execution resources support:

- `SpecialistSchema`;
- `SpecialistPromptProfile`; and
- `Specialist`.

The loader performs strict parsing, bounded resource-size checks, exact ID
validation, local schema resolution, prompt resolution, extension resolution,
and fail-fast registry publication. A specialist resource compiles into the
same immutable `SpecialistDefinition` used by Java-defined specialists.

Current specialist manifests correctly prohibit:

- Java class names;
- SQL and scripts;
- arbitrary HTTP calls;
- provider credentials;
- trusted identity, tenant, subject, or deployment values; and
- authority scopes.

Requested actions and vector spaces remain requests. Effective capability is
the intersection of the specialist definition, mode policy, deployment
inventory, registries, and trusted caller authority.

### 3.2 Java-defined chains

Current bounded chains require application Java to register:

- `SpecialistChainId`;
- manager `SpecialistId`;
- chain request type;
- `SpecialistChainInputAdapter`;
- closed `SpecialistChainTarget` values;
- one `SpecialistChainTargetInputMapper` per target;
- one `SpecialistChainTargetResultProjector` per target;
- `SpecialistChainLimits`; and
- `SpecialistChainConversationPolicy`.

The model proposes only a validated directive. Java validation remains the
transition authority. Workers remain leaves, and each worker invocation
receives independently resolved trusted authority.

### 3.3 Existing durability and identity

The chain registry computes an immutable content hash over chain identity,
manager and worker definitions, component identities, classes, descriptions,
policies, and limits. AI Fabric `0.6.1` also incorporates resolved manifest
specialist prompt and schema identity into effective specialist hashes.

Durable execution pins the exact chain hash. Changed definitions cannot resume
old work. Existing JDBC execution, leases, replay, cancellation, deadline,
conversation ownership, and access-fingerprint behavior must remain unchanged.

### 3.4 Missing contract

There is no official way to express the existing Java chain definition as a
bounded AI Fabric resource. In particular, there is no framework-owned
declarative contract for:

- manager and worker references;
- chain input projection;
- worker input mapping;
- safe worker result projection;
- target delegation/parallel/handoff flags;
- limits; or
- conversation policy.

This ADR fills only that gap.

## 4. Design Principles

The implementation must follow these principles:

1. **One execution engine.** Manifest chains compile into existing public chain contracts.
2. **One registry.** Java and manifest chain definitions share duplicate detection, hashing, lookup, health, and execution.
3. **Exact identities.** Chains, managers, workers, and schemas use immutable `name@version` references.
4. **Configuration requests capability.** A chain manifest cannot grant identity, authority, actions, vector spaces, provider access, or data access.
5. **Closed topology.** Every worker is listed in the manifest and validated at startup.
6. **No executable configuration.** YAML/JSON contains no classes, expressions, scripts, SQL, arbitrary URLs, or dynamic code.
7. **Schema before provider calls.** Chain and worker inputs are constructed and validated before invocation.
8. **Projection before synthesis.** The manager sees only bounded application-approved worker projections.
9. **Startup immutability.** A semantic change requires a new exact version and normal application restart or redeployment.
10. **Durable drift rejection.** Execution-semantic manifest changes cannot silently resume work created under another execution hash.
11. **Visible failure.** Missing resources, invalid mapping, schema mismatch, provider failure, or projection failure remain explicit failures.
12. **Smallest supported model.** This feature models a bounded chain, not a graph language.
13. **Declarative means declarative.** Manifest chains use only framework-owned,
    schema-validated mapping and projection primitives. They cannot select
    application Java orchestration components by name.
14. **Configuration constrains; intelligence decides.** The manifest closes the
    approved topology, authority, mappings, and limits. The manager model still
    decides which eligible worker or completion path fits the current request.

## 5. V1 Scope And Boundaries

### 5.1 Included in V1

V1 includes:

- a new `kind: SpecialistChain` resource under `apiVersion: ai.fabric/v1`;
- strict YAML and JSON parsing;
- integration with existing execution manifest locations;
- one JSON Schema-backed chain request;
- a declarative manager input adapter;
- declarative worker input mappings;
- declarative bounded result projections;
- compilation into `SpecialistChainDefinition<JsonNode>`;
- existing synchronous and asynchronous gateway operations;
- existing JDBC persistence and recovery;
- safe runtime status and content hashes;
- an authoring/validation API suitable for trusted host applications; and
- one real-app and one standalone-consumer proof.

### 5.2 Explicitly excluded from V1

V1 does not include:

- generic directed graphs;
- loops, cycles, recursion, nested chains, or manager-selected subchains;
- model-created or request-created topology;
- arbitrary conditions or transition expressions;
- JavaScript, SpEL, JQ, JSONata, templating engines, or reflection;
- named Java input adapters, worker mappers, or result projectors in manifests;
- write-capable workers;
- partial-success, quorum, best-effort, or fallback fan-in;
- arbitrary result-object forwarding;
- runtime create/update/delete APIs for chain definitions;
- hot reload of active chain definitions;
- external schema references;
- provider/model selection in chain input;
- identity, tenant, deployment, subject, scopes, or credentials in manifests;
- a manifest format for fixed sequential/parallel plans;
- a manifest format for standalone delegation, handoff, or legacy conversation-manager definitions;
- a general deployment or marketplace contract; or
- automatic migration of Java chain definitions to YAML.

Fixed plans may receive a separate proposal after manifest chains are released
and verified. This ADR must not broaden into a universal workflow language.

Named Java component references were considered and rejected for V1. They
would turn the manifest into fragile bean wiring, make ordinary chains depend
on custom deterministic code, and weaken the goal that a reviewed deployment
bundle can define the supported chain without recompiling an application. A
future proposal must not add them merely as a convenience escape hatch. It
would need to identify a genuine framework boundary that cannot be represented
declaratively and prove that a normal Java-defined chain is insufficient.

## 6. Proposed Resource Contract

Names below are proposed public API names. Implementation review may adjust
package-local names, but the semantics are required.

### 6.1 Resource identity

Add an official resource:

```yaml
apiVersion: ai.fabric/v1
kind: SpecialistChain
metadata:
  name: account-resolution
  version: "1"
  displayName: Account Resolution Team
  description: Coordinates approved account and policy readers.
  labels:
    domain: customer-support
spec: {}
```

Requirements:

- `metadata.name` and `metadata.version` form `SpecialistChainId`;
- the exact ID is immutable;
- labels are descriptive only;
- labels cannot change execution, authority, persistence, or routing;
- duplicate exact IDs across Java and manifest definitions fail startup;
- changing execution-semantic content requires a new exact chain version;
- reusing an exact ID with changed execution semantics is invalid authoring,
  even though the effective hash remains a second fail-closed safety net; and
- changing descriptive metadata changes the audit resource hash but not the
  durable execution hash.

### 6.2 Illustrative complete manifest

The exact serialized field names remain subject to implementation review. The
following shape captures the required semantics:

```yaml
apiVersion: ai.fabric/v1
kind: SpecialistChain
metadata:
  name: account-resolution
  version: "1"
  displayName: Account Resolution Team
  description: Coordinates approved account and policy readers.
  labels:
    domain: customer-support

spec:
  input:
    schemaRef: account-resolution-request@1
    managerMessagePointer: /question
    managerContext:
      - name: accountReference
        valuePointer: /accountReference
      - name: requestChannel
        valuePointer: /channel

  manager:
    specialistRef: account-resolution-manager@1

  targets:
    - specialistRef: account-reader@1
      description: Reads approved account state and subscription facts.
      input:
        type: JSON_POINTER_MAP
        fields:
          - source: CHAIN_INPUT
            sourcePointer: /accountReference
            targetField: accountReference
          - source: CHAIN_INPUT
            sourcePointer: /question
            targetField: question
          - source: MANAGER_OBJECTIVE
            targetField: objective
      result:
        type: BOUNDED_FACT_PROJECTION
        summaryPointer: /summary
        facts:
          - name: accountStatus
            valuePointer: /accountStatus
          - name: subscriptionStatus
            valuePointer: /subscriptionStatus
        evidenceReferences: ALL_APPROVED
      transitions:
        delegationAllowed: true
        parallelEligible: true
        handoffAllowed: false

    - specialistRef: policy-reader@1
      description: Reads the exact approved policy facts relevant to resolution.
      input:
        type: JSON_POINTER_MAP
        fields:
          - source: CHAIN_INPUT
            sourcePointer: /question
            targetField: question
          - source: MANAGER_OBJECTIVE
            targetField: objective
      result:
        type: BOUNDED_FACT_PROJECTION
        summaryPointer: /summary
        facts:
          - name: applicablePolicy
            valuePointer: /applicablePolicy
          - name: permittedOutcome
            valuePointer: /permittedOutcome
        evidenceReferences: ALL_APPROVED
      transitions:
        delegationAllowed: true
        parallelEligible: true
        handoffAllowed: false

  limits:
    maxDuration: PT60S
    maxManagerDecisions: 4
    maxWorkerInvocations: 2
    maxParallelWorkers: 2
    maxInvocationsPerTarget: 1
    maxProjectedResultCharacters: 8000

  conversationPolicy: REQUIRED
```

This example contains no trusted identity or authority. The host still builds
`TrustedExecutionContext` from authenticated application state for every
invocation.

### 6.3 Proposed Java representation

Add strict resource records equivalent to:

```java
public record SpecialistChainManifest(
    String apiVersion,
    String kind,
    SpecialistChainManifestMetadata metadata,
    SpecialistChainManifestSpec spec
) {}
```

```java
public record SpecialistChainManifestSpec(
    SpecialistChainManifestInput input,
    SpecialistChainManifestManager manager,
    List<SpecialistChainManifestTarget> targets,
    SpecialistChainLimits limits,
    SpecialistChainConversationPolicy conversationPolicy
) {}
```

```java
public record SpecialistChainManifestTarget(
    String specialistRef,
    String description,
    SpecialistChainTargetInputDefinition input,
    SpecialistChainTargetResultDefinition result,
    SpecialistChainTransitionPolicy transitions
) {}
```

All records must reject unknown fields through the strict object mapper. The
parser must preserve a canonical typed representation for audit identity and
semantic compilation. Literal YAML formatting and source filenames are not
execution identity.

The current `SpecialistChainDefinition` has no source or manifest-identity
field. Do not hide manifest identity in generated component IDs. Introduce a
source-aware registry envelope equivalent to:

```java
public record SpecialistChainRegistration(
    SpecialistChainDefinition<?> definition,
    SpecialistChainDefinitionSource source,
    SpecialistChainRegistrationIdentity identity
) {}

public record SpecialistChainRegistrationIdentity(
    Optional<String> resourceHash,
    Optional<String> declarativeSemanticsHash,
    Map<SpecialistSchemaId, String> schemaDependencies,
    String safeSource
) {}
```

For manifest registrations, `resourceHash` is the canonical whole-resource
audit hash, `declarativeSemanticsHash` is the compiler-owned hash of normalized
executable manifest semantics, and `schemaDependencies` contains the exact
chain-input schema ID and content hash. `safeSource` is diagnostic provenance
and never affects execution identity.

Factory methods must enforce source-specific invariants: a `MANIFEST`
registration requires all manifest identity fields; a `JAVA` registration has
no manifest hashes or schema-dependency map and continues to derive semantics
from its typed definition. The registry computes the final effective execution
hash after resolving manager, workers, schemas, limits, and policies.

### 6.4 Input schema

`spec.input.schemaRef` references one exact local `SpecialistSchema` with
`direction: INPUT`.

The manifest compiler must:

- resolve the exact schema;
- reject external `$ref` values;
- construct chain input as `JsonNode`;
- validate input before creating manager context or worker requests;
- reject unknown or malformed values according to the selected schema; and
- preserve current public request size limits and deployment ceilings.

The compiled chain request type and built-in declarative input adapter type
are exactly `JsonNode`. V1 does not compile a manifest chain into an arbitrary
application request class.

### 6.5 Manager specialist

`spec.manager.specialistRef` references one exact registered specialist.

The compiler must verify that the manager:

- exists after specialist manifest compilation;
- is eligible for chain-manager execution;
- accepts the exact framework manager-input contract;
- emits the exact five-field `SpecialistChainDirective` contract;
- enumerates only the chain's exact target IDs;
- cannot request writes;
- cannot widen retrieval/action authority; and
- has an effective content hash included in the chain hash.

The framework should provide a reusable validator for the manager input and
directive schemas so hosts do not reproduce the semantic checks.

The manager schema is coupled to the exact worker catalogue because its
`targetSpecialist` enum must exactly equal the chain targets. A manager may be
reused only by chains with the same exact target catalogue and compatible
conversation contract. Adding, removing, or versioning a target normally
requires a new manager specialist version and a new chain version; this
feature is not arbitrary team recomposition around one generic manager.

### 6.6 Target specialists

Every target uses an exact `SpecialistId` and the existing maximum target
count.

The compiler must verify that every target:

- exists;
- is read-only;
- is non-interactive;
- is not the manager;
- is unique within the chain;
- has resolvable input and output contracts;
- satisfies current delegation/handoff eligibility rules; and
- remains independently subject to effective authority resolution at runtime.

Every declarative target must support an exact `JsonNode` input and `JsonNode`
output binding through its registered JSON Schemas. Native Java specialists
whose typed contracts cannot bind to `JsonNode` are not eligible for a
manifest chain and must remain in a Java-defined chain.

Manifest loading must not infer a target from labels, descriptions, prompt
text, class names, installed modules, or model output.

## 7. Declarative Chain Input Adapter

### 7.1 Manager message

`managerMessagePointer` selects one string from the already schema-valid chain
input. It becomes `SpecialistChainManagerInput.currentUserMessage`.

Rules:

- use RFC 6901 JSON Pointer;
- the selected value must be a string;
- it must be non-empty after trimming;
- it must satisfy the existing manager-message bound; and
- missing, null, object, array, numeric, or boolean values fail before a provider call.

The name remains `managerMessagePointer` even for non-chat machine requests.
It identifies the bounded primary task text, not a browser-owned conversation.

### 7.2 Manager context

Each `managerContext` entry maps one fixed manifest-owned name to one scalar
string value selected from chain input.

Rules:

- names satisfy current `ConversationManagerContextValue` constraints;
- pointers use RFC 6901;
- values must be scalar strings in V1;
- entry count and value length use existing manager-context bounds;
- duplicates fail compilation;
- every entry has `required`, defaulting to `true`; a missing required value
  fails before a provider call, while a missing optional value is omitted; and
- context cannot contain trusted identity, authority, credentials, or provider configuration.

V1 must not serialize arbitrary input subtrees into manager context.

### 7.3 Boundary when declarative adaptation is insufficient

V1 has no manifest input-adapter reference. If the primary message or manager
context requires source-of-truth access, domain computation, conditional
transformation, or another operation beyond bounded selection from validated
input, define the chain in Java with the existing typed
`SpecialistChainInputAdapter` contract.

This is deliberate. A manifest should describe the framework-supported
declarative chain, not indirectly invoke application orchestration code through
a bean-name string.

## 8. Declarative Worker Input Mapping

### 8.1 Purpose

Each worker receives a fresh JSON object constructed from allowlisted sources.
The manager cannot author the worker payload.

V1 supports exactly these source kinds:

```java
public enum SpecialistChainMappingSource {
    CHAIN_INPUT,
    MANAGER_OBJECTIVE
}
```

`CHAIN_INPUT` selects one value through an RFC 6901 pointer from the validated
chain request. `MANAGER_OBJECTIVE` selects the already bounded,
non-authoritative objective from `SpecialistChainTargetRequest`.

### 8.2 Mapping rules

Each mapping declares:

- source kind;
- source pointer when the source is `CHAIN_INPUT`;
- one top-level destination field in a new worker input object; and
- `required`, defaulting to `true`.

The compiler/runtime must:

- reject duplicate destination fields;
- reject attempts to write outside the new worker request;
- reject prototype/meta-property paths;
- reject missing required source values and omit missing optional values;
- construct a fresh object rather than mutate chain input;
- reject unsupported scalar/object/array copies according to the target schema;
- validate the final object against the exact target specialist input schema;
- run mapping before any worker provider call; and
- return a stable safe failure code on mapping/schema failure.

V1 has no concatenation, interpolation, arithmetic, conditions, loops,
functions, external lookups, or implicit coercion.

V1 permits arbitrary bounded RFC 6901 source selection but only a validated
top-level destination field. It does not construct nested target paths. The
implementation must enforce explicit ceilings for:

- JSON Pointer character length and segment depth;
- manager-context entries;
- mapping entries per target and across one chain;
- copied node depth and node count;
- copied value bytes;
- final worker-object depth, node count, and serialized bytes; and
- total mapping work per request.

The selected node may be a scalar, object, or array only when those ceilings
and the exact target schema permit it. Bounds are deployment ceilings and may
only be narrowed by framework configuration; a manifest cannot widen them.

### 8.3 Boundary when declarative mapping is insufficient

V1 has no `mapperRef`. Domain computations, database reads, conditional
mapping, normalization requiring application logic, and nested object
construction belong in a Java-defined chain using the existing typed
`SpecialistChainTargetInputMapper` contract.

## 9. Declarative Worker Result Projection

### 9.1 Purpose

The manager must not receive a worker's raw output. A declarative projection
converts one already validated worker result into the existing bounded
`SpecialistChainResultProjection`:

- one summary;
- a bounded map of named string facts; and
- bounded approved evidence reference IDs.

### 9.2 Projection contract

V1 `BOUNDED_FACT_PROJECTION` contains:

- `summaryPointer` selecting one required string;
- zero to the existing maximum number of facts;
- fixed manifest-owned fact names;
- one JSON Pointer per fact value; and
- an evidence-reference policy of `NONE` or `ALL_APPROVED`.

Rules:

- projection runs only after worker output schema and semantic validation pass;
- summary and fact values must be strings in V1;
- no raw object/array serialization is allowed;
- all existing count and character bounds apply;
- duplicate fact names fail compilation;
- missing required fields fail visibly;
- `ALL_APPROVED` copies only evidence references already approved by the worker execution result;
- model-provided citation strings are never promoted into evidence references; and
- projection failure cannot become partial chain success.

Projection selectors have the same pointer character/depth and selected-value
size ceilings as input selectors. The final projection must also satisfy the
existing summary, fact-count, fact-value, evidence-reference, and total
projected-character limits.

### 9.3 Boundary when declarative projection is insufficient

V1 has no `projectorRef`. If projection requires authoritative application
logic, reconciliation with a system of record, computed domain invariants, or
anything richer than bounded strings and approved evidence IDs, keep the chain
Java-defined and use the existing typed
`SpecialistChainTargetResultProjector` contract.

## 10. Chain Limits And Transition Policy

The manifest maps directly to existing `SpecialistChainLimits` and
`SpecialistChainConversationPolicy`.

The compiler must ensure:

- every limit is positive and within existing absolute bounds;
- `maxManagerDecisions` reserves a completion decision;
- parallel workers do not exceed total worker invocations;
- per-chain limits only narrow deployment-level ceilings;
- target flags satisfy existing `SpecialistChainTarget` invariants;
- only delegation targets may be parallel eligible;
- every target permits at least delegation or handoff; and
- conversation policy is `DISABLED`, `OPTIONAL`, or `REQUIRED`.

A public request cannot replace or relax manifest limits.

## 11. Loading, Compilation, And Registration

### 11.1 Resource discovery

Preferred behavior:

- existing `ai.execution.manifests.locations` may contain
  `SpecialistSchema`, `SpecialistPromptProfile`, `Specialist`, and
  `SpecialistChain` documents;
- `.yml`, `.yaml`, and `.json` remain supported;
- multi-document YAML remains supported;
- existing resource and manifest byte ceilings apply, with a dedicated chain
  ceiling only if required by implementation evidence; and
- `fail-fast: true` remains the production default.

Extend the existing one-pass `SpecialistManifestLoader` classification and
`SpecialistResourceBundle`. Do not add a second resource scanner or a separate
public `SpecialistChainManifestLoader`. The same ordered resource pass must
classify schemas, prompt profiles, specialists, and chains, preserving current
multi-document YAML behavior and resource ceilings.

If manifest loading is enabled and a `SpecialistChain` resource is discovered
while `ai.execution.specialist-chains.enabled=false`, the resource must never
be silently ignored:

- with production-default `fail-fast: true`, startup fails with
  `CHAIN_MANIFEST_FEATURE_DISABLED`;
- with diagnostics-only `fail-fast: false`, the chain is not registered, a
  bounded inactive diagnostic is published, and manifest readiness must not be
  reported as green; and
- the disabled chain path must not create a JDBC/repository requirement.

When manifest loading itself is disabled, existing behavior remains: resource
locations are not scanned.

### 11.2 Compilation order

Startup must resolve resources in this order:

1. schemas;
2. prompt profiles;
3. specialist definitions;
4. chain manifests;
5. merged Java and manifest chain registry;
6. durable gateway readiness.

A chain cannot compile against a specialist that failed or was omitted.

### 11.3 Proposed compiler contracts

Extend `SpecialistResourceBundle` with loaded chain resources and chain
diagnostics while retaining one loader contract.

Add a framework-owned compiler equivalent to:

```java
public interface SpecialistChainManifestCompiler {
    SpecialistChainRegistration compile(
        LoadedSpecialistChainManifest manifest,
        SpecialistChainCompilationContext context
    );
}
```

`SpecialistChainCompilationContext` supplies reviewed registries and ceilings.
It must not carry request identity or runtime user data.

### 11.4 Registry merge

The framework must merge:

- Java `SpecialistChainDefinition<?>` beans wrapped as source-aware
  registrations; and
- compiled manifest `SpecialistChainRegistration` values containing
  `SpecialistChainDefinition<JsonNode>`.

The existing `SpecialistChainRegistry` remains the only runtime lookup source.
Duplicate exact IDs fail startup regardless of source.

The registry input/API must make manifest provenance explicit. It must not
attempt to infer source from generated adapter classes, component IDs, bean
names, or input type.

## 12. Content Identity And Durable Compatibility

### 12.1 Audit resource identity

Each loaded chain receives a `resourceHash` over the complete recognized
resource converted to canonical typed JSON. It includes descriptive metadata
for audit and deployment comparison. It is not a hash of literal YAML bytes:
comments, whitespace, key order, source filename, and YAML-versus-JSON
serialization must not change it.

The audit hash may change when `displayName`, top-level description, or labels
change. Such a change is operationally visible but must not invalidate durable
execution by itself.

### 12.2 Declarative semantics identity

The compiler produces a separate `declarativeSemanticsHash` from only the
normalized fields that can affect execution:

- resource API/contract version and exact chain ID;
- exact input schema reference;
- manager message and context selectors;
- exact manager specialist reference;
- targets in declaration order;
- every target's exact specialist reference and manager-visible description;
- all declarative input mappings and result projections;
- target transition flags;
- limits; and
- conversation policy.

It excludes `metadata.displayName`, top-level descriptive metadata, labels,
source filename, comments, and serialization formatting. Defaults must be
normalized before hashing so equivalent explicit and implicit values cannot
produce ambiguous identity.

### 12.3 Effective execution identity

The registry computes the final durable `executionHash` from:

- the declarative semantics hash for manifest definitions, or the normalized
  typed definition fingerprint for Java definitions;
- input schema exact ID and content hash;
- manager specialist exact ID and effective content hash;
- every target specialist exact ID and effective content hash in declaration
  order; and
- a framework chain-contract version when needed to prevent ambiguous replay.

The full `resourceHash` must not be folded into `executionHash`, because labels
and display text do not alter execution. Equivalent semantics must produce
deterministic execution identity across process restarts. Any executable
change under an existing exact ID is invalid authoring and must also produce a
different execution hash as a fail-closed safeguard.

Target order is execution-semantic. The gateway presents targets to the
manager in declaration order, while the current `0.6.1` registry sorts them
before hashing. Implementation must remove that mismatch and hash declaration
order. This is intentional registry hardening: Java-defined chains declared in
non-sorted order may receive a new hash. The feature release must document an
operational drain/restart requirement and must not resume pre-release durable
work under a newly computed hash.

### 12.4 Durable replay

Existing durable rules remain:

- execution records pin exact chain ID and effective hash;
- unchanged payload plus unchanged definition may replay exactly;
- changed payload under the same idempotency key conflicts;
- changed chain, manager, worker, schema, mapping, projection, target order,
  limits, or policy
  fails with a changed-definition outcome; and
- old records are never relabeled with a new execution hash.

No new persistence table is required merely because the definition came from
YAML. The existing chain table remains authoritative.

## 13. Offline Validation And Authoring Support

A deployment system must be able to reject invalid manifests before starting
a new runtime. Add a framework-owned parser/validator suitable for trusted
host code and build tooling.

### 13.1 Validation API

Provide an API equivalent to:

```java
public interface SpecialistChainManifestValidator {
    SpecialistChainManifestValidationResult validate(
        SpecialistChainManifestBundle bundle,
        SpecialistChainAuthoringCatalog catalog,
        SpecialistChainDeploymentCeilings ceilings
    );
}
```

The result contains:

- valid/invalid status;
- exact parsed chain IDs;
- required specialist and schema references;
- deterministic safe reason codes;
- bounded source locations and field paths;
- normalized limits and capability requirements;
- audit resource and declarative-semantics hashes; and
- no prompt bodies, secrets, protected data, or raw application objects.

The offline validator and runtime compiler must share validation components.
They must not evolve into two independent interpretations of the manifest.

### 13.2 Authoring catalogue

Extend the trusted authoring catalogue or add a chain-specific provider that
exposes:

- exact specialists and effective role/capability metadata;
- input and output schema IDs;
- whether a specialist is manager-compatible;
- whether a specialist is read-only and non-interactive;
- whether each specialist supports the required exact schema-backed typed
  binding;
- supported declarative selector, mapping, projection, and payload ceilings;
- whether the specialist-chain feature is enabled for the target deployment;
- previously accepted exact chain IDs and declarative-semantics hashes when
  the host supplies a deployment/publication baseline;
- deployment chain ceilings; and
- supported resource contract versions.

The catalogue is authoring information, not runtime authorization. It must not
be exposed as unrestricted model-selected discovery.

The rule that semantic changes require a new exact version can be diagnosed as
`CHAIN_MANIFEST_EXACT_VERSION_REUSED` only when validation has a prior
publication/deployment baseline. Without that baseline, the framework can
still reject duplicates within the current bundle and reject durable replay
through the effective execution hash, but it must not claim historical
version-reuse detection it cannot prove.

### 13.3 Published JSON Schema

Publish an editor/CI schema for the complete resource bundle. It must cover
`SpecialistChain` and enforce every structural rule that JSON Schema can
represent.

Semantic validation remains authoritative for cross-resource references,
effective capabilities, exact typed bindings, and deployment ceilings.

## 14. Runtime Status, Metrics, And Diagnostics

Add safe runtime status equivalent to the specialist manifest status.

It should expose:

- whether chain manifest loading is enabled;
- readiness;
- Java-defined chain count;
- discovered manifest-chain count and inactive count;
- manifest-defined chain count;
- total registered chain count;
- aggregate resource, declarative-semantics, and effective execution hashes
  where applicable;
- exact chain IDs and their bounded source/hash metadata only where the host
  explicitly allows them; and
- bounded safe diagnostics with reason code and source filename.

It must not expose:

- prompts;
- schemas;
- full manifests;
- manager directives;
- worker inputs or outputs;
- trusted context;
- conversation history;
- persistence payloads;
- secrets; or
- provider responses.

Metrics should cover:

- resources discovered;
- parse success/failure;
- compile success/failure by safe reason code;
- definitions registered by source;
- duplicate identity failures;
- mapping failures;
- projection failures; and
- existing chain execution outcomes without adding a second metric family for
  execution source unless a source tag is bounded and useful.

## 15. Failure Model And Reason Codes

Use stable, bounded reason codes. Proposed codes include:

| Reason code | Meaning |
| --- | --- |
| `CHAIN_MANIFEST_FEATURE_DISABLED` | A chain resource was discovered while chain execution is disabled |
| `CHAIN_MANIFEST_API_VERSION_UNSUPPORTED` | Resource API version is unsupported |
| `CHAIN_MANIFEST_KIND_UNSUPPORTED` | Resource kind is not recognized |
| `CHAIN_MANIFEST_ID_INVALID` | Chain name/version is malformed |
| `CHAIN_MANIFEST_TOO_LARGE` | Chain resource exceeds its configured bound |
| `CHAIN_MANIFEST_INPUT_SCHEMA_NOT_FOUND` | Exact chain input schema is absent |
| `CHAIN_MANIFEST_MANAGER_NOT_FOUND` | Exact manager specialist is absent |
| `CHAIN_MANIFEST_MANAGER_CONTRACT_INVALID` | Manager input/directive contract is incompatible |
| `CHAIN_MANIFEST_MANAGER_TARGET_CATALOG_MISMATCH` | Manager target enum differs from the chain target catalogue |
| `CHAIN_MANIFEST_TARGET_NOT_FOUND` | Exact worker specialist is absent |
| `CHAIN_MANIFEST_TARGET_NOT_READ_ONLY` | Worker can propose or perform writes |
| `CHAIN_MANIFEST_TARGET_INTERACTIVE` | Worker can own dialogue |
| `CHAIN_MANIFEST_TARGET_DUPLICATE` | Worker appears more than once |
| `CHAIN_MANIFEST_TARGET_BINDING_INVALID` | Worker cannot bind as exact schema-backed `JsonNode` input/output |
| `CHAIN_MANIFEST_INPUT_ADAPTER_INVALID` | Declarative adapter is structurally invalid |
| `CHAIN_MANIFEST_INPUT_MAPPING_INVALID` | Worker mapping is invalid or ambiguous |
| `CHAIN_MANIFEST_INPUT_MAPPING_LIMIT_EXCEEDED` | Mapping pointer, node, count, or byte ceiling is exceeded |
| `CHAIN_MANIFEST_RESULT_PROJECTION_INVALID` | Result projection is structurally invalid |
| `CHAIN_MANIFEST_RESULT_PROJECTION_LIMIT_EXCEEDED` | Projection pointer, value, count, or byte ceiling is exceeded |
| `CHAIN_MANIFEST_LIMIT_INVALID` | Per-chain limits are inconsistent |
| `CHAIN_MANIFEST_LIMIT_EXCEEDS_DEPLOYMENT` | Manifest attempts to widen a deployment ceiling |
| `CHAIN_MANIFEST_CONVERSATION_POLICY_INVALID` | Conversation policy is unsupported |
| `CHAIN_MANIFEST_DEFINITION_DUPLICATE` | Java/manifest registry contains the same exact ID |
| `CHAIN_MANIFEST_EXACT_VERSION_REUSED` | An exact ID is reused with changed executable semantics |
| `CHAIN_DEFINITION_CHANGED` | Durable work references another effective hash |

Public diagnostics use safe messages. Internal exceptions may retain causes
without exposing protected content.

`CHAIN_MANIFEST_*` codes are authoring, loading, or startup-compilation
outcomes. Runtime gateway codes retain their existing `CHAIN_*` namespace.
Compiler code must not reuse a runtime code such as
`CHAIN_RESULT_PROJECTION_INVALID`, because that would make deployment defects
indistinguishable from request-time execution failures.

## 16. Authority And Security Rules

The manifest is trusted deployment configuration, but it is not authority.

Required rules:

1. The host application authenticates the request before chain selection.
2. The host constructs `TrustedExecutionContext` from server-owned state.
3. Chain input cannot supply or override principal, subject, customer, tenant,
   deployment, source, scopes, credentials, provider, or vector authority.
4. The manager can choose only currently approved exact targets.
5. Every worker receives an independently resolved effective capability profile.
6. Manifest-requested actions and vector spaces remain narrowed by mode,
   deployment inventory, registries, and trusted authority.
7. Workers remain read-only and non-interactive in V1.
8. Manager objectives are untrusted task text, not authority or worker payload.
9. Raw worker output never reaches another model without a validated projector.
10. Evidence references come from framework-approved execution evidence, not
    model-authored strings.
11. No chain-manifest field may name a Java class, Spring bean, or
    application adapter/mapper/projector, or invoke arbitrary code.
12. Mounted resources must be immutable for the life of the process.
13. Missing or ambiguous references fail closed.
14. `fail-fast: false` remains diagnostics-only and cannot advertise partial
    production readiness.

## 17. Host And Deployment-System Integration

The framework should enable this generic host flow:

```text
author one execution resource bundle
  -> run framework parser and validator
  -> pin exact resource bytes and hashes in immutable deployment configuration
  -> mount or package the bundle at an approved manifest location
  -> start the application with fail-fast loading
  -> compare expected and runtime registry hashes
  -> admit traffic only after readiness and product verification pass
```

The framework does not own:

- customer identity or tenancy;
- deployment version storage;
- marketplace publication;
- artifact signing;
- secret management;
- infrastructure provisioning;
- release approval;
- traffic assignment; or
- product-specific verification.

The framework does own the portable resource contract, strict parser,
compiler, registry integration, runtime invariants, content identity,
durability compatibility, and safe diagnostics.

## 18. Module-Level Implementation Plan

### Phase A: Contract and strict parsing

Tasks:

- add `SpecialistChain` resource records;
- extend the published execution-resource JSON Schema;
- extend the existing one-pass manifest loader and resource bundle;
- parse YAML/JSON with the existing strict object-mapper policy;
- support multi-document bundles;
- preserve safe source, canonical typed content, and audit resource hash;
- reject or explicitly diagnose discovered chains when the feature is disabled;
- add bounded diagnostics and reason codes;
- add size and duplicate-resource tests.

Exit:

- valid resources parse deterministically;
- unknown fields and unsupported kinds fail;
- no chain is registered yet.

### Phase B: Declarative adapters, mappings, and projections

Tasks:

- implement JSON Pointer manager-message extraction;
- implement bounded manager-context extraction;
- implement fresh-object target input mapping;
- support only `CHAIN_INPUT` and `MANAGER_OBJECTIVE` sources;
- permit only bounded source pointers and top-level target fields;
- validate mapped worker input against exact target schemas;
- implement bounded fact/evidence result projection;
- enforce pointer, field-count, node-depth, node-count, value-byte, final-object,
  and total-work ceilings; and
- reject every Java component-reference field as an unknown field.

Exit:

- common JSON-schema chains require no application Java components;
- unsupported transformations require a Java-defined chain;
- no expression engine exists.

### Phase C: Chain compiler and registry merge

Tasks:

- compile manifests into `SpecialistChainDefinition<JsonNode>`;
- validate manager directive compatibility;
- resolve exact read-only non-interactive workers;
- verify exact schema-backed `JsonNode` bindings for every declarative worker;
- reuse current target and limit invariants;
- merge Java and manifest definitions into one registry;
- introduce source-aware chain registrations;
- compute separate audit-resource, declarative-semantics, and effective
  execution hashes;
- preserve and hash target declaration order;
- fail duplicate exact IDs across sources.

Exit:

- the existing gateway executes one manifest-defined chain;
- Java-defined chain behavior remains unchanged.

### Phase D: Durability, replay, and runtime status

Tasks:

- prove JDBC submission, checkpointing, restart recovery, result lookup,
  cancellation, and exact replay for manifest chains;
- prove changed manager/worker/schema/mapping/projection/order rejection while
  descriptive metadata changes preserve execution identity;
- add safe runtime status and aggregate hash;
- add bounded metrics and diagnostics;
- verify no manifest content leaks through health or metrics.

Exit:

- a manifest chain is operationally equivalent to a Java chain;
- changed definitions fail closed.

### Phase E: Offline validation and authoring support

Tasks:

- expose parser/validator APIs for trusted host code;
- share validation logic with runtime compilation;
- expose safe authoring catalogue entries;
- expose declarative bounds and feature availability, not Java bean catalogues;
- compare candidate semantics with an optional prior publication/deployment
  catalogue to detect exact-version reuse;
- publish normalized requirement and diagnostic output;
- add build-tool examples without creating a framework-hosted control plane.

Exit:

- a host can reject invalid chain bundles before deployment;
- offline and runtime interpretation cannot drift silently.

### Phase F: Real application and external consumer proof

Tasks:

- add a manifest-defined bounded chain to one real app under a separate exact
  ID or profile;
- use one manager and at least two read-only workers;
- cover zero-worker completion, one worker, adaptive second worker, independent
  parallel workers, clarification, terminal handoff, and grounded completion;
- run with a real provider;
- prove restart, replay, cancellation, deadline, and cross-owner denial;
- add an empty-cache external consumer using only published artifacts.

Exit:

- the public contract is usable without framework source checkout;
- real-provider behavior matches the Java-defined security contract.

### Phase G: Documentation and release

Tasks:

- update the specialist authoring guide;
- update the bounded-chain guide;
- update migration and application guides;
- document Java-versus-manifest selection;
- publish complete example resources;
- document operational drain requirements for definition changes;
- add release notes and Maven Central verification.

Exit:

- consumers can adopt the feature from released documentation and artifacts;
- no private bridge is required.

## 19. Required Test Matrix

### 19.1 Parsing and schema

- valid YAML and JSON;
- multi-document bundle;
- unknown field;
- unsupported API version;
- unsupported resource kind;
- malformed exact ID;
- oversized resource;
- duplicate exact chain ID;
- absent input schema;
- external schema reference;
- deterministic canonical resource hash;
- deterministic declarative-semantics hash;
- equivalent YAML/JSON serialization produces the same hashes;
- descriptive metadata changes only the resource hash; and
- execution-semantic changes require a new exact version when a prior baseline
  is supplied.

### 19.2 Compilation and startup

- manager missing;
- manager equals worker;
- manager directive schema missing required field;
- manager target enum does not equal registered targets;
- manager reuse with a different target catalogue rejected;
- worker missing;
- duplicate worker;
- write-capable worker;
- interactive worker;
- worker without exact schema-backed `JsonNode` binding;
- invalid transition flags;
- invalid limits;
- deployment ceiling exceeded;
- duplicate Java/manifest chain ID;
- fail-fast readiness failure;
- chain resource plus disabled chain feature fails by default;
- diagnostics-only disabled-feature omission with readiness not falsely green;
- disabled-feature diagnostic creates no JDBC requirement; and
- target declaration order preserved in manager input and execution hash.

### 19.3 Input adapter and mapping

- valid message pointer;
- missing/non-string/oversized message;
- bounded manager context;
- duplicate context name;
- valid scalar/object/array transfer where target schema allows it;
- missing required source;
- duplicate destination field;
- nested destination path rejected;
- invalid or reserved destination field;
- manager objective mapping;
- final worker schema mismatch;
- pointer character/depth ceiling;
- mapping count and total-work ceilings;
- copied node depth/count/value-byte ceilings;
- final mapped-object depth/count/serialized-byte ceilings;
- no implicit coercion;
- no mutation of original chain input; and
- adapter/mapper/projector reference fields rejected as unknown.

### 19.4 Result projection

- valid summary/facts/evidence;
- missing summary;
- non-string fact;
- duplicate fact name;
- fact count and size overflow;
- only approved evidence references copied;
- raw result never exposed;
- projection pointer/value/count/byte ceilings; and
- projection failure prevents synthesis.

### 19.5 Chain execution behavior

- manager completes without worker;
- one worker;
- adaptive sequential second worker;
- independent parallel workers with `ALL_REQUIRED` semantics;
- one clarification;
- terminal handoff;
- invented target denied;
- completed worker cannot be reinvoked beyond limits;
- supporting-result attribution exactness;
- provider failure remains failure;
- required branch failure remains failure;
- deadline and cancellation.

### 19.6 Authority and isolation

- missing principal;
- missing tenant/deployment where required by host policy;
- cross-principal status/result denial;
- cross-tenant and cross-deployment denial;
- caller input cannot override trusted context;
- manager objective cannot widen worker capability;
- worker authority evaluated independently;
- requested vector/action capability denied when not effective;
- no write worker path.

### 19.7 Persistence and drift

- submit and restart recovery;
- unchanged exact replay;
- changed request conflict;
- changed executable chain-manifest field;
- changed input schema;
- changed manager prompt/schema/hash;
- changed worker prompt/schema/hash;
- changed mapping;
- changed projection;
- changed target order;
- changed target manager-visible description;
- changed labels/display name preserve execution hash but change resource hash;
- prior-baseline comparison rejects semantic change under the same exact ID;
- changed limits/policy;
- cancelled terminal replay;
- no old record relabeling.

### 19.8 Regression

- Java-defined chains retain behavior; the target-order hash correction is an
  intentional hash migration for definitions declared in non-sorted order;
- direct specialists remain unchanged;
- specialist manifest loading remains unchanged;
- fixed plans, delegation, handoff, conversation managers, receipts, reviews,
  and durable direct jobs retain existing behavior;
- chains disabled means no chain persistence requirement;
- no additional provider call on rejected startup/request validation.

## 20. Release Gates

Before release, all of the following must pass:

1. focused manifest parser/compiler tests;
2. focused chain registry/gateway/JDBC tests;
3. full `ai-fabric-execution` test suite;
4. full framework reactor with normal tests enabled;
5. every real-app module with normal tests enabled;
6. packaged application boot using immutable mounted chain manifests;
7. deterministic smoke provider matrix;
8. keyed real-provider chain matrix;
9. JDBC restart, replay, cancellation, and changed-definition canaries;
10. cross-principal, cross-tenant, and cross-deployment denial canaries;
11. empty-cache external consumer using only Maven Central artifacts;
12. health/readiness proof with exact framework and registry identity; and
13. documentation and JSON Schema examples validated by the released parser.

No release gate may skip tests, use locally installed release artifacts, rely
on mutable source, or treat a healthy old process as proof of the new manifest.

## 21. Explicit Non-Goals

This change does not provide:

- a generic agent graph runtime;
- BPMN or workflow orchestration;
- model-authored plans;
- dynamic target discovery;
- recursive specialist calls;
- nested chains;
- chain workers that execute or propose writes;
- arbitrary condition expressions;
- transformation scripts;
- remote code loading;
- arbitrary URLs or connector definitions;
- trusted identity in YAML;
- provider selection in YAML;
- user-uploaded hot configuration;
- partial-success synthesis;
- exactly-once provider invocation;
- exactly-once external side effects;
- chain definition mutation while work is active;
- deployment management APIs; or
- a framework-owned marketplace.

## 22. Framework And Host Boundary

AI Fabric owns:

- the `SpecialistChain` resource contract;
- strict parsing and schema validation;
- safe declarative mapping/projection primitives;
- compilation to existing chain definitions;
- source-aware registration and identity calculation;
- registry integration;
- execution invariants;
- content hashes;
- durable compatibility;
- metrics and safe diagnostics; and
- reference applications and public documentation.

The host application or deployment system owns:

- who may author or select a manifest;
- storage and review of resource bundles;
- deployment configuration immutability;
- authentication and trusted execution context;
- application-specific adapters, mappers, and projectors used by explicitly
  Java-defined chains, never indirectly referenced from chain manifests;
- source-of-truth authorization;
- infrastructure and database provisioning;
- release approval and traffic assignment;
- product UX; and
- domain-specific quality verification.

The framework must remain usable by an ordinary Spring Boot application with
classpath manifests. Managed deployment systems are an adopter, not a required
runtime dependency.

## 23. Adoption Guidance

Use a manifest-defined chain when:

- manager and workers are exact manifest specialists;
- input and output are JSON Schema-backed;
- every worker supports exact schema-backed `JsonNode` input/output binding;
- mapping can be represented through bounded JSON Pointer selection into
  top-level target fields;
- worker results can be reduced to bounded summary/facts/evidence; and
- topology is one closed bounded chain.

Keep a Java-defined chain when:

- the chain request is a rich application type;
- mapping requires domain invariants or source-of-truth access;
- projection requires authoritative application logic;
- safe output cannot be represented through bounded strings and evidence IDs;
- components have complex application dependencies; or
- a reviewed Java definition is clearer and safer than configuration.

Do not choose manifests merely to avoid source review. The declarative path is
for a bounded, reviewable subset of current chain semantics.

## 24. Definition Of Done

This change is complete only when:

1. `SpecialistChain` is a published `ai.fabric/v1` resource with a validated JSON Schema.
2. A multi-document bundle can define schemas, prompts, specialists, and one chain.
3. The chain compiles into `SpecialistChainDefinition<JsonNode>`.
4. Java and manifest chains share one registry and gateway.
5. Common JSON-schema chains require no application Java mapping/projector code.
6. Manifest fields cannot reference Java adapters, mappers, projectors, bean names, or classes.
7. Complex application boundaries remain explicit Java-defined chains.
8. Manager and worker contracts fail closed when missing or incompatible.
9. Every worker remains exact-version, read-only, non-interactive, independently authorized, and schema-bindable as `JsonNode`.
10. Audit resource identity is separate from declarative semantics and durable execution identity.
11. Content identity includes ordered targets, resolved manager/workers/schemas, mappings, projections, limits, and policy.
12. Existing JDBC replay/recovery rejects changed execution definitions while descriptive metadata changes do not invalidate replay.
13. Offline validation and runtime compilation share the same semantic validators.
14. One existing resource loader handles schemas, prompts, specialists, and chains.
15. A discovered chain with the feature disabled is never silently ignored and does not require JDBC when inactive.
16. Runtime status proves expected chain count and bounded hashes without exposing protected content.
17. Real-app deterministic, keyed-provider, restart, replay, cancellation, and isolation tests pass.
18. An empty-cache external consumer passes from Maven Central artifacts only.
19. Existing Java-chain behavior and non-chain execution regressions pass; the documented target-order hash migration is verified.
20. Documentation clearly distinguishes bounded chain manifests from workflow/graph engines.

## 25. Implementation Evidence

The `0.7.0` source candidate passed these pre-publication gates on
2026-09-19:

- the isolated 36-module framework reactor completed `clean verify` with the
  local ONNX model enabled: 1,947 tests, zero failures, zero errors, and three
  intentional skips;
- the isolated 26-module real-app reactor completed `clean verify`: 531 tests,
  zero failures, zero errors, and 23 credential-gated skips;
- the focused `ai-fabric-execution` reactor passed all 417 tests;
- the standalone public consumer passed all five tests against installed
  source-candidate artifacts;
- all 15 Incident Investigation Room OpenAI scenarios passed from an isolated
  checkout, including manifest-defined parallel routing, exact replay,
  health-only stopping, adaptive routing, unsupported-target rejection, and
  terminal handoff;
- the packaged candidate discovered and registered exactly one manifest chain,
  executed it against PostgreSQL, replayed it without re-execution, restarted
  the application container, and replayed the same durable result; and
- the published JSON Schema parsed successfully, changed files contained no
  placeholder implementations, and `git diff --check` was clean.

The post-publication gates completed on 2026-09-19:

- the immutable `ai-fabric-framework-v0.7.0` tag points to release commit
  `5b075b66384dc5b756b3b3dd12efaf896ce9a50b`;
- the Maven Central release workflow succeeded and representative `0.7.0`
  artifacts resolved over HTTPS;
- a new empty-cache standalone consumer passed all five tests using Maven
  Central only;
- both public reference applications deployed with AI Fabric `0.7.0` and
  reported their manifest-defined chains as registered and ready; and
- live OpenAI calls executed each declarative chain with an
  `INVOKE_PARALLEL` decision followed by `COMPLETE`, then returned an exact
  replay for the same scoped idempotency key and execution ID.

The public Incident and Agentic Resolver UIs call the declarative async,
status, and cancellation endpoints. Java-defined chains remain available only
as an explicit comparison and as the supported escape hatch for complex
application-owned mapping or projection.

## 26. Final Recommendation

Implement official declarative bounded specialist-chain manifests in AI Fabric
as the configuration form of the existing chain contract.

Do not ask downstream platforms to create private chain YAML, reflection
bridges, generated Java source, or parallel orchestration engines. Provide one
strict framework resource that resolves exact specialists and schemas,
supports bounded field mapping and result projection, compiles into the current
registry/gateway, and preserves all existing authority, durability, replay,
and failure guarantees.

The first release should remain intentionally narrow: immutable startup-loaded
read-only chains, closed exact targets, JSON Schema input, bounded projections,
and no executable configuration or named Java component references. When the
declarative subset is insufficient, use the existing Java chain API rather
than weakening the manifest contract. That scope is sufficient for
deployment-time composition while keeping the application and framework
security boundaries intact and aligned with AI Fabric's philosophy that
configuration supplies constraints while intelligence makes bounded choices.
