# MCP Native Action Schema Support: 010.69 Handoff

Status: MAIN approved immutable 0.8.48 publication on 2026-11-11 following the
post-correction five-test/eight-turn real-provider pass. Publication and clean
public consumption are tracked separately from local qualification.
Baseline: `49a93e22da1605310efc169c21a6de07d2973a98`, with released framework
`0.8.47` (`965b2773ca4128e2b139de5bcac5f40c37fba1e6`). MAIN reviewed the
bounded helper/dispatch diff and then authorized the explicit-null correction,
version 0.8.48, local full qualification and external consumer builds.
MAIN later issued PUBLISH GO for this reviewed candidate. Hosted deployment
remains MAIN-owned; no earlier published release may be rewritten.

## Scope And Ownership

This change supports imported business capabilities, not autonomous agents.
Platform owns MCP discovery, customer connection ownership, credentials,
tool authorization, schema refresh, and live integration. The framework owns
preserving executable schemas, exposing their public input contract to the
model, and validating resolved inputs before execution.

Managed MCP uses the existing `McpActionExecutor` SPI. The runtime bridge may
handle exactly configured `execution.mcp.connectionId` actions and return
`MCP_TOOL_NOT_AVAILABLE` for actions outside its ownership. Existing explicit
native/gateway paths and gateway base-url/api-key configuration remain intact.
There are no new authentication APIs, services, schedulers, or recovery loops.

## Catalogue Contract

Optional action-level `inputSchema` and `outputSchema` contain native JSON
Schema objects. Omitted fields preserve the existing simplified action
contract. An empty schema object is a valid explicitly supplied schema.
There is no requirement for a literal root `type: object`: omitted root type,
root `oneOf`, and root nullable type unions are supported where the maintained
validator accepts the actual argument object. Output schemas are not forced
to describe objects; array-shaped output metadata is also tested.

An exact action-level boolean `enabled: false` is skipped by the catalogue
loader before registration. Omitted `enabled` and `enabled: true` preserve the
existing enabled behavior. This adds no record field. The disabled capability
does not reach action metadata/planners or handler lookup. Platform must still
enforce disabled policy at its managed MCP execution proxy.

Optional `inputSchema: null`/`outputSchema: null` are treated as absent, not as
empty schemas. This preserves the existing simplified parameter validation;
only an explicit `{}` opts into an empty native schema contract.

```yaml
actions:
  - name: inspect_request
    description: Inspect the signed-in customer's request
    accessMode: READ
    groundingEligible: false
    readActionResolutionEligible: true
    adapterType: mcp-tool
    inputSchema:
      type: object
      properties:
        query:
          type: [string, 'null']
        scopeKey:
          type: string
      required: [query, scopeKey]
      additionalProperties: false
    outputSchema:
      type: object
      properties:
        accepted: {type: boolean}
      required: [accepted]
    params:
      - name: scopeKey
        type: STRING
        required: true
        askUser: false
        visibility: INTERNAL
        resolveFrom: {source: RUNTIME_CONTEXT, field: userId}
    execution:
      mcp:
        connectionId: customer-owned-connection
        toolName: request.inspect
```

`scopeKey` is an arbitrary illustrative schema property, not a built-in actor
name. The upstream server must enforce the business authorization associated
with the trusted context. Schema validation does not authenticate a user or
establish ownership by itself.

## Preservation, Guidance And Validation

- The loader and `ConnectorActionDefinition` preserve native schema values,
  including nullable unions, numeric enums, decimal bounds, and local refs.
- Both schemas reach `AIActionMetaData`, prompt-visible `ActionInfo`, and
  `runtimeActionConfig`. Existing constructors and simplified forms remain.
- Main intent prompts, multi-step parameter collection, completion prompts,
  read-action planning, and Spring AI tool definitions receive the native
  public input schema rather than a lossy simplified type projection.
- Trusted hidden parameters remain in the full execution schema but are
  removed from the model's collection schema. Their existing `params`
  metadata continues to declare `resolveFrom`, visibility, and evidence policy.
