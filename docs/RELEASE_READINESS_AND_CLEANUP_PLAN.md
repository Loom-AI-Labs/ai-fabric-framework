# AI Fabric Framework — Release Readiness & Cleanup Plan

- **Date:** 2026-06-15
- **Version under review:** `0.1.0-preview`
- **Target repository:** `loom-ai-labs/ai-fabric-framework`
- **Reviewed branch:** `claude/framework-release-check-3tma5g`
- **Release channel:** GitHub Packages (Maven), plus a framework-only source archive on the GitHub Release

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

**Phase 3 — Release**
8. Merge to `main`. Confirm `framework-verify.yml` is green.
9. Pre-flight per `docs/GITHUB_PACKAGES_RELEASE_GUIDE.md`:
   ```bash
   mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml validate
   mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -DskipTests compile
   ```
10. Tag and push: `git tag ai-fabric-framework-v0.1.0-preview && git push origin ai-fabric-framework-v0.1.0-preview`.
11. Create the GitHub Release from the tag → triggers
    `ai-fabric-framework-github-packages-release.yml`, which verifies, deploys Maven
    artifacts to GitHub Packages, and uploads the source archive + SHA-256.
12. Validate consumption: from a clean machine, import the `ai-fabric-bom` and build
    `examples/minimal-spring-boot` against the published `0.1.0-preview` artifacts.

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

**Bottom line:** **B1 and B2 are fixed and all C-items are applied.** The full reactor build
with tests (`mvn clean install`, all 33 modules) is green, CI now runs tests, and the release
profile attaches source + javadoc jars. The framework is releasable as `0.1.0-preview`; only
the optional O-items remain for after the first release.
