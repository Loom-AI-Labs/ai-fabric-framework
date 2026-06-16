# AI Fabric Framework — Release Readiness & Cleanup Plan

- **Date:** 2026-06-15
- **First release version:** `0.1.0` (published to Maven Central)
- **Current release version:** `0.2.0` (naming normalization — see note below)
- **groupId:** `io.github.loom-ai-labs`
- **Target repository:** `loom-ai-labs/ai-fabric-framework`
- **Reviewed branch:** `claude/framework-release-check-3tma5g`
- **Release channel:** Maven Central (Sonatype Central Portal) — see `docs/MAVEN_CENTRAL_RELEASE_GUIDE.md`

> **0.2.0 naming normalization (post-0.1.0):** unified all artifactIds under one prefix
> (`ai-infrastructure-*` → `ai-fabric-*`, `ai-curated-*` → `ai-fabric-curated-*`) and renamed the
> Java base package `com.ai.infrastructure.*` → `ai.fabric.*` (plus `com.ai.curated.*` →
> `ai.fabric.curated.*`). This is a breaking change, hence the minor bump. Public class names
> (e.g. `EnableAIInfrastructure`) were intentionally left unchanged. Example apps keep their own
> packages (`com.ai.fabric.realapps.*`, `com.subscription.hub.*`); only their framework imports moved.

---

## 1. Verdict

**Can we release? — Yes. All blockers and recommended cleanup are now applied.**

The framework is in good shape: the code compiles, the module reactor is internally
consistent, there are no leaked secrets, and the OSS governance/documentation set is
complete. Two blockers were found and **both are now fixed** in branch
`claude/framework-release-check-3tma5g`:

- **B1** — published-coordinate URLs pointed at a personal account (`mahmoudashraf`) instead
  of the release org; GitHub Packages would reject `mvn deploy`. Now `loom-ai-labs`.
- **B2** — a full `mvn clean install` (with tests) failed on **4 pre-existing stale tests**
  left over from the monorepo split. Root-caused as test-only issues (production logic was
  correct) and fixed; the full reactor build is now green.

All recommended cleanup (C1–C5) is also applied. Remaining items are optional post-release
polish (O1–O3).

| Area | Status |
|------|--------|
| Full reactor build (`mvn clean install -DskipTests`, Java 21) | ✅ **PASS** — all 33 modules build & install |
| Module/version consistency (`0.1.0-preview`, `com.ai.fabric`) | ✅ PASS |
| Secret / credential scan | ✅ PASS — no real secrets, only `sk-test-key` placeholders |
| Leaked internal/private references | ✅ PASS — runtime/managed-product code cleanly removed |
| OSS governance (LICENSE, SECURITY, CONTRIBUTING, COC) | ✅ PASS |
| Examples (`minimal-spring-boot`, `real-apps` x11) | ✅ PASS — env-var config, correct versions |
| CI workflows + provider scripts | ✅ PASS (functional) |
| **Published coordinates point at correct org** | ✅ FIXED — now `loom-ai-labs` |
| **Full test build (`mvn clean install`, all modules + tests)** | ✅ **FIXED** — 4 pre-existing stale tests corrected; reactor green |
| Stale `run-real-api-tests.sh` reference | ✅ FIXED |
| Tests run in CI (`framework-verify.yml`) | ✅ FIXED — `-DskipTests` removed |
| Maven publishing completeness (source/javadoc jars) | ✅ FIXED — `release` profile adds source+javadoc |
| CHANGELOG / release notes | ⚠️ Optional (post-release) |

---

## 2. What was verified

### Build (live, with network)
- `mvn -f ai-infrastructure-module/pom.xml clean install -DskipTests` → **BUILD SUCCESS** (exit 0).
- Toolchain: **Java 21**, **Spring Boot 3.2.0**, `maven-compiler-plugin` 3.13.0, consistent across all modules.
- No `SNAPSHOT` dependencies anywhere.
- All 33 `<module>` entries in the parent BOM resolve to real directories with correct
  `<parent>` references; the parent module list and the release workflow's
  `FRAMEWORK_MODULES` list are aligned.

### Tests
- **192 test classes** across 30 of 33 modules.
- Two integration tests and `run-real-api-tests.sh` need live external services/API keys —
  these are correctly gated and not part of the default build.
- **CI currently builds with `-DskipTests`** — see §4.