- Draft validation retains structural size/type bounds but does not require
  missing native fields prematurely. The full executable input schema is
  validated at actual dispatch after the caller's normal resolution and
  confirmation gates, and before connector or MCP SPI dispatch. Proposal
  preflight does not run full native validation. No values are invented or
  coerced to satisfy it. A real `IntentHandlingStep` regression covers
  incomplete collection and replacement of model-supplied context by the
  existing `RUNTIME_CONTEXT` resolver before governed dispatch.
- Output schemas are preserved, not applied to the framework's `ActionResult`
  envelope. Platform/MCP adapters own validation of upstream structured output.
- Validation uses networknt `3.0.0`, aligned with the current MCP SDK. The native
  and existing specialist helpers cross the Jackson 2/3 boundary using the
  validator's standard JSON-string APIs; public framework DTO types stay
  unchanged. This is JSON serialization/parsing, not semantic text matching or
  fallback. Only explicitly qualified Draft 2020-12 and Draft 07
  dialects are accepted; omitted dialect defaults to 2020-12. The registry
  pins those two dialects, clears all resource loaders, and allows only
  bundled metaschema resource IDs. Unknown/file/classpath `$schema` values
  are rejected at root or nested schema locations. Refs must be local URI
  fragments; schema validation never fetches a URL or arbitrary file.
- Every new hard check protects an executable receiver contract or the
  no-external-resource boundary. There is no semantic keyword classifier,
  replacement answer, automatic search-all, or model decision override.

## Write Retry Contract

`mcp-tool` `WRITE_ONLY` and `READ_WRITE` connector dispatch use one attempt by
default, including uncertain transport exceptions and retryable error results.
Generating an idempotency key does not prove the receiver deduplicates calls.
The standard HTTP retry policy and bounded MCP READ retries are unchanged.

Only an explicitly qualified server-owned receiver contract permits the
configured connector retry count:

```yaml
execution:
  mcp:
    receiverDeduplication:
      qualified: true
      contractId: receiver-deduplication-contract-v1
      keyTransport: CONNECTOR_REQUEST_IDEMPOTENCY_KEY
```

Qualification must prove the gateway/receiver carries the connector envelope's
unchanged `idempotencyKey` to a durable deduplication boundary. The declaration
does not implement deduplication or extend MCP itself. Leave it absent for
unqualified public/business servers. The custom runtime SPI bridge owns its
single outbound call and any internal retry behavior; the framework does not
wrap that SPI execution in an automatic retry loop.

## Initial Reviewed Files

This inventory describes the first reviewed native-schema diff, before the
approved null correction and 0.8.48 version alignment. The current Git diff
and release notes are authoritative for the complete candidate.

Production paths under `ai-infrastructure-module/`:

- `ai-fabric-core/pom.xml`: existing managed networknt dependency.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionMetaData.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionInfo.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionNativeSchemaSupport.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionParameterContractValidator.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/RegisteredActionsAIActionProvider.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/invocation/DefaultGovernedActionInvocationService.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/tool/AIActionToolCallbackFactory.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/EnrichedPromptBuilder.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/extraction/MultiStepIntentExtractionStrategy.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/extraction/CompletionIntentExtractionStrategy.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/information/ReadActionResolutionService.java`.
- `ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/ActionMetadataVisibilitySupport.java`.
- `ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionDefinition.java`.
- `ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionCatalogLoader.java`.
- `ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionMetadataMapper.java`.
- `ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ActionConnectorExecutor.java`.

Focused test classes: `ActionNativeSchemaSupportTest`,
`NativeConnectorActionContractTest`, `ActionConnectorExecutorTest`,
`ConnectorActionCatalogLoaderTest`,
`AIActionToolCallbackFactoryTest`, `MultiStepIntentExtractionStrategyTest`,
`ReadActionResolutionServiceTest`, `ActionContextParamResolutionSupportTest`,
`DefaultGovernedActionInvocationServiceTest`, and `IntentHandlingStepBatchTargetsTest`.
Fixtures: `ai-fabric-actions-connector/src/test/resources/actions/native-mcp-actions.yml`
and `ai-fabric-actions-connector/src/test/resources/actions/enabled-mcp-actions.yml`.

## Earlier Verification Before The Null Correction

The post-review scoped reactor passed on 2026-11-11: **1,619 tests**, zero
failures, errors, or skips (14 curated-default, 991 core, 96 chat-session,
445 execution, 73 connector). This included PostgreSQL Testcontainers
lease-transition tests and the optional-null-schema compatibility regression.
The final focused run passed **171 tests** (122 core, 49 connector), zero
failures, errors, or skips, against the final production source, including the
last dispatch-order check before the action-start guard. Covered cases include
empty-schema/root-union/non-object-output contracts, same-named nested property
preservation, reference-looking literal data, pinned offline dialect resources,
actual invocation/resolution, incomplete collection, native proposal preflight,
and disabled-action registration.
Counts from separate runs overlap and must not be added as unique tests.

Commands:

```bash
mvn -B --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-actions-connector,ai-fabric-execution -am \
  -Dai.vector-db.lucene.cleanup-on-close=true test
