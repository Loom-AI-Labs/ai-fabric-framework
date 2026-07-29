# Configurable Specialist Manifest Runtime Implementation Plan

- **Status:** Implemented and verified; not released
- **Date:** 2026-07-28
- **Implementation verified:** 2026-07-29
- **Framework baseline:** AI Fabric `0.4.0`
- **Code reviewed at:** `0b73eeca8f04ca49a4f81d26a1adecea8114b641`
- **Prerequisite:** Completed bounded specialist execution and governed write receipts
- **Target:** Next agentic-enablement release after plan `0002`; version not assigned
- **Reference proof:** `examples/real-apps/agentic-ai-action-resolver`

## 1. Purpose

Make specialists easy to create and operate from Loom AI without requiring a
new Java configuration class for every specialist.

The implementation must preserve the governance already proved by the
execution module:

- a specialist requests capabilities but never grants itself authority;
- trusted principal, subject, tenant, deployment, and scopes remain
  application-owned;
- Mode, deployment, action-registry, and current-authority restrictions still
  intersect before any capability reaches a model;
- a model may propose an approved WRITE but cannot authorize, confirm, or
  execute it;
- action outcomes and unknown outcomes remain application truth; and
- provider, schema, grounding, policy, and persistence failures remain visible.

The target developer experience is:

> Create most specialists by publishing a versioned manifest that composes
> capabilities already registered by the application. Write Java only when the
> application introduces genuinely new domain behavior.

This plan does not add specialist-definition storage. Version-one manifests are
application configuration loaded and validated at startup. The JDBC storage
used by the restart/replay proof belongs only to durable action receipts.

## 2. Executive Decision

Implement a hybrid specialist model.

### Config-first path

A manifest may compose:

- an existing AI Fabric Mode;
- a versioned objective and prompt profile;
- existing vector spaces;
- registered READ actions;
- registered, confirmation-gated WRITE actions that may only be proposed;
- versioned JSON input and output schemas;
- built-in input rendering and structured-output handling;
- default grounding checks;
- optional references to pre-registered reusable validators or projectors;
- conversation eligibility; and
- enforceable execution limits.

This is the ordinary path Loom AI should use.

### Java extension path

The existing `SpecialistDefinition<I, O>` bean remains supported for:

- strongly typed Java APIs;
- a new domain action or connector;
- complex authoritative fact reconciliation;
- a custom deterministic output projector;
- a domain invariant that cannot be expressed safely by JSON Schema and
  built-in grounding rules; or
- a custom input representation.

The Java path is an escape hatch, not the default authoring experience.

### Explicit non-goal

Do not build a general expression language, Java class loader, script runtime,
or arbitrary HTTP/SQL tool system inside specialist manifests. A manifest may
reference only resources and extension IDs already approved by the
application.

## 3. Why This Work Is Needed

Current code has a sound execution boundary but specialist authoring is
bean-only:

| Current evidence | Consequence |
| --- | --- |
| [`SpecialistDefinition`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/SpecialistDefinition.java) contains identity, instructions, execution profile, limits, input adapter, and output adapter | The aggregate is a good compiler target, but every definition currently requires Java. |
| [`AIExecutionAutoConfiguration`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/config/AIExecutionAutoConfiguration.java) creates the registry from `List<SpecialistDefinition<?, ?>>` beans | Add startup-loaded manifests as a second definition source; no runtime repository is required. |
| [`DefaultSpecialistRegistry`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/DefaultSpecialistRegistry.java) rejects duplicate IDs, unknown Modes/actions/vector spaces, invalid action access modes, and unbounded retrieval | These validations must be reused by the manifest compiler, not reimplemented differently. |
| [`RequestedCapabilityProfile`](../../../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/capability/RequestedCapabilityProfile.java) separates visible, requestable READ, and proposable WRITE actions | The manifest can expose the existing distinction directly. |
| [`SpecialistCapabilityResolver`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/gateway/SpecialistCapabilityResolver.java) performs the runtime Mode, deployment, registry, and authority intersection | Dynamic configuration does not require a new authorization model. |
| [`DefaultSpecialistAuthorityResolver`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/gateway/DefaultSpecialistAuthorityResolver.java) derives exact action/vector grants from trusted scopes | Authority must remain outside the manifest. |
| [`DefaultStructuredSpecialistOutputFinalizer`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/gateway/DefaultStructuredSpecialistOutputFinalizer.java) currently derives structured output from a Java `Class<O>` | Schema-backed manifest outputs need an explicit JSON-Schema path while retaining the current typed path. |
| [`account-resolver.yml`](../../../../examples/real-apps/agentic-ai-action-resolver/src/main/resources/ai-specialists/account-resolver.yml) now owns specialist identity, schemas, prompt profiles, capabilities, grounding requirements, conversation policy, and limits | [`AccountResolverSpecialistExtensions`](../../../../examples/real-apps/agentic-ai-action-resolver/src/main/java/com/ai/fabric/realapps/agenticresolver/agentic/AccountResolverSpecialistExtensions.java) retains only authoritative account projection and consistency checks. |
| [`ActionOutcomeProjectorRegistry`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/action/ActionOutcomeProjectorRegistry.java) binds safe projections to registered actions | Safe action projection is reusable action infrastructure and must not be duplicated per specialist. |
| [`ChatSessionStorageProvider`](../../../../ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/spi/ChatSessionStorageProvider.java) stores mutable conversation state | Keep conversation storage unrelated to static specialist configuration. |

