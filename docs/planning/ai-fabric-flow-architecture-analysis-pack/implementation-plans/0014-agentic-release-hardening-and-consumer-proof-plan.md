# Agentic Release Hardening And Consumer Proof Plan

- **Status:** Implemented and verified
- **Date:** 2026-07-30
- **Framework baseline:** AI Fabric `0.4.0`
- **Candidate baseline:** `006c57c`
- **Implementation commit:** `c52b9628d892b6a1b053f3b4ded10dd84021a652`
- **Prerequisites:** Plans `0001` through `0013`
- **Target release:** AI Fabric `0.5.0`

## 1. Purpose

Close the gap between a verified framework reactor and a consumable agentic
release.

The execution module, manifests, governed writes, durable jobs, reviews,
dialogue ownership, managers, and bounded parallel reads are implemented. The
next risk is packaging and external adoption: a repository-internal build can
pass while a normal Spring Boot application cannot compile or run from the
published public artifacts.

This plan adds a standalone consumer proof and makes it part of automatic CI.
It does not add another agent runtime capability.

## 2. Required Proof Shape

Create `examples/agentic-execution-consumer` as a standalone Maven project:

- no framework reactor parent;
- no relative module dependency;
- AI Fabric version selected through the public BOM;
- explicit `ai-fabric-execution` dependency;
- ordinary Spring Boot application packaging;
- public typed sequential and parallel plan declarations;
- application-owned mappers and deterministic aggregator;
- a context-start test with AI Fabric runtime auto-configuration disabled; and
- a deterministic runtime test over the public coordinator contracts.

The runtime test must prove:

- two exact declared read branches overlap;
- `ALL_REQUIRED` returns one typed aggregate;
- traces preserve declaration order;
- parallel group and common source revision are visible; and
- no internal or package-private framework class is required.

## 3. Candidate Versus Published Verification

Two different gates are required.

### 3.1 Pre-publication candidate gate

Automatic CI first installs the framework reactor, then launches Maven against
the standalone consumer POM. The consumer resolves only installed artifacts;
it does not compile against reactor source directories.

This catches:

- missing BOM management;
- missing transitive dependencies;
- source-incompatible public contracts;
- absent classes or constructors in packaged JARs;
- Spring Boot classpath/startup problems; and
- runtime regressions in the bounded plan API.

### 3.2 Post-publication Maven Central gate

After `0.5.0` is published, run the same consumer in a fresh Maven local
repository with `-Dai-fabric.version=0.5.0`. Do not install the framework
reactor first.

This is the only valid proof that immutable Maven Central metadata, POMs,
sources, Javadocs, signatures, and transitive artifacts are complete. It
cannot be marked complete before publication.

## 4. CI Integration

Add one automatic framework workflow step after the framework install:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/agentic-execution-consumer/pom.xml \
  test
```

The step must run tests normally. It must not use `-DskipTests`,
`-Dmaven.test.skip`, a reactor `-pl`, or source-path classpath injection.

Keep the existing minimal consumer compile. It proves the general starter
shape; the new consumer proves the optional execution module and agentic plan
contracts.

## 5. Documentation

Update:

- the implementation-plan index;
- CI pipeline guidance;
- GitHub Actions verification guidance;
- the external consumer README;
- the Loom AI release candidate;
- release-gate commands and expected output.

The docs must distinguish:

- deterministic candidate artifact proof;
- real-provider reference-app proof; and
- post-publication Maven Central proof.

None may be presented as a substitute for another.

## 6. Test And Release Matrix

| Gate | Provider | Artifact source | Required now |
| --- | --- | --- | --- |
| Consumer compile/context/runtime | Deterministic test doubles | Installed candidate JARs | Yes |
| Agentic Resolver packaged smoke | Visible offline failure | Packaged candidate app | Yes |
| Agentic Resolver real scenarios | OpenAI | Packaged candidate app | Yes |
| Clean Central consumer | None | Maven Central `0.5.0` | After publish |
| Loom AI platform compile/smoke | Platform-configured | Published `0.5.0` | Before adoption |

## 7. Explicit Non-Goals

This plan does not:

- publish `0.5.0`;
- change existing execution semantics;
- add graph, loop, scheduler, or WRITE-parallel behavior;
- copy `smoke-support` into a public consumer;
- claim Maven Central success from a local repository;
- add provider keys to automatic CI; or
- replace the independent Agentic AI Action Resolver proof.

## 8. Acceptance Gate

1. [x] Standalone consumer uses only public Maven dependencies.
2. [x] Context startup passes from packaged candidate artifacts.
3. [x] Public sequential and parallel declarations compile.
4. [x] Deterministic parallel runtime proof passes.
5. [x] Automatic CI runs the consumer tests normally.
6. [x] Existing minimal consumer and framework gates remain intact.
7. [x] Documentation separates candidate, live-provider, and Central proof.
8. [x] `git diff --check` and release guards pass.
9. [x] Implementation is committed and pinned.
10. [x] Post-publication Central and Loom AI consumer gates remain explicitly
    open until the release exists.

## 9. Verification Evidence

The pre-publication candidate gate passed from the `006c57c` baseline:

- the framework dependency reactor installed packaged `0.4.0` candidate
  artifacts after 1,052 tests passed with zero failures, errors, or skips;
- a separate Maven invocation ran
  `examples/agentic-execution-consumer/pom.xml clean test`;
- the standalone Spring Boot context and public coordinator runtime tests both
  passed, for 2 tests with zero failures, errors, or skips;
- the runtime proof observed two overlapping read branches, one atomic typed
  result, declaration-order traces, one parallel group, and one common source
  revision;
- the framework release guards passed, including provider registry, workflow
  policy, release-document validation, production-stub scanning, and vector
  readiness tests; and
- no test-skipping flag, provider fallback, reactor parent, relative framework
  module, or source-path classpath injection was used.

The Maven Central and Loom AI platform rows in the release matrix remain open.
They require a published immutable `0.5.0` artifact and cannot be inferred
from this local candidate proof.