.github/scripts/validate-framework-release-guards.sh
git diff --check
```

Final focused command:

```bash
mvn -B --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-actions-connector -am \
  -Dtest=ActionNativeSchemaSupportTest,DefaultGovernedActionInvocationServiceTest,IntentHandlingStepBatchTargetsTest,ConnectorActionCatalogLoaderTest,NativeConnectorActionContractTest,ActionConnectorExecutorTest,AIActionToolCallbackFactoryTest,MultiStepIntentExtractionStrategyTest,ReadActionResolutionServiceTest,ActionContextParamResolutionSupportTest,RegisteredActionsAIActionProviderTest,ActionMetadataVisibilitySupportTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Initial diff inventory: **30 files**: 17 production/build paths, ten Java test
classes, two test fixtures, and this handoff document. Existing source was
preserved; all edits remain uncommitted in the assigned framework worktree.

The seven repository release guards passed. No guard was weakened or broadly
allowlisted: local-reference checking uses a URI parser, not semantic matching.
The scoped module test run includes core, chat session, execution, and connector
regressions without replacing locally installed published artifacts.

### Approved Explicit-Null Correction

MAIN authorized closing the proven null-loss gap. Explicitly supplied nulls
are now retained through Intent, extraction, action context, read planning,
draft continuation/submission, confirmation JSON and encrypted receipts.
Required public native nulls are treated as present but remain subject to full
schema validation at dispatch. No field is synthesized from absence, and
required hidden/evidence-bound context is not waived. Durable native proposals
use the same validator and bind native schemas into the existing receipt hash.
Regressions cover null versus absent, nested array positions, draft overwrite,
ordinary extraction and confirmation-to-dispatch.

The first full candidate build caught a read-planner compatibility regression.
It is corrected: native proposals retain explicit null while simplified read
actions retain their existing omission of optional null planner parameters.
Both behaviors now have dispatch-level regressions. A focused candidate run
passed 65 tests (51 core, 14 execution), without failures, errors or skips.

Trusted-binding projection is qualified for top-level property contracts,
including public nested/local-reference values. Complex root-reference or
conditional schemas depending on hidden bindings need additional qualification;
they must not be claimed as universally safe public collection projections.

MAIN reviewed and authorized correction of the private READ binding gap.
`ActionRuntimeContextParamSupport` extracts the existing runtime-value lookup
from `ActionContextParamResolutionSupport`; ordinary resolution reuses it.
The read planner resolves only explicitly configured `RUNTIME_CONTEXT` fields
before required/provided validation, deduplication and execution. Model values
for those fields are overwritten, or removed when trusted context is absent.
No recursive READ_ACTION calls, attachment fallbacks, protocol special cases
or new authority sources are introduced. Dispatch regressions cover absent
and spoofed bindings, deduplication after resolution, missing trusted user
context and preservation of an explicitly null public value. The final full
candidate qualification must include this correction, not only the preceding
successful candidate build.

## Candidate Qualification And MAIN Provider Handoff

