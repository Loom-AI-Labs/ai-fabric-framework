# Specialist Manifest Authoring Guide

AI Fabric specialist manifests let a Spring Boot application compose an
existing Mode, retrieval spaces, registered actions, schemas, prompts, and
execution limits without adding a Java configuration class for every
specialist.

They do not create a second orchestration engine. A manifest compiles into the
same immutable `SpecialistDefinition` and runs through the existing
`AIExecutionGateway`.

## Choose The Right Path

Use a manifest when the specialist can be assembled from capabilities already
owned by the application:

- an existing AI Fabric Mode;
- registered vector spaces;
- registered READ actions;
- registered confirmation-gated WRITE actions;
- JSON input and output contracts;
- a bounded prompt profile;
- built-in grounding rules; and
- named, application-approved validators or projectors.

Use a Java `SpecialistDefinition<I, O>` or a named Java extension when you add:

- a new action or connector;
- a complex domain invariant;
- reconciliation with a system of record;
- a custom authoritative output projection; or
- a representation that cannot be expressed safely as JSON Schema.

Manifests are configuration, not executable business-rule files. They cannot
contain Java class names, SQL, scripts, arbitrary HTTP calls, provider
credentials, identity, tenant IDs, subjects, or authority scopes.

## Add The Module

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
  <version>${ai-fabric.version}</version>
</dependency>
```

The application must also include its normal AI Fabric provider,
orchestration, vector, and optional chat-session modules.

## Enable Startup Loading

Package YAML or JSON under `src/main/resources/ai-specialists`, or mount
immutable files at deployment time:

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
        # - file:/etc/ai-fabric/specialists/*.yml
```

The defaults scan `.yml`, `.yaml`, and `.json` files under
`classpath*:ai-specialists/`. Loading happens once during startup. A change is
activated through the application's normal rebuild or restart.

Keep `fail-fast: true` for shared and production deployments. With
`fail-fast: false`, invalid resources are omitted and readiness reports the
safe reason codes; it is intended for diagnostics, not partial production
activation.

## Author The Resource Bundle

A bundle normally contains:

1. one `SpecialistSchema` with `direction: INPUT`;
2. one `SpecialistSchema` with `direction: OUTPUT`;
3. one `SpecialistPromptProfile`; and
4. one `Specialist`.

Separate YAML documents with `---`. Every resource uses an exact immutable
`name@version` reference.

The complete config-only example is packaged at:

```text
META-INF/ai-fabric/examples/support-knowledge-specialist.yml
```

The editor and validation schema is packaged at:

```text
META-INF/ai-fabric/specialist-resource-v1.schema.json
```

Unknown fields, unsupported API versions, malformed exact IDs, duplicate
resources, oversized documents, missing references, and unsupported
combinations fail before registry publication.

## Define Input

V1 accepts `PRIMARY_TEXT_WITH_JSON_CONTEXT`:

```yaml
input:
  schemaRef: support-question@1
  rendering: PRIMARY_TEXT_WITH_JSON_CONTEXT
  primaryTextPointer: /question
  conversationTextPointer: /question
  contextPointers: []
  context:
    position: support
```

The input schema is validated before any provider call. JSON pointers use RFC
6901 syntax and may select only untrusted application data. The configured
`position` is constant server-owned orchestration context.

Never put `userId`, `tenantId`, `subjectId`, scopes, endpoint URLs, model names,
or secrets into the manifest or request JSON. Build those values in a
`TrustedExecutionContext` from authenticated server state.

## Request Capabilities

```yaml
capabilities:
  retrieval:
    enabled: true
    vectorSpaces:
      - support-article
      - support-policy
  actions:
    visible:
      - get_ticket
    requestableReads:
      - get_ticket
    proposableWrites: []
```

These values request capabilities; they grant nothing. At invocation time AI
Fabric intersects them with:

- Mode policy;
- deployment inventory;
- the action registry;
- registered vector spaces; and
- trusted caller authority.

The `SpecialistAuthoringCatalogProvider` exposes the bounded deployment
inventory to trusted platform or application code:

```java
SpecialistAuthoringCatalog catalog =
    specialistAuthoringCatalogProvider.catalog();
```

The catalogue is suitable for an authoring form. It is not an authorization
decision and should not be exposed as unrestricted model-selected discovery.

## Grounding

```yaml
grounding:
  requirement: REQUIRED
  requireEvidenceCitations: true
  sources:
    - type: ANY_ALLOWED_VECTOR_SPACE
      minimumCount: 1
      requiredEvidenceIds: []
      groundingUsable: false
  validatorRefs: []
```

Built-in grounding checks count only approved evidence and server-produced
READ observations within the effective capability profile. Evidence
references are returned separately in `AIExecutionResult`; the framework does
not trust model-invented citation identifiers.

Use a named `SpecialistGroundingValidator` only when the domain needs a rule
beyond bounded source counts and required evidence IDs.

## Produce Output

`STRUCTURED_GENERATION` asks the configured provider for one JSON value and
then validates it against the pinned output schema:

```yaml
output:
  mode: STRUCTURED_GENERATION
  schemaRef: grounded-support-answer@1
  directProjectorRef: null
  conversationTextPointer: /answer
  finalValidatorRefs: []
  normalizerRef: null
```

Malformed JSON, extra properties, schema mismatch, provider failure,
insufficient grounding, and semantic validation failure remain visible. AI
Fabric does not replace them with deterministic success.

Use `DIRECT_PROJECTION` only with a registered
`SpecialistDirectOutputProjector`. The projector converts an already approved
orchestration result into the schema-bound public output. It is application
code because it owns application truth.