The Account Resolver proves why both paths are needed. Its identity,
capabilities, prompt constraints, schemas, limits, and conversation projection
are declarative. Its final blocker calculation compares authoritative account
facts with domain policy and is real application logic. The former should be
configuration; the latter should remain a registered validator/projector.

## 4. Ownership Boundary

The following split is mandatory.

| Concern | Owner | Defined per specialist? |
| --- | --- | --- |
| Identity, description, objective, Mode, requested capabilities, schemas, prompt profile, strategy, conversation eligibility, and limits | Specialist manifest | Yes |
| Input JSON validation and canonical rendering | AI Fabric built-in schema runtime | No custom code for ordinary JSON inputs |
| Output JSON validation and structured generation | AI Fabric built-in schema runtime | No custom code for ordinary structured outputs |
| Grounding scope and minimum evidence/observation requirements | Manifest plus AI Fabric default grounding validator | Only the requirement values vary |
| Complex domain consistency check | Registered application validator | Only when the domain genuinely needs it |
| Trusted principal, subject, tenant, deployment, scopes, and source | Host application and `TrustedExecutionContext` | Never |
| Authorization and effective capability intersection | AI Fabric plus application policy | Never |
| Action parameters, access mode, confirmation contract, and handler | Existing `AIActionRegistry` | Per action, not per specialist |
| Safe WRITE outcome projection | Existing action-level projector registry | Per action, reusable by every specialist |
| `OUTCOME_UNKNOWN` reconciliation | Action/system connector | Per action or system of record, reusable |
| Chat turns and ownership | `ai-fabric-chat-session` | Per conversation, not part of manifest configuration |
| Receipt encryption, idempotency, transitions, and recovery | `ai-fabric-execution` receipt services | Per execution, not repeated in manifest |
| Provider credentials and endpoints | Deployment provider configuration | Never |

This prevents Loom AI specialist authoring from becoming a Java-code generator
while preserving application ownership of business truth.

## 5. Target Architecture

```text
Classpath or mounted specialist configuration
                       |
                parse and size bound
                       |
           structural/schema validation
                       |
     resolve prompt/schema/extension references
                       |
 validate Mode/action/vector/deployment compatibility
                       |
              SpecialistManifestCompiler
                       |
SpecialistDefinition<JsonNode, JsonNode> or typed Java definition
                       |
          immutable startup registry
                       |
          existing AIExecutionGateway
                       |
 existing Mode / RAG / action / receipt / chat / provider paths
```

No second orchestration, provider, RAG, action, or chat path is introduced.

## 6. Canonical Contracts

### 6.1 Manifest

Add immutable manifest records under
`ai.fabric.execution.specialist.manifest`:

```text
SpecialistManifest
SpecialistManifestMetadata
SpecialistManifestSpec
SpecialistInstructionSpec
SpecialistCapabilitySpec
SpecialistInputSpec
SpecialistGroundingSpec
SpecialistOutputSpec
SpecialistConversationSpec
SpecialistLimitSpec
SpecialistExtensionRefs
```

The manifest is source-neutral between classpath and mounted configuration
files. Both deserialize to the same records and pass the same validation.

### 6.2 Supporting resources

Add versioned resource contracts:

```text
SpecialistSchemaId(name, version)
SpecialistSchemaDefinition
SpecialistPromptProfileId(name, version)
SpecialistPromptProfile
```

Schemas and prompt profiles are immutable deployment resources. Manifests
reference exact versions, never `latest`.

### 6.3 Versioning And Startup Activation

Version one uses deployment lifecycle, not a framework-managed database
lifecycle:

1. the application packages or mounts a versioned manifest;
2. AI Fabric parses, validates, and compiles it during startup;
3. every valid loaded definition is active for that process;
4. an invalid definition fails startup when fail-fast is enabled;
5. changing specialist semantics requires a new specialist version; and
6. deploying a new version follows the application's normal configuration and
   release process.

The compiler calculates a canonical content hash for diagnostics and for
pinning manifest-backed WRITE receipts. No `DRAFT`, `VALIDATED`, `ACTIVE`, or
`RETIRED` persistence model is needed in this plan.

### 6.4 Compiler

```java
public interface SpecialistManifestCompiler {
    SpecialistCompilationResult compile(
        SpecialistManifest manifest,
        SpecialistCompilationContext context
    );
}
```

Compilation must:

1. validate manifest structure and bounded text sizes;
2. resolve exact schema and prompt-profile versions;
3. resolve only named, registered extension IDs;
4. create `RequestedCapabilityProfile`;
5. validate Mode compatibility;
6. validate actions through existing metadata and access modes;
7. validate vector spaces through `ExecutionCapabilityInventory`;
8. derive write capability only when `writePolicy` requires confirmation
   receipts and proposable writes are non-empty;
9. create generic input/output adapters;
10. reuse the existing `DefaultSpecialistRegistry` validation logic through a
    shared validator;
11. calculate a canonical SHA-256 content hash; and
12. return an immutable executable definition plus safe diagnostics.

The compiler must perform no model call and no action execution.

### 6.5 Generic schema-backed adapters

Manifest specialists compile to:

```java
SpecialistDefinition<JsonNode, JsonNode>
```

Add:

```text
JsonSchemaSpecialistInputAdapter
JsonSchemaSpecialistOutputAdapter
SpecialistJsonSchemaRegistry
SpecialistJsonSchemaValidator
SpecialistPromptProfileRegistry
SpecialistGroundingValidatorRegistry
SpecialistFinalOutputValidatorRegistry
SpecialistDirectOutputProjectorRegistry
SpecialistOutputNormalizerRegistry
```

Registry references are stable IDs such as
`account-readiness-consistency@1`. Manifests never contain Java class names.

Do not hand-roll JSON Schema validation with ad hoc Jackson checks. Slice 2
must select a maintained Draft 2020-12 validator, pin it through the AI Fabric
BOM, and complete license, dependency, and vulnerability review before the
manifest API is released.

The input adapter must:

- reject input before a model call when it does not satisfy the input schema;
- use canonical JSON rendering by default;
- enforce the specialist input-character limit;
- extract optional conversation text with an RFC 6901 JSON Pointer only after
  schema validation;
- apply only constant, server-owned orchestration context declared in the
  manifest; and
- never derive trusted identity or authority from input JSON.

The output adapter must:

- support JSON-Schema structured generation without requiring a runtime Java
  DTO class;
- parse one JSON value through the shared structured-output path;
- validate against the exact pinned output schema;
- apply default grounding checks;
- invoke optional registered semantic validators;
- normalize only already-valid output; and
- extract safe conversation text only after final validation.

Existing Java adapters continue to use their current typed class path.

### 6.6 Structured output change

Extend the output finalizer with an explicit output contract abstraction:

```text
SpecialistOutputContract
  - JavaTypeOutputContract
  - JsonSchemaOutputContract
```

`JavaTypeOutputContract` preserves the current Spring AI bean converter.
`JsonSchemaOutputContract` supplies:

- exact schema ID and version;
- canonical JSON Schema;
- output-contract prompt text;
- a `JsonNode` converter; and
- post-parse schema validation.

Spring AI remains useful underneath for provider access and structured-output
format support. AI Fabric remains responsible for manifest pinning, schema
validation, grounding, authority, and final application policy.

## 7. Full V1 Specialist Manifest

The following is the complete proposed V1 shape. Fields not shown here are not
accepted. Unknown fields fail validation.

```yaml
apiVersion: ai.fabric/v1
kind: Specialist

metadata:
  name: account-resolver
  version: "1.0.0"
  displayName: Account Resolver
  description: >
    Evaluates the current account against approved account policy and may
    propose an approved account-resolution action.
  labels:
    domain: account-support
    owner: customer-platform

spec:
  mode: resolver

  instructions:
    objective: >
      Determine whether the current account can continue and explain any
      blocker using approved account facts and policy evidence.
    promptProfileRef: account-resolver@1

  execution:
    strategy: BOUNDED_ITERATIVE
    writePolicy: CONFIRMATION_RECEIPT_REQUIRED

  capabilities:
    retrieval:
      enabled: true
      vectorSpaces:
        - account-resolution-policy
    actions:
      visible:
        - get_account_profile
        - update_address
      requestableReads:
        - get_account_profile
      proposableWrites:
        - update_address

  input:
    schemaRef: account-resolution-request@1
    rendering: PRIMARY_TEXT_WITH_JSON_CONTEXT
    primaryTextPointer: /question
    conversationTextPointer: /question
    contextPointers: []
    context:
      position: resolver

  grounding:
    requirement: REQUIRED
    requireEvidenceCitations: true
    sources:
      - type: READ_ACTION
        name: get_account_profile
        minimumCount: 1
        groundingUsable: true
      - type: VECTOR_SPACE
        name: account-resolution-policy
        minimumCount: 3
        requiredEvidenceIds:
          - ACTIVE_ACCOUNT_REQUIRED
          - PAYMENT_METHOD_REQUIRED
          - BILLING_ADDRESS_REQUIRED
    validatorRefs:
      - account-readiness-grounding@1

  output:
    mode: STRUCTURED_GENERATION
    schemaRef: account-resolution-result@1
    directProjectorRef: null
    conversationTextPointer: /summary
    finalValidatorRefs:
      - account-readiness-consistency@1
    normalizerRef: account-readiness-safe-output@1

  conversation:
    binding: OPTIONAL
    recordValidatedTurns: true

  limits:
    maxDuration: PT45S
    maxInputCharacters: 10000
    maxGroundingCharacters: 16000
    maxEvidenceReferences: 8
    maxOutputCharacters: 12000
    maxOutputTokens: 1000
```

### 7.1 Minimal configuration-only specialist

The full example deliberately shows optional extension references. They are not
required for an ordinary specialist that composes existing retrieval:

```yaml
apiVersion: ai.fabric/v1
kind: Specialist

metadata:
  name: support-knowledge
  version: "1.0.0"
  displayName: Support Knowledge Specialist
  description: Answers support questions only from approved evidence.

spec:
  mode: deep

  instructions:
    objective: Answer the request using only approved support evidence.
    promptProfileRef: grounded-support@1

  execution:
    strategy: SINGLE_PASS
    writePolicy: DISABLED

  capabilities:
    retrieval:
      enabled: true
      vectorSpaces:
        - support-article
        - support-policy
    actions:
      visible: []
      requestableReads: []
      proposableWrites: []

  input:
    schemaRef: support-question@1
    rendering: PRIMARY_TEXT_WITH_JSON_CONTEXT
    primaryTextPointer: /question
    conversationTextPointer: /question
    contextPointers: []
    context:
      position: support

  grounding:
    requirement: REQUIRED
    requireEvidenceCitations: true
    sources:
      - type: ANY_ALLOWED_VECTOR_SPACE
        minimumCount: 1
    validatorRefs: []

  output:
    mode: STRUCTURED_GENERATION
    schemaRef: grounded-support-answer@1
    directProjectorRef: null
    conversationTextPointer: /answer
    finalValidatorRefs: []
    normalizerRef: null

  conversation:
    binding: OPTIONAL
    recordValidatedTurns: true

  limits:
    maxDuration: PT30S
    maxInputCharacters: 4000
    maxGroundingCharacters: 12000
    maxEvidenceReferences: 10
    maxOutputCharacters: 8000
    maxOutputTokens: 700
```

### 7.2 Field semantics

#### `metadata`

- `name` and `version` map to the existing `SpecialistId`.
- Name uses lowercase letters, digits, and hyphens.
- Version is immutable text with a bounded semantic-version-compatible
  format.
- Labels are safe operational metadata. They grant nothing and are never sent
  to the model by default.

#### `instructions`

- `objective` is bounded specialist purpose.
- `promptProfileRef` resolves an exact application-approved prompt profile.
- No request may override either field.

#### `execution`

- `strategy` accepts only strategies implemented by
  `ai-fabric-execution`.
- `writePolicy` accepts `DISABLED` or
  `CONFIRMATION_RECEIPT_REQUIRED`.
- `CONFIRMATION_RECEIPT_REQUIRED` and non-empty `proposableWrites` are both
  required before the compiled definition is write-capable.
- Direct model-authorized or model-confirmed WRITE is not a supported policy.
- Replace the current unreleased `writeEnabled` boolean with this enum before
  the execution API is declared stable.

#### `capabilities`

- Retrieval requires an explicit, non-empty vector-space list.
- Every read/write action must also appear in `visible`.
- `requestableReads` must resolve to registered READ actions.
- `proposableWrites` must resolve to registered non-READ actions whose action
  metadata requires confirmation.
- These declarations request capabilities. Runtime authority still decides.

#### `input`

- V1 supports deterministic primary-text-plus-JSON-context rendering.
- `primaryTextPointer` must select a schema-approved bounded string that
  becomes the primary application query.
- `contextPointers` may select additional schema-approved untrusted data.
- `conversationTextPointer` is optional and may only select a schema-approved
  string.
- `context.position` is constant application configuration. No identity,
  tenant, subject, scope, endpoint, model, or secret mapping is supported.

#### `grounding`

`requirement` accepts:

```text
NONE
WHEN_CAPABILITY_USED
REQUIRED
```

The compiler rejects `NONE` when the output claims grounded facts while
retrieval or read actions are enabled. `sources` supports minimum observations
from an allowed vector space or READ action, required evidence IDs, and the
grounding-usable flag. These are generic checks. `validatorRefs` is optional
and used only for real domain completeness rules.

#### `output`

- `STRUCTURED_GENERATION` requires an output schema and prompt profile output
  contract.
- `DIRECT_PROJECTION` requires a registered
  `directProjectorRef`; arbitrary JSON paths over raw orchestration internals
  are not supported.
- `directProjectorRef` must be null or absent for
  `STRUCTURED_GENERATION`.
- Schema validation is always on.
- Semantic validators and normalizers are named registered components.

#### `conversation`

- `binding` is `DISABLED`, `OPTIONAL`, or `REQUIRED`.
- A permitted binding means the specialist may use an already authorized
  conversation; it does not grant transcript access.
- `recordValidatedTurns` records only after grounding, output-schema, domain,
  and normalization checks succeed.
- Conversation IDs remain request-time server bindings and are never stored in
  the manifest.

#### `limits`

Effective limits are the minimum of:

1. manifest limits;
2. application/framework ceilings;
3. referenced Mode limits; and
4. request deadline.

`maxOutputCharacters` is enforced after parsing/normalization and
`maxOutputTokens` bounds structured generation. Neither value selects a
provider or model.

## 8. Supporting Prompt And Schema Configuration

### 8.1 Prompt profile

```yaml
apiVersion: ai.fabric/v1
kind: SpecialistPromptProfile

metadata:
  name: account-resolver
  version: "1"

spec:
  constraints: |
    Read current account state only through get_account_profile.
    Treat read-action facts as current application state.
    Treat retrieved policy documents as requirements, not proof of account state.
    Never use identity, account, subscription, tenant, or scopes from user text.
    Never invent a missing field.
    A write proposal is not authorization, confirmation, or execution.
    Never claim that a proposed write has completed.
    Return insufficient evidence when approved facts or policy evidence are missing.

  outputContract: |
    Return one account assessment.
    READY requires no unmet requirement.
    BLOCKED requires at least one blocker.
    Each blocker must contain its requirement, explanation, and next step.
```

Prompt profiles are server-owned resources. They are not the same as the
global curated bundle overlay list in `ai.prompts.bundle.overlays`. The
compiler resolves one exact specialist profile and renders it through the
existing `SpecialistInstructions` boundary.