### Security / leakage
- No real API keys, tokens, private keys, `.env` files, or customer data.
- Only placeholder test values (`sk-test-key`, `sk-test-integration`).
- `@author` tags are all the generic "AI Infrastructure Team" — no personal names/emails.
- The earlier split (commit `fc8d3b3`, "Move deployable runtime services out of framework
  repo") cleanly removed runtime/managed-product code; no dangling imports or pom references
  to removed modules remain.

---

## 3. Release blocker (MUST fix before tagging)

### B1 — Published coordinates point at a personal account, not the release org

All Maven coordinate/SCM/distribution URLs reference `github.com/mahmoudashraf/...`. For a
release under `loom-ai-labs/ai-fabric-framework` these must be updated, otherwise
`mvn deploy` to GitHub Packages fails (the Packages registry binds the artifact to the
owning repo, and the deploy URL owner must match).

**7 occurrences to change `mahmoudashraf` → `loom-ai-labs`:**

| File | Line | Field |
|------|------|-------|
| `ai-infrastructure-module/pom.xml` | 15 | `<url>` (project) |
| `ai-infrastructure-module/pom.xml` | 18 | `<scm><connection>` |
| `ai-infrastructure-module/pom.xml` | 19 | `<scm><developerConnection>` |
| `ai-infrastructure-module/pom.xml` | 20 | `<scm><url>` |
| `ai-infrastructure-module/pom.xml` | 389 | `<distributionManagement><repository><url>` |
| `docs/GITHUB_PACKAGES_RELEASE_GUIDE.md` | 7 | Maven registry URL |
| `docs/GITHUB_PACKAGES_RELEASE_GUIDE.md` | 61 | consumer `<repositories>` example |

> Decision needed: confirm `loom-ai-labs` is the final public org (the GitHub MCP scope and
> branch config both indicate it is). If a different org/name is intended, substitute that
> instead.
>
> **Status: FIXED** — all 7 references now point at `loom-ai-labs`.

### B2 — `mvn clean install` / `mvn clean verify` fails: 4 pre-existing test failures

A full build *with tests* fails in `ai-fabric-core` (`Tests run: 359, Failures: 3`). The
failures are deterministic (not environment/flaky, no Docker or network involved) and exist
on `main` independently of any cleanup in this branch:

| Test | Symptom |
|------|---------|
| `IntentHandlingStepBatchTargetsTest.shouldDefaultMcpCartAddItemsFromProductVariantMetadata` | `add_items` not populated from resolved target metadata → actual is null |
| `IntentHandlingStepBatchTargetsTest.shouldReplaceInvalidBatchItemWithResolvedTargetMetadataWhenSchemaConstrained` | same batch-target replacement path → actual is null |
| `MultiStepIntentExtractionStrategyTest.shouldExposeAndPreserveOptionalPresentationParamsDuringFill` | generated fill prompt text no longer matches the expected template |

Why it matters:
- `CONTRIBUTING.md` instructs contributors to run `mvn -f ai-infrastructure-module/pom.xml clean verify` — which **fails out of the box**.
- CI currently masks this by building with `-DskipTests` (see C3).
- The published *artifacts* still compile and deploy; this does not block producing packages, but it is a real quality signal for a public release.

**Root cause (resolved): all three were stale tests, not production regressions.**

- The two `IntentHandlingStepBatchTargetsTest` cases declare a schema whose
  `product_variant_id` property carries `pattern("^commerce://resource/ProductVariant/[0-9]+$")`
  and `evidenceBound(true)`, but then fed a resolved-target metadata value of
  `commerce://product-variant/1`, which cannot match that pattern. The production
  `IntentHandlingStep.normalizeBatchStringValue` *correctly* rejects the malformed value, so
  `add_items` is left null. Sibling tests that use a valid `commerce://resource/ProductVariant/<digits>`
  id pass. **Fix:** corrected the 4 fixture/assertion occurrences to a pattern-valid id
  (`commerce://resource/ProductVariant/1`). Test-only; no production logic changed.
- `MultiStepIntentExtractionStrategyTest` asserted the **commerce** pack's fill-params wording
  ("For catalog/search actions…"), but `ai-fabric-core` resolves the **default** pack
  (`ai-curated-default` `v1`), whose template reads "For search/read actions…". **Fix:**
  aligned the assertion with the default template's actual wording. Test-only.

A fourth pre-existing failure was masked behind the core failures (the reactor stops at the
first failing module): `RelayOpenApiContractTest` in `ai-infrastructure-relay` errored with
`OpenAPI spec not found on disk … changes/Productization/customer-connector-api.openapi.yml`.
The spec was never part of this repo (it lived in the private monorepo under `Productization/`),
and this test file was the **only** place the private `Productization/` path name appeared in
the public repo. **Fix:** the contract test now skips gracefully (JUnit `Assumptions`) when the
spec is absent and the private `Productization/` path hints were removed; it still runs wherever
the spec is present. Test-only.

**Status: FIXED** — the four previously-failing tests now pass/skip; `mvn clean install` (with
tests) is green, which unblocks C3.

---

## 4. Recommended cleanup (should fix before / shortly after release)

### C1 — Stale `run-real-api-tests.sh` reference
`ai-infrastructure-module/run-real-api-tests.sh` runs
`mvn test -Dtest=AIInfrastructureRealAPITest -Dspring.profiles.active=real-api-test`, but
the class `AIInfrastructureRealAPITest` and the `real-api-test` profile no longer exist in
the repo (removed during the split). The script will fail with "No tests found".
**Fix:** repoint it to the surviving real-API tests (e.g. `EmbeddingProviderIntegrationTest`)
or remove/rewrite the script. *(Low effort)*

### C2 — Maven publishing completeness (source + javadoc jars)
The parent POM configures no `maven-source-plugin` or `maven-javadoc-plugin`, so published
artifacts ship without `-sources.jar` / `-javadoc.jar`. This is tolerated by GitHub Packages
but degrades the consumer IDE experience and is **mandatory if Maven Central is ever a
target**. **Fix:** add both plugins (ideally behind a `release` profile). GPG signing is only
needed for Maven Central, not GitHub Packages. *(Medium effort)*

### C3 — CI does not run tests — *FIXED (unblocked by B2)*
`framework-verify.yml` previously built the reactor with `-DskipTests`, so the public CI gave
no test signal on PRs. With B2 resolved, the full reactor build (`mvn clean install`, all
modules, all tests) is green locally, so **the `-DskipTests` flag was removed from the reactor
build step** — CI now compiles, tests, and installs. Real-API/integration tests remain
self-gating (they skip without credentials/specs), and the example-compile steps are unchanged.

### C4 — POM dependency hygiene
Minor, non-blocking:
- `ai-infrastructure-module/pom.xml:435` — `mapstruct-processor` hardcodes `1.5.5.Final`;
  use `${mapstruct.version}`.
- `ai-infrastructure-core/pom.xml` — `jsr305:3.0.2` and `spring-cloud-context:4.0.4`
  hardcoded; promote to BOM `dependencyManagement` / properties.
- `ai-infrastructure-relay/pom.xml` — `swagger-request-validator-mockmvc:2.40.0` (test) hardcoded.
- `surefire 3.0.0` hardcoded in a couple of child modules; centralize via a property.

*(Low effort, quality only.)*

### C5 — SECURITY.md has no concrete reporting channel
SECURITY.md says "report privately to the repository owner" but gives no mechanism.
**Fix:** enable GitHub Private Vulnerability Reporting and/or add a security contact address.
*(Low effort.)*

---

## 5. Optional / nice-to-have (post-release OK)

- **O1 — `CHANGELOG.md`** documenting the `0.1.0-preview` contents and known limitations.
- **O2 — Issue / PR templates** under `.github/ISSUE_TEMPLATE/` and a PR template.
- **O3 — `victor-databases/` directory typo.** The directory is misspelled ("victor" vs
  "vector"); the artifacts inside are correctly named `ai-infrastructure-vector-*`. Renaming
  is **cosmetic but public-facing** and touches the parent POM `<module>` paths and the
  release workflow `FRAMEWORK_MODULES` list. Defer unless desired — low value, non-trivial
  blast radius. If done, do it as an isolated commit and re-run the full build.
- **O4 — Two `TODO`s in example code** (`sub-management-hub/.../BehaviorEventService.java`,
  `core/.../AnnotationFieldScanner.java`) — legitimate future-work markers, leave as-is.

---

## 6. Step-by-step release plan

**Phase 1 — Unblock (required) — ✅ done in this branch**
1. ✅ **B1**: replaced all 7 `mahmoudashraf` references with `loom-ai-labs`.
2. ✅ **B2**: fixed 4 pre-existing stale tests; `mvn -f ai-infrastructure-module/pom.xml clean install`
   (all modules + tests) is green.
3. Commit + push to `claude/framework-release-check-3tma5g`; open a PR into `main`.

**Phase 2 — Recommended cleanup — ✅ done in this branch**
4. ✅ Fixed `run-real-api-tests.sh` (**C1**).
5. ✅ Added source/javadoc plugins behind a `release` profile, wired into the deploy workflow (**C2**).
6. ✅ Enabled tests in `framework-verify.yml` (**C3**).
7. ✅ POM hygiene (**C4**) and SECURITY contact (**C5**).

**Phase 3 — Release to Maven Central** (publish target chosen: Maven Central, groupId
`io.github.loom-ai-labs`, version `0.1.0`). Repo-side prep is **done** (see §8); the remaining
steps need maintainer credentials.

8. **Maintainer provides credentials** (one-time, as GitHub Actions repo secrets):
   - `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` — Sonatype Central Portal user token
     (after registering and verifying the `io.github.loom-ai-labs` namespace).
   - `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE` — a GPG key for artifact signing.
   - Full steps in `docs/MAVEN_CENTRAL_RELEASE_GUIDE.md`.
9. Merge this branch to `main`. Confirm `framework-verify.yml` (build + tests) is green.
10. Tag and push: `git tag ai-fabric-framework-v0.1.0 && git push origin ai-fabric-framework-v0.1.0`.
11. Create a GitHub Release from the tag → triggers `maven-central-release.yml`, which GPG-signs
    and deploys all modules (with `-sources`/`-javadoc` jars) to Maven Central via the
    `central-publishing-maven-plugin` (`autoPublish` releases automatically).
12. Validate consumption: from a clean machine with no extra repo config, import `ai-fabric-bom`
    `0.1.0` and build `examples/minimal-spring-boot`.

**Phase 4 — Post-release polish**
13. Add CHANGELOG (**O1**), issue/PR templates (**O2**), and decide on the
    `victor-databases` rename (**O3**).

---

## 7. Task checklist

| ID | Task | Priority | Effort | Status |
|----|------|----------|--------|--------|
| B1 | `mahmoudashraf` → `loom-ai-labs` (7 refs) | P0 | Low | ✅ Done |
| B2 | Fix 4 pre-existing stale tests (core ×3, relay ×1) | P0 | Med | ✅ Done |
| C1 | Fix stale `run-real-api-tests.sh` | P1 | Low | ✅ Done |
| C2 | Add source + javadoc jar plugins (`release` profile) | P1 | Med | ✅ Done |
| C3 | Run tests in CI (remove `-DskipTests`) | P1 | Med | ✅ Done |
| C4 | POM version-hygiene cleanup | P2 | Low | ✅ Done |
| C5 | Concrete security reporting channel | P2 | Low | ✅ Done |
| O1 | `CHANGELOG.md` | P2 | Low | Open (post-release) |
| O2 | Issue / PR templates | P3 | Low | Open (post-release) |
| O3 | Rename `victor-databases/` → `vector-databases/` | P3 | Med | Open (post-release) |
| O4 | Example `TODO`s | — | — | Leave |
| R1 | Set version → `0.1.0` and groupId → `io.github.loom-ai-labs` | P0 | Med | ✅ Done |
| R2 | Add `<developers>`, `central` profile (GPG + central-publishing) | P0 | Med | ✅ Done |
| R3 | `maven-central-release.yml`; remove GitHub Packages workflow/guide | P0 | Low | ✅ Done |
| R4 | Provide Sonatype + GPG secrets, tag, release | P0 | Low | ⏳ Needs maintainer creds |

**Bottom line:** **B1, B2, all C-items, and the Maven Central prep (R1–R3) are done.** The full
reactor builds and tests green at groupId `io.github.loom-ai-labs` / version `0.1.0`, and the
`release,central` profiles resolve and validate. The **only** remaining step is **R4**: you add
the four Sonatype/GPG secrets, then tag + release — the workflow publishes to Central
automatically. I cannot perform R4 because it requires your credentials.

---

## 8. Release execution — prepared vs. pending

**Prepared in this branch (no credentials needed):**
- Coordinates: groupId `io.github.loom-ai-labs`, version `0.1.0` across all 47 POMs + README/docs.
- POM: `<developers>` block; `release` profile (source+javadoc); `central` profile (GPG sign +
  `central-publishing-maven-plugin`, `autoPublish`). GitHub Packages `distributionManagement` removed.
- CI/CD: `maven-central-release.yml` (signs + deploys to Central on a GitHub Release); obsolete
  `ai-fabric-framework-github-packages-release.yml` and its guide removed to prevent double-publish.
- Docs: `docs/MAVEN_CENTRAL_RELEASE_GUIDE.md` (consume + one-time setup + release steps).
- Verified: full `mvn clean install` (tests) green; `mvn -Prelease,central validate` green.

**Pending — requires the maintainer (you):**
- Register/verify the `io.github.loom-ai-labs` namespace on https://central.sonatype.com and
  generate a user token → add secrets `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`.
- Create a GPG key, publish the public key, export the private key → add secrets
  `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`.
- Merge to `main`, then `git tag ai-fabric-framework-v0.1.0` + create the GitHub Release.

I can drive the tag/release once the secrets exist (I can dispatch the workflow); I cannot
create the Sonatype account, the GPG key, or add repository secrets.

---

## 9. Honest capability assessment (2026-06-16, at `0.2.1`)

Evidence-based review: source/test sizes measured, integrations and annotation processors
verified in code, full test suite run (passes), and all 11 example apps booted.

### Scale & substance
- ~96,700 lines of main Java; ~31,800 lines of test (≈0.33 test:code ratio).
- `ai-fabric-core` is the real mass: 50k LOC / 288 classes / 80 test files / 17k test LOC.
- Incompleteness is low: 1 TODO/FIXME, 3 `UnsupportedOperationException`, only 5 "not
  implemented"-style throws. The 222 "stub/placeholder" grep hits were noise (208 are query/prompt
  parameter placeholders; the rest are legitimate no-op beans).