A normalizer runs only after the output is valid and is followed by another
validation pass. It cannot repair an invalid decision or invent missing facts.

## Add Reusable Extensions

Register stable exact IDs, never Java class names:

```java
@Bean
SpecialistFinalOutputValidator accountConsistencyValidator() {
    return SpecialistFinalOutputValidator.named(
        "account-readiness-consistency@1",
        context -> accountPolicy.validate(
            context.output(),
            context.sourceResult()
        )
    );
}
```

Available extension registries are:

- `SpecialistGroundingValidator`;
- `SpecialistFinalOutputValidator`;
- `SpecialistDirectOutputProjector`; and
- `SpecialistOutputNormalizer`.

Duplicate IDs and missing references fail at startup.

## Govern Writes

A manifest may propose a WRITE only when all of these are true:

- the action already exists in `AIActionRegistry`;
- its access mode is not `READ`;
- its metadata requires confirmation;
- it appears in both `visible` and `proposableWrites`;
- `writePolicy` is `CONFIRMATION_RECEIPT_REQUIRED`;
- Mode, deployment, and trusted authority allow it; and
- the receipt service is configured.

The model cannot authorize, confirm, or execute the action. AI Fabric creates a
durable receipt that pins the specialist ID and content hash. Confirmation
re-resolves trusted identity, subject, tenant, authority, specialist content,
action schema, and idempotency before application action execution.

A semantic change must use a new specialist version. Changing content under an
existing version causes a pending confirmation to fail closed.

## Call A Manifest Specialist

Manifest specialists execute internally as `JsonNode`. Applications may call
them directly through `AIExecutionGateway`, or bind schema-compatible Java
records:

```java
record SupportQuestion(String question) {}
record SupportAnswer(String answer) {}

SpecialistClient<SupportQuestion, SupportAnswer> client =
    specialistClientFactory.bind(
        SpecialistId.of("support-knowledge", "1"),
        SupportQuestion.class,
        SupportAnswer.class
    );

AIExecutionResult<SupportAnswer> result = client.execute(
    new SupportQuestion("How do I reset MFA?"),
    trustedExecutionContext
);
```

Binding validates the Java record shape against the pinned input and output
schemas. It does not let the caller replace the manifest, Mode, prompt, or
capability set.

Use `SpecialistInvocation` when the request needs an authorized conversation
binding, deadline, or stable idempotency key.

## Conversation Memory

The manifest declares whether conversation binding is `DISABLED`, `OPTIONAL`,
or `REQUIRED`. It never contains a conversation ID.

When `recordValidatedTurns` is enabled, AI Fabric records the new user and
assistant turn only after grounding, schema, semantic, normalization, and
output-size validation succeed. The UI sends only the new message. Session
ownership and storage remain in `ai-fabric-chat-session`.

## Operations

`SpecialistManifestRuntimeStatus` safely exposes:

- whether manifest loading is enabled;
- readiness;
- total, Java, and manifest definition counts;
- one registry content hash; and
- bounded diagnostics containing reason, safe message, and source filename.

Metrics are emitted for load, validation, registry counts, and execution
source. Do not expose full manifests, schemas, prompt text, user data,
authority scopes, receipt payloads, or secrets through health endpoints.

Common reason codes include:

| Reason | Meaning |
| --- | --- |
| `RESOURCE_API_VERSION_UNSUPPORTED` | The resource does not use `ai.fabric/v1`. |
| `SCHEMA_ID_INVALID` | A schema name or version is not an exact bounded ID. |
| `PROMPT_PROFILE_ID_INVALID` | A prompt-profile name or version is invalid. |
| `SCHEMA_EXTERNAL_REFERENCE_FORBIDDEN` | A domain schema tried to resolve a non-local `$ref`. |
| `SCHEMA_REFERENCE_NOT_FOUND` | A manifest references an unregistered exact schema. |
| `PROMPT_PROFILE_REFERENCE_NOT_FOUND` | The exact prompt profile is absent. |
| `EXTENSION_REFERENCE_NOT_FOUND` | A named application extension is absent. |
| `DUPLICATE_SPECIALIST_ID` | Java and manifest sources declared the same exact specialist ID. |
| `WRITE_POLICY_INVALID` | A write was requested without receipt-backed confirmation. |
| `ITERATIVE_MODE_REQUIRED` | Bounded iteration was requested from a non-iterative Mode. |

## Verify Before Deployment

Run tests normally:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-execution -am test
```

For an application bundle, also verify:

1. startup succeeds with `fail-fast: true`;
2. invalid input causes no model call;
3. retrieval and READ grounding stay within allowed scopes;
4. malformed or extra-field output fails visibly;
5. a governed write requires an explicit decision;
6. reject and terminal replay do not execute;
7. restart confirmation resolves the same specialist content hash;
8. cross-principal, subject, and tenant decisions are denied; and
9. a real provider failure remains visible.

The independent reference implementation is:

```text
examples/real-apps/agentic-ai-action-resolver
```

It uses manifest-defined specialists while keeping account reconciliation,
trusted context, action handlers, safe outcome projection, and receipt
persistence in application code.

## Loom AI Boundary

Loom AI can build an authoring UI from `SpecialistAuthoringCatalogProvider`,
generate versioned resource files, validate them with the packaged public
schema, and deploy them through the application's normal configuration
pipeline.

V1 intentionally has no specialist database, draft/activate lifecycle, hot
reload, unrestricted tool discovery, or executable YAML. Static specialist
configuration, chat-session storage, and durable action receipts remain three
separate concerns.