MAIN approved a bounded dependency correction after external qualification
found that adding networknt 2.0.1 to core overrode MCP SDK 2.0.0's required
networknt 3.0.0. The reference server failed before startup with
`Schema.validate(tools.jackson.databind.JsonNode)` missing. The BOM now pins
3.0.0 and only the two schema helpers use the JSON-string boundary. No schema
contract, resource-loader policy, authorization or fallback was changed. A
real MCP-server integration regression invokes native nullable validation in
the same application context. Post-correction qualification passed: fresh
30-module install (2,373 tests, no failures/errors/skips), seven release guards,
minimal clean compile, five agentic-consumer tests, and six reference-server
tests. The packaged SDK server booted, enforced authentication and discovered
all three tools. Corrected installed artifacts and the compiled provider
fixture are ready for MAIN's five-test/eight-turn real OpenAI rerun. No reactor
or integration-target Maven remains running at this handoff. Earlier provider
results do not replace that post-correction rerun.

Local qualification follows the full checklist: release-profile reactor,
integration-suite compilation, vector containers, external consumers, packaged
smokes and release guards. Results are recorded in `docs/release-notes/0.8.48.md`;
raw local logs live under ignored `logs/0.8.48-rc/`.

The candidate uses new `0.8.48` coordinates in
`/Users/mahmoudashraf/.m2/repository`, never replacement `0.8.47` artifacts.
MAIN must wait for the full candidate install verdict before using it. Consumer
invocations may explicitly select `-Dmaven.repo.local=/Users/mahmoudashraf/.m2/repository`
and `-Dai-fabric.version=0.8.48` where that consumer exposes the existing version
property. Do not use `-U` as evidence of Central publication.

2026-11-11 provider handoff checkpoint before the SDK dependency correction:
the then-current corrected 30-module install
passed (2,372 tests, zero failures/errors/skips), shared test support installed,
and direct integration-module test compilation passed. No framework Maven
process remains running. Further Maven qualification is held for MAIN's real
provider window; the broader container/external-consumer gates are not yet
claimed complete.

The provider action matrix uses fixed annotated test actions; it does not have
a native catalogue override covering this import contract. MAIN authorized one
focused `RealAPINativeActionSchemaIntegrationTest` on the existing
`TestApplication`/`RealAPITestSupport`. Its fixture catalogue and synthetic
`McpActionExecutor` record calls without real business mutations. The LLM is
not mocked. Five tests use at most eight user turns: public no-vector READ with
nested nullable data, private READ with a required trusted binding and public
null, missing required nullable selection followed by null and confirmation,
rejected WRITE, and declared no-confirmation WRITE. The private READ and writes
verify the existing trusted `RUNTIME_CONTEXT` binding. Missing OpenAI
credentials fail this explicitly selected gate, not skip it. No new app or
broad harness is introduced; connector/chat dependencies are test-scoped.

After the full candidate and shared test-support artifact are installed, MAIN
can privately inject `OPENAI_API_KEY` and `OPENAI_MODEL`, then run:

```bash
mvn -B --no-transfer-progress \
  -Dmaven.repo.local=/Users/mahmoudashraf/.m2/repository \
  -f ai-infrastructure-module/integration-Testing/integration-tests/pom.xml \
  -Dtest=RealAPINativeActionSchemaIntegrationTest test
```

The result must include all five tests with no skips; compilation alone is not
provider qualification. Logs state provider/model and result type/call counts,
not credentials. The deterministic private READ regressions additionally
prove actual planner-dispatch ordering and missing/spoofed-context handling.
MAIN's managed runtime bridge/live integration remains a separate consumer
check.

Release is not approved by local unit tests. MAIN owns real-provider/live
qualification, final review and publication approval, immutable publication,
and clean published-artifact consumption. No live integration verdict is
claimed by this framework-only change.

Verdict: **reviewed 0.8.48, publication authorized**. See
`docs/release-notes/0.8.48.md` for current gate results. Real-provider and live
Platform integration are separate MAIN-owned qualification. Published version
was `0.8.47` at the pre-publication checkpoint; candidate installs use new
`0.8.48` coordinates. Immutable tagging, signed publication and a fresh-cache
public consumer are the remaining release operations.