### Capability-by-capability verdict
| Capability | Real? | Evidence | Grade |
|---|---|---|---|
| Core abstractions + auto-config | Yes | 50k LOC, exercised by every example | Solid |
| Annotation programming model | Yes (not decorative) | Real processors: `AICapableProcessor` (360 LOC), `AnnotationFieldScanner`, `AnnotationMetadataEntityConfigRegistrar`, `AIActionRegistry`, `AnnotatedAIActionHandler`, `ActionMethodArgumentBinder` | Solid — key differentiator |
| LLM providers (OpenAI/Anthropic/Cohere/Gemini/Azure) | Yes | All make real `HttpClient` calls, ~1k LOC each | Solid surface, thin tests (1 test file each) |
| Relationship-query (NL→JPQL) | Yes | 6k LOC main / 4.4k test — best-tested after core | Solid |
| Vector stores (Qdrant/Pinecone/Weaviate/Milvus/Lucene/Memory) | Yes | Real `io.qdrant`/`io.milvus`/pinecone/weaviate/`lucene` clients | Real but lightly tested (Lucene/Memory 0 tests; Milvus 49 test LOC) |
| RAG, indexing, migration, data-sync | Yes | 1–2k LOC each + annotation-driven indexing | Partial; depth varies |
| **Orchestration / intent pipeline** | Yes — core & deep | `intent/` is **~22.7k LOC / 107 files** (≈¼ of the framework); 18-step Spring-wired Chain-of-Responsibility pipeline (`DefaultOrchestrationPipeline`, `RAGOrchestrator`); multi-strategy intent extraction (~3k LOC); **57 test files / ~14.4k test LOC, ≈0.64 ratio — ~2× the framework average** | **Solid — strongest/most differentiated subsystem** |
| Actions, governance, PII, chat-session, behavior | Yes | Substantive (governance 3.2k, chat 4.4k, behavior 1.7k LOC); action model has authz + confirmation + bounded-facts | Partial→Solid, area-dependent |