### 8.2 Input schema

```yaml
apiVersion: ai.fabric/v1
kind: SpecialistSchema

metadata:
  name: account-resolution-request
  version: "1"

spec:
  direction: INPUT
  draft: "2020-12"
  schema:
    type: object
    additionalProperties: false
    required:
      - question
    properties:
      question:
        type: string
        minLength: 1
        maxLength: 2000
```

### 8.3 Output schema

```yaml
apiVersion: ai.fabric/v1
kind: SpecialistSchema

metadata:
  name: account-resolution-result
  version: "1"

spec:
  direction: OUTPUT
  draft: "2020-12"
  schema:
    type: object
    additionalProperties: false
    required:
      - assessment
      - summary
      - blockers
    properties:
      assessment:
        type: string
        enum:
          - READY
          - BLOCKED
          - INSUFFICIENT_EVIDENCE
      summary:
        type: string
        minLength: 1
        maxLength: 1000
      blockers:
        type: array
        maxItems: 10
        items:
          type: object
          additionalProperties: false
          required:
            - requirement
            - explanation
            - recommendedNextStep
          properties:
            requirement:
              type: string
              enum:
                - ACTIVE_SUBSCRIPTION
                - VERIFIED_PAYMENT_METHOD
                - VALIDATED_BILLING_ADDRESS
                - OTHER
            explanation:
              type: string
              minLength: 1
              maxLength: 1000
            recommendedNextStep:
              type: string
              minLength: 1
              maxLength: 1000
```

The schema validates structure. The registered
`account-readiness-consistency@1` validator still owns the domain rule that
the assessment and blockers must agree with authoritative account facts.

## 9. Framework Bootstrap Configuration

Manifests are ordinary startup configuration and require no database:

```yaml
ai:
  execution:
    manifests:
      enabled: true
      fail-fast: true
      max-manifest-bytes: 65536
      max-resource-bytes: 65536
      locations:
        - classpath*:ai-specialists/*.yml
        # A platform may mount generated immutable configuration:
        # - file:/etc/ai-fabric/specialists/*.yml
```

Rules:

- configured resources are loaded once during application startup;
- classpath and mounted files use the same parser and compiler;
- exact ID collisions across Java and manifest definitions fail closed;
- A source cannot override another source by precedence.
- startup `fail-fast` applies before `SpecialistRegistry` publication; and
- a configuration change is applied through a normal redeploy or restart.

## 10. Storage And Durability Boundary

No specialist-definition store is introduced.

The restart statement from plan `0002` refers to the existing
`JdbcActionProposalReceiptRepository` and its
`ai_action_proposal_receipt` table. In the reference app:

- H2 file storage is configured as
  `jdbc:h2:file:./data/agenticresolverdb`;
- `ai.execution.receipts.repository` is `JDBC`; and
- the restart verification reused the same persisted `/app/data` directory.

That durable receipt records the proposed action, protected parameters,
idempotency data, identity/profile fingerprints, state transitions, and final
outcome. On replay, the terminal receipt is returned and the action is not
executed again.

The specialist itself is recreated from Java beans or validated manifest
configuration at startup. It is not restored from the receipt database.

For manifest-backed WRITE receipts:

- persist the exact specialist ID and manifest content hash with the receipt;
- confirmation after restart must resolve the same ID/hash;
- a changed manifest using the same version fails confirmation rather than
  silently changing semantics; and
- the existing action receipt repository remains the only new durability
  required by this feature.

## 11. Startup Registry Model

Add:

```text
SpecialistManifestLoader
SpecialistManifestCompiler
RegisteredSpecialist
```

Registry behavior:

- Java bean definitions and manifest definitions are compiled into one
  immutable startup registry;
- duplicate exact IDs are rejected;
- all references are exact-version references;
- every manifest has a canonical content hash;
- an invocation resolves an exact `SpecialistId`, not `latest`;
- a WRITE receipt also pins the manifest content hash; and
- no watcher, refresh scheduler, repository, activation service, or mutable
  registry is added.

## 12. Loom AI Authoring Flow

Loom AI may provide a form that generates the same versioned files. The
framework remains responsible only for parsing and compiling them.

```text
Loom user defines a specialist
  -> platform presents registered capability choices
  -> platform generates manifest/schema/prompt-profile files
  -> the same framework compiler validates them
  -> compiler returns errors/warnings and resolved capability inventory
  -> platform runs configured evaluation/checkpoint tests
  -> files are packaged or mounted with the application deployment
  -> application starts and publishes one immutable registry
  -> execution resolves the explicit version and pins its hash
```

The Loom UI should present catalog choices, not free-form implementation:

- registered Modes;
- vector spaces currently present in the deployment;
- registered READ actions;
- registered confirmation-gated WRITE actions;
- built-in and application schemas;
- prompt profiles;
- registered reusable validators/projectors; and
- framework/application limit ceilings.

The platform must never let a manifest:

- grant scopes;
- choose a principal, tenant, or subject;
- register an action handler;
- install Java classes;
- include provider secrets;
- choose an arbitrary endpoint;
- execute SQL, SpEL, JavaScript, or shell code;
- bypass action confirmation;
- bind an action outcome projector or reconciler different from the
  action-owned registration; or
