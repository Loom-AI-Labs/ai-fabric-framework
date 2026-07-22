# NotebookLM Video Script: Operations And Release Readiness

## Production Instruction

Produce a ten-minute technical video for Java and Spring Boot developers. Use only this script.
Keep AI Fabric and the Support Knowledge Assistant central. Explain exact-artifact identity,
independent dependency readiness, durable state ownership, restart proof, bounded retention,
credential validation, and separate keyless versus live-provider evidence.

## Opening

Passing unit tests is not the same as proving a release. A Spring Boot process can start with the
wrong commit, an unreachable vector provider, volatile conversation memory, stalled indexing work,
or an accidentally disabled generation provider.

A production-ready AI Fabric application needs evidence about the exact artifact being deployed,
the dependencies it actually selected, the state that survives restart, the data cleanup is allowed
to remove, and the credentials required by enabled providers.

## Release Evidence Flow

Describe this diagram:

```text
exact Git commit
       |
       v
container build -> tests run normally -> source metadata embedded
       |
       v
non-root runtime artifact
       |
       v
process health + independent application readiness
       |
       v
seed, migrate, index, store conversation probe
       |
       v
restart the same artifact
       |
       v
verify source, vectors, sessions, jobs, and revision
       |
       v
bounded cleanup and failure probes
       |
       v
machine-readable release evidence
```

Every step must describe what was actually exercised. A skipped live-provider test is `NOT_RUN`,
not a pass.

## Exact Artifact Identity

The container build receives the source commit, source branch, and build time explicitly. The build
does not copy `.git` into the image. The OCI image revision and the application's safe health output
report the same commit.

The Docker build runs the normal test suite. It does not use `-DskipTests`. A multi-stage build
places only the packaged JAR and required runtime model assets in the final image, which runs as a
dedicated non-root user.

Deployment evidence must compare the expected Git commit, image revision, and runtime health
commit. A mutable branch name alone is not deployment identity.

## Health And Readiness Are Different

Actuator health answers whether the process is alive. Application readiness should report each
required dependency independently:

```text
build identity
application database
vector provider
chat-session storage
indexing queue
migration jobs
generation provider
```

One failing dependency must not erase diagnostics for the others. Readiness can expose safe counts,
provider names, transport, storage implementation, and source revision. It must not expose API
keys, passwords, raw prompts, PII, or full provider exceptions.

An optional disabled generation provider is not the same as a failed required provider. In the
keyless release profile, generation is explicitly disabled and reported as optional. If OpenAI is
selected and generation is enabled, its credential and model configuration become required.

## State Ownership Map

Classify state before writing restart or cleanup tests:

```text
application source rows       durable and application-owned
vector evidence               durable provider state, derived and rebuildable
chat sessions and turns       durable AI workflow state
pending actions or drafts     durable workflow state with expiry rules
migration jobs                durable operational state
indexing queue entries        durable retry and failure state
prompt resources              versioned application artifacts
caches and diagnostics        ephemeral and rebuildable
provider credentials          external secret-store state
```

This classification determines what restart must preserve and what retention may delete.

## Restart Proof

Before restart, seed application data, migrate and index evidence, and store a backend-owned chat
turn. Record readiness and counts. Stop and restart the same container configuration while retaining
database and Qdrant volumes.

After restart, prove:

- runtime commit and image revision are unchanged;
- source rows are still present;
- expected vectors are still retrievable;
- the conversation and its turn remain;
- migration and indexing state are readable;
- the quality scorecard still passes.

Restart proof should exercise actual persisted state, not reseed everything before checking.

## Bounded Retention

Retention is not a general reset button. It may delete expired course chat sessions and completed
migration or indexing records older than configured cutoffs. It must preserve application source
rows. Routine operational cleanup in this checkpoint also preserves reusable vector evidence;
reindex and vector replacement are separate procedures.

The cleanup response reports before, removed, and after counts for each eligible category, plus
proof that source and vector counts remain unchanged. Maintenance endpoints stay behind an
application-owned admin boundary and can be disabled by configuration.

## Provider Credential Validation

AI Fabric validates selected provider configuration during startup. When generation is disabled,
LLM validation is skipped deliberately. When OpenAI is selected for enabled generation, missing API
key, base URL, or required model configuration makes startup fail.

This fail-fast behavior prevents a deployment from looking healthy until the first user request.
The public result should show a safe validation failure, while secrets remain outside logs and
evidence artifacts.

## Keyless And Keyed Evidence

The required release gate uses local ONNX embeddings, Docker Qdrant, and the application database.
It proves the full persistence and operational contract without a hosted-provider key.

An optional OpenAI smoke is a separate command and a separately named evidence file. When no key is
provided, it records `NOT_RUN` and the reason. It must not reuse a keyless success result or hide a
provider failure behind deterministic output.

## Visible Failure

Release probes should include at least these failures:

- selected OpenAI generation with no credential fails startup;
- unreachable vector storage reports a vector dependency failure;
- retention cannot remove source-of-truth records;
- runtime commit mismatch fails deployment proof;
- a disabled optional provider remains clearly labelled disabled rather than tested.

No fallback should make these cases look successful.

## Incorrect Architecture

An incorrect release process runs unit tests on one commit, builds a container from another, checks
only `/actuator/health`, and calls the deployment ready. Another cleanup job deletes all vectors and
source rows together because they are treated as one cache. A third process marks OpenAI passed when
no key was supplied.

The correct process ties evidence to the exact artifact, separates component readiness, respects
state ownership, and names every unexecuted optional gate honestly.

## Lab Bridge

In PROD-08, you will build a source-labelled non-root image, expose independent operations
readiness, run Docker Qdrant and durable application storage, create a non-LLM persistence probe,
restart the stack, verify retained state, run bounded cleanup, and prove selected OpenAI generation
fails without its required credential. The resulting JSON summary records the checkpoint and exact
commit.

## Closing

Release readiness is evidence, not optimism. AI Fabric applications become operable when artifact
identity, provider posture, durable workflow state, cleanup ownership, and failure behavior are all
visible and testable before users depend on them.