> **Correction (2026-06-16, after a direct re-assessment of the orchestrator):** an earlier draft of
> this section folded orchestration into the actions row and treated it as a "lighter," area-dependent
> capability. That under-rated it. Measured properly, the intent/orchestration subsystem is the
> framework's largest and best-tested area and the place where the "governed AI" claims are actually
> enforced: the pipeline runs security analysis → access control → PII detection → compliance →
> multi-strategy LLM intent extraction → intent handling (action execution / RAG) → metadata → smart
> suggestions → response sanitization → history persistence, with per-step skip/timing/error-isolation
> and early termination, and is extended simply by registering a `PipelineStep` Spring bean. This is a
> genuine differentiator versus generic Java AI toolkits and should be graded **Solid**.

> **Second correction (2026-06-16, deeper pass on connectors / action config / MCP / interception):**
> the first pass also missed material capabilities in the actions/connector surface. Adding them here:
>
> - **Dual action models.** Actions can be defined either as annotated Java (`@AIAction` +
>   `@ActionExecute`/`@Param`/`@ActionAllowed`/`@ActionConfirmation`) **or** as declarative
>   **connector** config (`ConnectorActionDefinition`, prefix `ai.actions.connector.*` /
>   `ai.actions.*`), both merged into one `AIActionRegistry` via the `AIActionRegistryContributor`
>   SPI. Config actions support typed/`allowedValues`/`sensitive` params, **evidence-bound** params
>   (`evidenceBound`/`evidenceKeys`/`evidenceFallbackPolicy`), post-execution policies, and **signed
>   webhook targets** (`urlSecretRef`/`signingSecretRef`).
> - **MCP (Model Context Protocol) tool actions.** Connector actions have an `adapterType` of
>   `webhook` or `mcp-tool` plus an `mcpServers` map; `mcp-tool` actions execute through a configured
>   **MCP execution gateway** (`ai.actions.connector.mcp-gateway.*`) with secret-ref resolution, and
>   MCP tool output is normalized into the bounded LLM "facts" payload. This is a current, topical
>   capability that the first assessment entirely missed.
> - **Confirmation-interception engine** (`intent/action/confirmation/`): rules + triggers + stack
>   policy + decision types (`EXECUTE_ACTION`/`PROMPT_ACTION`/`REPLY`), resolved from three sources —
>   annotations (`@OnPendingActionConfirmation`), config, and connectors — not just the two chat
>   annotations named earlier.
> - **Retrieval connector** (`RetrievalConnectorRAGProvider`): RAG can be backed by an external
>   retrieval connector over a protocol, not only the local vector store.
> - **Security analysis** (`security/` package → pipeline `SecurityAnalysisStep`): prompt-injection /
>   malicious-request blocking as a first-class pipeline step.
>
> Net effect on the verdict: the governed, extensible **actions/connector + MCP** surface is a
> stronger differentiator than first credited. Grade it **Solid**. Two consecutive corrections (this
> and the orchestrator) also indicate the framework's depth is concentrated in the orchestration/
> action layer and is easy to under-read from module names alone.