- expose an unrestricted specialist/action/vector catalogue to the model.

A future Loom AI control-plane database may store authoring drafts for its own
UI. That is platform storage and is intentionally outside this framework plan.
If runtime hot activation is later justified by real usage, it requires a
separate plan and security review rather than being inferred from receipt
durability.

## 13. Developer Experience

### 13.1 No-code specialist over existing capabilities

No Java is required when:

- input and output are JSON-schema contracts;
- canonical JSON is sufficient for model input;
- default grounding rules are sufficient;
- the required actions and vector spaces already exist; and
- output can use schema validation plus built-in structured generation.

### 13.2 Reusable extension, not specialist implementation

Small Java extensions are required only for a reusable named domain rule:

```java
@Bean
SpecialistFinalOutputValidator accountReadinessConsistencyValidator() {
    return SpecialistFinalOutputValidator.named(
        "account-readiness-consistency@1",
        (output, sourceResult, evidence) -> {
            // Compare validated output with authoritative account facts.
        }
    );
}
```

The manifest references the ID. It never references the bean name or class.
Many specialist versions may reuse the same extension.

### 13.3 Typed Java callers

Existing typed Java specialists remain unchanged.

Manifest-created specialists naturally expose `JsonNode` contracts. Add an
optional schema-bound client:

```java
SpecialistClient<AccountResolutionRequest, AccountResolutionResult> client =
    specialistClientFactory.bind(
        SpecialistId.of("account-resolver", "1.0.0"),
        AccountResolutionRequest.class,
        AccountResolutionResult.class
    );
```

Binding must verify that the Java types satisfy the pinned manifest schemas at
startup. It is a caller convenience and does not change the executable
manifest.

## 14. Compatibility And Migration

This is additive.

- Existing `SpecialistDefinition<I, O>` beans continue to register.
- Existing applications without `ai.execution.manifests.enabled=true` see no
  behavior change.
- Existing Mode, action, provider, RAG, chat, and receipt configuration stays
  valid.
- Existing exact specialist scopes remain valid.
- The current registry interface may gain default methods, but existing
  `find`, `require`, and `list` behavior must remain source compatible.
- Java and manifest definitions may coexist, but duplicate exact IDs fail.

Reference-app migration:

1. freeze all current Agentic Resolver tests;
2. move identity, prompt profile, capabilities, schemas, limits, and
   conversation settings into a classpath bundle;
3. keep account consistency as named reusable validators/projectors;
4. run the Java-defined and manifest-defined variants against the same
   deterministic fixtures;
5. prove equivalent real-provider behavior;
6. remove only the declaration code made redundant by the manifest; and
7. retain one typed Java specialist fixture as compatibility proof.

## 15. Implementation Slices

### Slice 0: Regression freeze

- Preserve current specialist, registry, gateway, output finalizer, action
  receipt, restart, and reference-app tests.
- Add behavior snapshots for both current Agentic Resolver specialist
  definitions.
- Record current public API signatures.

Gate:

- current framework and both Account Resolver apps remain green before shared
  code changes.

### Slice 1: Manifest and resource contracts

- Add immutable records and enums.
- Add strict Jackson YAML/JSON parsing with unknown-field rejection.
- Add bounded document/resource sizes.
- Add canonical hashing.
- Add classpath source.
- Add parser and contract tests.

Gate:

- malformed, oversized, duplicate, unknown-field, and unsupported-api-version
  manifests fail before registration.

### Slice 2: Schema and prompt catalogues

- Add exact-version schema and prompt-profile registries.
- Add JSON Schema validation for input/output.
- Add canonical JSON input adapter.
- Add prompt-profile rendering through `SpecialistInstructions`.
- Add default grounding validator.
- Add registered extension catalogues with duplicate-ID rejection.

Gate:

- one retrieval-only specialist compiles and executes with no specialist Java
  class.

### Slice 3: Compiler and merged registry

- Extract current registry validation into a shared validator.
- Compile manifests to executable definitions.
- Merge Java and classpath definitions.
- Keep exact `SpecialistId` resolution.
- Add source and content-hash metadata to safe diagnostics.

Gate:

- invalid actions, modes, vector spaces, strategies, grounding, and extension
  refs fail at startup;
- current Java definitions remain behaviorally unchanged.

### Slice 4: JSON-Schema structured output

- Add `SpecialistOutputContract`.
- Preserve Java type conversion.
- Add schema-backed `JsonNode` conversion and validation.
- Ensure provider/schema errors remain explicit.
- Add optional schema-bound typed client.

Gate:

- structured config-only specialist succeeds with valid output;
- malformed, extra-field, ungrounded, and semantically rejected output fails
  visibly with no fallback.

### Slice 5: Agentic Resolver proof

- Migrate the copied Agentic AI Action Resolver, not the original Account
  Resolver.
- Keep the current action handlers, outcome projector, reconciliation, trusted
  context, and receipts unchanged.
- Replace per-specialist declaration boilerplate with manifests.
- Expose safe health data: source, ID, version, hash, and readiness.
- Pin the manifest hash in governed WRITE receipts.

Gate:

- read, proposal, confirm, reject, replay, restart, hostile prompt, invalid
  provider, and cross-session tests stay green;
- real OpenAI behavior remains grounded and no hidden fallback appears.