### Full-sweep capability map (2026-06-16)

A systematic sweep (every `@ConfigurationProperties` prefix, every SPI, every auto-configuration)
confirmed further depth that name-level reading misses. 32 config prefixes, ~24 SPI extension points,
28 registered auto-configurations.

- **Enterprise data governance** (`ai-fabric-governance`, 3.2k LOC, prefix `ai.governance`): GDPR
  user-data **deletion** (`UserDataDeletionService`/`UserDataDeletionProvider`), **retention**
  policies + `RetentionCleanupScheduler`, **compliance** checks (`AIComplianceService` + pipeline
  `ComplianceCheckStep`), an **index catalog** (JPA + vector) for audit/scan, and a
  `GovernanceVectorDatabaseServiceDecorator` that enforces governance transparently on the vector
  store. **Differentiated** — the incumbents do not ship this.
- **Three action-definition sources** unified in one registry: annotated Java (`@AIAction`),
  declarative connector config (`ai.actions.connector.*`), and a **DB-backed, REST-managed registry**
  (`ai.actions.db`, `ConnectorActionRegistryController`, Liquibase schema) for runtime-managed actions
  — on top of the MCP-tool adapter and signed webhooks already noted.
- **Vector-space routing** (`ai.rag.vectorspace-routing`) for segmented/multi-tenant retrieval.
- **Pluggable everything via SPIs:** chat memory (`ChatSessionStorageProvider`, `MemoryStrategy`),
  RAG (`AdvancedRAGProvider`, `rag.source.SearchSource`, retrieval connector), security
  (`SecurityAnalysisPolicy`), access control at entity/chat/relationship layers, compliance,
  retention, deletion.
- Plus orchestration **attachments** (multimodal), **intent history**, **post-action generation**,
  **smart suggestions**, **response sanitization**, and a `relay` module (rate limiting/transport).

### Verdict: is this just a redundancy for existing Java AI frameworks?

**No — it is a different category, not a redundant clone.**

- **Redundant on the basics** (and less mature): provider abstraction, embeddings, vector stores,
  basic RAG, function/tool calling. Spring AI and LangChain4j do all of this, are more mature, and
  are better distributed. For "call an LLM + do RAG from Java," AI Fabric adds little over them.
- **Genuinely differentiated above the basics:** Spring AI / LangChain4j are *unopinionated
  libraries* that hand you primitives and leave the application architecture, governance and request
  lifecycle to you. AI Fabric is an *opinionated, governed AI-application framework* — a 18-step
  orchestration pipeline with security/access-control/PII/compliance/sanitization built in; a
  three-source, MCP-capable, interception-governed **actions platform**; declarative JPA-style entity
  annotations; NL→JPQL over your own data; and enterprise data-governance (GDPR deletion, retention,
  audit). That layer is **not** provided by the incumbents.

So the honest framing is "Spring/Rails-for-AI-apps" vs. "AI client libraries" — overlapping
foundations, different ambition. The differentiation is real and technical, not cosmetic.