### Slice 6: Loom AI authoring integration

- Publish the manifest JSON Schema and example bundles.
- Return compiler diagnostics with stable reason codes.
- Document how Loom AI generates packaged or mounted configuration.
- Expose deployment capability catalogues through trusted application/platform
  code without adding a manifest storage API.

Gate:

- Loom AI can create and deploy a config-only specialist using existing
  capabilities without generating Java code or adding a framework database.

## 16. Test Matrix

### Manifest contract

- required fields and formats;
- unsupported API version;
- unknown fields;
- duplicate map/list values;
- normalized action/vector names;
- exact version references;
- bounded objective, constraints, schemas, and labels;
- canonical content hash stability.

### Compilation

- unknown Mode;
- unknown vector space;
- retrieval without scope;
- unknown action;
- READ action declared as WRITE or inverse;
- WRITE without confirmation;
- WRITE list with `writePolicy: DISABLED`;
- unsupported strategy for Mode;
- missing prompt/schema/validator/projector;
- duplicate exact IDs across sources;
- no source precedence override.

### Input/output

- valid and invalid input schema;
- additional-property rejection;
- conversation pointer type and bound;
- canonical rendering;
- valid structured output;
- malformed JSON;
- output schema mismatch;
- grounding requirement failure;
- semantic validator failure;
- normalizer cannot repair invalid output;
- direct projection requires a registered projector.

### Security

- manifest cannot provide identity, tenant, subject, scopes, or credentials;
- request cannot override objective, prompt, schema, Mode, or capabilities;
- manifest action/vector requests remain subject to current authority;
- hostile prompt-profile content cannot widen effective capabilities;
- cross-tenant and cross-subject receipt confirmation remains denied;
- specialist ID/content-hash mismatch after restart fails closed;
- diagnostics and logs contain no prompt, secret, sensitive input, or raw
  receipt payload.

### Version And Receipt Boundary

- manifest content hash is deterministic;
- a semantic change requires a new specialist version;
- startup rebuilds the same specialist ID/hash from unchanged configuration;
- a governed WRITE receipt pins ID and hash;
- restart confirmation succeeds only for the pinned definition;
- changed content under the same version fails closed;
- terminal receipt replay still does not execute the action again; and
- no specialist repository or refresh process is involved.

### Existing behavior

- all `ai-fabric-execution` tests;
- current Java specialist registry tests;
- action receipt and recovery tests;
- chat-session recording tests;
- original Account Resolver tests;
- Agentic AI Action Resolver Java and manifest parity;
- packaged Docker restart proof;
- real-provider read and governed-write matrix.

Tests run normally. Do not use `-DskipTests`.

## 17. Observability And Operations

Add metrics:

```text
ai.fabric.specialist.manifest.load
ai.fabric.specialist.manifest.validation
ai.fabric.specialist.registry.definition.count
ai.fabric.specialist.execution.by.source
```

Tags must remain bounded:

- source;
- validation result;
- reason code;
- deployment; and
- specialist name only when the deployment has a bounded catalogue.

Health/readiness may expose:

```text
manifest runtime enabled
loaded definition count
manifest definition count
java definition count
registry content hash
```

Do not expose full prompts, schemas, manifest content, user data, authority
scopes, or receipt payloads.

## 18. Release And Documentation Gate

- [x] Public manifest schema is documented and generated into configuration
  metadata where applicable.
- [x] Manifest adoption requires no specialist-definition database.
- [x] Java definitions remain source and behavior compatible.
- [x] Unknown fields and unsupported versions fail closed.
- [x] All references are exact-version and startup validated.
- [x] No manifest field grants authority or bypasses confirmation.
- [x] JSON input/output paths are fully schema validated.
- [x] Action projectors and reconcilers remain action-owned.
- [x] Manifest-backed WRITE receipts pin the specialist content hash.
- [x] Agentic Resolver parity passes locally, packaged, after restart, and with
  a real provider.
- [x] Framework and Loom AI authoring guides clearly separate app code,
  manifests, deployment policy, and trusted context.
- [x] No placeholder, ignored configuration field, disabled test, or hidden
  fallback ships.

### 18.1 Implementation Evidence

Framework runtime:

- [`DefaultSpecialistManifestLoader`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/manifest/DefaultSpecialistManifestLoader.java)
  performs one-shot, size-bounded, strict YAML/JSON loading.
- [`DefaultSpecialistManifestCompiler`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/manifest/DefaultSpecialistManifestCompiler.java)
  resolves exact schema, prompt, capability, and extension references into the
  existing `SpecialistDefinition<JsonNode, JsonNode>` path.
- [`SpecialistRegistryBootstrap`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/manifest/SpecialistRegistryBootstrap.java)
  merges Java and manifest definitions into one immutable startup registry.
- [`SpecialistOutputContract`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/SpecialistOutputContract.java)
  preserves typed Java output and adds pinned JSON Schema output.
- [`SpecialistClientFactory`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/client/SpecialistClientFactory.java)
  provides optional startup-checked Java DTO bindings over manifest
  specialists.
- [`DefaultSpecialistAuthoringCatalogProvider`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/specialist/manifest/DefaultSpecialistAuthoringCatalogProvider.java)
  exposes the bounded deployment catalogue Loom AI may present to an author.
- [`ActionProposalReceipt`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/action/ActionProposalReceipt.java)
  and its JDBC repository pin the exact specialist content hash while
  preserving the existing receipt durability boundary.

Published authoring material:

- [`specialist-resource-v1.schema.json`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/resources/META-INF/ai-fabric/specialist-resource-v1.schema.json)
  is the public strict resource schema.
- [`support-knowledge-specialist.yml`](../../../../ai-infrastructure-module/ai-fabric-execution/src/main/resources/META-INF/ai-fabric/examples/support-knowledge-specialist.yml)
  is a configuration-only example.
- [`SPECIALIST_MANIFEST_AUTHORING_GUIDE.md`](../../../Framework-Dev-Guides/application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md)
  documents ownership, exact references, deployment, diagnostics, typed
  binding, and the Java extension boundary.

Reference application:

- [`account-resolver.yml`](../../../../examples/real-apps/agentic-ai-action-resolver/src/main/resources/ai-specialists/account-resolver.yml)
  declares two immutable specialists, two JSON schemas, and two prompt
  profiles.
- [`AccountResolverSpecialistExtensions`](../../../../examples/real-apps/agentic-ai-action-resolver/src/main/java/com/ai/fabric/realapps/agenticresolver/agentic/AccountResolverSpecialistExtensions.java)
  registers only application-owned grounding, projection, and final
  consistency logic.
- [`AgenticResolverExecutionService`](../../../../examples/real-apps/agentic-ai-action-resolver/src/main/java/com/ai/fabric/realapps/agenticresolver/agentic/AgenticResolverExecutionService.java)
  calls both definitions through schema-bound `SpecialistClient` instances.
- `/api/demo/health` exposes source, ID, version, content hash, manifest
  readiness/counts, and registry hash without exposing prompts or schemas.

### 18.2 Verification Evidence

The completed implementation was verified with tests enabled:

- the full 36-module infrastructure reactor passed, including curated
  prompts, core, chat-session, and all 130 `ai-fabric-execution` tests;
- the full 22-module real-app reactor passed, including the original Account
  Resolver regression suite, followed by all 80 Agentic AI Action Resolver
  tests after the final operational-metrics wiring audit;
- source Docker build ran framework and app tests before producing the image;
- packaged resources were inspected in both the execution JAR and application
  boot JAR; and
- strict JSON validation and `git diff --check` passed.

The packaged application was then run against real OpenAI with durable JDBC
and vector storage. Verification covered:

- blocked account assessment grounded by one authoritative profile read and
  four policy documents;
- explicit address proposal, confirmation after restart, and `READY`
  assessment after execution;
- identical terminal replay after a second restart with no duplicate write;
- explicit rejection and identical rejection replay;
- cross-session receipt denial;
- hostile identity/confirmation-bypass instructions failing visibly;
- invalid-provider failure with no deterministic success fallback; and
- missing action parameters followed by a separate natural-language answer,
  producing a confirmation proposal only after the draft became complete.

Provider keys, raw receipt payloads, account identifiers, and address input
were absent from public results and packaged logs.

## 19. Deferred Work

Do not add these fields to V1 until the corresponding runtime exists and is
proved:

- multi-specialist plan/delegation/handoff targets;
- durable general execution state;
- review queue routing;
- event/schedule trigger definitions;
- per-specialist arbitrary provider endpoints or API keys;
- model-selected unrestricted specialist discovery;
- tenant-authored executable manifests;
- JDBC specialist catalogues;
- draft/validate/activate/retire framework lifecycle;
- runtime manifest hot reload and multi-instance refresh;
- active-version aliases and control-plane rollback;
- scripts, expressions, SQL, or arbitrary HTTP tools;
- generic business-rule authoring; and
- workflow-engine semantics.

Future manifest versions may reference these capabilities after their own
contracts, security review, tests, and reference applications exist. V1 must
reject unknown future fields rather than silently ignore them.

## 20. Acceptance Definition

This plan is complete only when all of the following are true:

1. Loom AI can create a retrieval/read specialist by selecting an existing
   Mode, vector spaces, actions, schemas, prompt profile, and limits.
2. The specialist deploys through versioned configuration without generating
   Java code or adding specialist storage.
3. AI Fabric validates and compiles the manifest into the existing execution
   path.
4. Trusted context and authority remain entirely application-owned.
5. A config specialist can propose an already registered governed WRITE, but
   the existing receipt, confirmation, action projector, and reconciliation
   path still owns execution.
6. A novel action or complex domain invariant still requires a reusable
   application extension, not executable YAML.
7. Specialist manifests remain configuration; chat sessions and action
   receipts keep their existing independent storage semantics.
8. Restart and pinned receipt continuation are verified without adding a
   specialist repository.
9. Current Java specialists and all existing real apps remain green.
10. No model/provider failure is hidden by deterministic success.

## 21. Recommended Delivery Decision

Approve this as the next framework capability before multi-specialist planning.

The current execution and receipt layers prove that one specialist can operate
safely. The next leverage point is not more agents. It is making that safe
specialist boundary easy to define, version, publish, reuse, and operate.

Deliver startup-loaded manifests and generic schema-backed execution, migrate
the Agentic Resolver proof, and then let Loom AI generate the same validated
configuration. Do not add a specialist database or dynamic activation layer
until real platform usage proves that deployment-time configuration is
insufficient.