**Caveats (unchanged):** differentiated ≠ adopted. (1) The opinionation that differentiates it is also
a barrier — many teams prefer composing unopinionated libraries to avoid lock-in, and AI Fabric also
competes with "roll your own on top of Spring AI." (2) Maturity gaps remain (a shipped `0.2.0` bug,
uneven edge test coverage). (3) Distribution/community, not code, will decide attention. Net: not
redundant, but its path to adoption runs through a sharp "governed AI-app framework" wedge + a hardened
1.0, not through matching the incumbents feature-for-feature.

> **Assessment honesty note:** this verdict followed *three* upward revisions of perceived depth
> (orchestrator, then connectors/MCP/interception, then governance/DB-registry/SPIs). The initial
> "probably redundant" read was too dismissive of the technical differentiation; the market caveats
> above still stand.
| Curated packs | Yes (resource/prompt assets) | 0 Java by design | Thin by design |

### Annotation programming model (standout)
14 annotations with working processors form a declarative, JPA-style model:
- Domain: `@AICapable`, `@AISearchable`, `@AIContext`, `@AISmartValidation`, `@AIProcess`.
- Actions/tools: `@AIAction`, `@ActionExecute`, `@Param`, `@ActionAllowed` (authz),
  `@ActionConfirmation` (human-in-the-loop), `@ActionFacts` (bounded data to the LLM).
- Chat confirmation: `@AIConfirmationInterceptors`, `@OnPendingActionConfirmation`.
- Bootstrap: `@EnableAIInfrastructure`.
The action model's built-in authorization, confirmation, and data-minimization are genuinely
enterprise-oriented and not something generic frameworks provide out of the box.

### Maturity signals (the honest mix)
- **Good:** low TODO density; real integrations throughout; well-tested core + relationship-query;
  coherent declarative model with working processors; new CI smoke gate boots all 11 examples.
- **Concerning:** a shipped-version bug (`0.2.0` component-scan broke multi-module apps, fixed in
  `0.2.1`); order-fragile tests; examples that couldn't boot until the smoke profile was added; an
  incomplete rename; **uneven coverage** — core and relationship-query are well-covered, but
  providers and vector stores have a single test file each (some zero), which is where regressions
  will hide.

### Bottom line
Real and substantively implemented — not vaporware, not a thin wrapper. The capability surface
mostly exists in working code; the **governed orchestration pipeline and the annotation model are the
standouts**; the core is solid and tested.
What it lacks is maturity and edge coverage. **Verified:** code present, integrates real clients,
compiles, passes tests, all examples boot. **Not verified (needs creds/data/traffic):** runtime
*quality* — relationship-query plan quality on messy schemas, cloud vector stores against live
services, response quality under load. Strong on "the capabilities are genuinely built," unproven
on "battle-hardened at scale."

### Highest-leverage next steps for the 1.0 story
1. Add focused tests for the least-covered, highest-risk areas: **LLM providers** and **vector
   stores** (the smoke gate now protects boot wiring; these protect behavior).
2. Harden the order-fragile test patterns surfaced during the rename.
3. Pick one sharp differentiator to lead with — the **governed orchestration pipeline** (security /
   access-control / PII / compliance / intent → action / RAG, extensible via `PipelineStep` beans),
   relationship-query, or the annotation + action model — rather than competing feature-for-feature
   with Spring AI and LangChain4j.
