# Build Production-Oriented AI Workflows with Java and Spring Boot

> Master curriculum and implementation specification for the public AI Fabric course.

## Document Status

| Field | Value |
| --- | --- |
| Status | Ready for course asset implementation after the release blockers in this document are fixed |
| Course baseline | AI Fabric `0.3.3` |
| Framework release tag | `ai-fabric-framework-v0.3.3` |
| Course content version | `0.3.3-course.1` |
| Planned course source tag | `ai-fabric-course-v0.3.3.1` |
| Java | `21` |
| Spring Boot | `4.1.x` |
| Maven group | `io.github.loom-ai-labs` |
| Framework packages | `ai.fabric.*` |
| Public course title | Build Production-Oriented AI Workflows with Java and Spring Boot |
| Public subtitle | Semantic search, evidence-grounded RAG, governed actions, chat memory, and tenant security with AI Fabric |

This file is the curriculum source of truth. It defines what must be built in the framework
repository, the learner repository, the `aifabric` website, and the NotebookLM production workflow.
It is not itself a learner lesson.

Do not publicly label the course "AI Fabric External User Course." That phrase is an internal
description. Use the public title above.

## Course Promise

The course teaches a Java developer to add useful AI workflows to an existing Spring Boot
application while keeping domain rules, authorization, state changes, and failure handling under
application control.

By the end of the core course, a learner can:

- Install AI Fabric from Maven Central in a standalone Spring Boot app.
- Model and index application data for semantic retrieval.
- Produce answers grounded in retrieved evidence.
- Register read and write actions with typed parameters.
- Require confirmation before side effects.
- Continue a conversation using backend-owned chat session state.
- Apply tenant, user, and role policy before exposing evidence or actions.
- Test success, failure, and no-evidence behavior without hiding provider failures.
- Complete a lesson manually or direct a coding assistant through the same bounded implementation,
  failure exercise, tests, and completion checks.

The production and elective tracks add:

- Local ONNX and OpenAI provider profiles.
- Indexing, migration, update, and delete lifecycle coverage.
- Provider and vector diagnostics.
- PII-safe application patterns.
- RAG quality evaluation.
- Real application architecture studies.
- Safe use of coding assistants with AI Fabric.

## Course Philosophy

The course must consistently demonstrate these principles:

1. AI Fabric supplies orchestration, retrieval, governed action, memory, policy, and diagnostics.
2. The application owns domain truth, authorization, persistence, and side effects.
3. The LLM supplies interpretation and generation. UI code must not fake intelligence.
4. Retrieved evidence and trusted action facts must remain visible and inspectable.
5. Write actions require explicit confirmation.
6. Security and tenant boundaries fail closed.
7. Provider failures stay visible. A deterministic fallback must never masquerade as live AI.
8. Tests preserve behavior before refactoring or release.
9. Learners use published artifacts. Course success must not depend on framework source being
   installed locally.
10. A coding assistant may perform lesson work, but the learner still owns diff review, verification,
    and the ability to explain the resulting request flow.

The framework philosophy remains authoritative:

- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`

### Field Lessons From Real Applications

The course must teach real failure modes, not only the final architecture. The lessons below came
from building, deploying, and correcting the public real applications. They belong inside the
existing course tracks as intentional failures, troubleshooting exercises, and case-study
postmortems; they are not eighteen additional standalone modules.

| Field lesson | Primary course home | Applied case study |
| --- | --- | --- |
| Missing `EntityAccessPolicy` fails the pipeline closed | CORE-06, CORE-07 | Account Resolver |
| Frontend shortcuts must not fake model reasoning | CORE-01, ASSIST-01, ASSIST-02 | All AI-facing cases |
| `position` metadata is not an orchestration `mode` | CORE-01, CORE-04 | Shopping, Account Resolver |
| RAG is real only after evidence is indexed | QS-01, CORE-02, CORE-03, PROD-02 | Shopping, Account Resolver |
| Iterative read planning needs allowlists and hard bounds | CORE-04 | Account Resolver |
| RAG configuration still requires `ai-fabric-rag` at runtime | CORE-03, CORE-07 | Shopping, Account Resolver |
| Context-owned action values must not be model parameters | CORE-04 | Account Resolver |
| Domain follow-ups should use app prompt overlays first | CORE-05, PROD-03 | Account Resolver |
| Action results need user-facing projection | CORE-04 | Shopping, Account Resolver |
| Policy decisions and explanations belong in backend results | CORE-04 | Account Resolver, Behavior Signals |
| Public mutable demos require session isolation and cleanup | PROD-05 | Shopping, Account Resolver, Behavior Signals, Tenant Guard, Privacy Shield |
| Deployment proof must inspect the served bundle and backend build | CORE-07, PROD-05 | All deployed cases |
| Chat presentation state is not AI conversation state | CORE-05 | Shopping, Account Resolver |
| Raw payment details must stay outside the LLM path | CORE-06 | Account Resolver |
| Conversation memory belongs in `ai-fabric-chat-session` | CORE-05, ASSIST-02 | Account Resolver |
| Local deterministic and live provider posture must be explicit | PROD-01, PROD-05 | Behavior Signals, Tenant Guard, Privacy Shield |
| Deterministic governance claims still require backend proof | CORE-06, CORE-07 | Tenant Guard |
| Privacy claims must be proved at the backend boundary | CORE-06, CORE-07 | Privacy Shield |

Every field-lesson block in the course uses this contract:

1. **Symptom:** what the application user, API consumer, or operator actually observes.
2. **Cause:** which application, framework, provider, deployment, or UI boundary owns the problem.
3. **Correct pattern:** the smallest fix that preserves AI Fabric's ownership and security model.
4. **Proof:** the regression test, direct HTTP request, runtime diagnostic, or deployed artifact that
   demonstrates the fix.

## Audience

Primary audience:

- Java and Spring Boot backend developers.
- Technical leads evaluating AI enablement for an existing product.
- Solution engineers building secure enterprise AI workflows.
- Developer advocates teaching AI Fabric through working applications.

Secondary audience:

- Frontend developers consuming AI Fabric-backed chat and action APIs.
- Engineers using coding assistants to add AI Fabric to an application.
- Contributors who first want to understand the external-user experience.

Prerequisites:

- Comfortable reading and editing a small Spring Boot application.
- Basic Java, Maven, REST, JSON, and JUnit knowledge.
- Java 21 and Maven 3.9 or later.
- Git and a command-line HTTP client.
- Optional OpenAI API key for explicitly marked live-provider labs.

Not required for the quickstart or core local path:

- Cloning or building the AI Fabric framework reactor.
- Docker.
- A cloud vector database.
- An LLM API key.

## Definition Of Learner-Ready

A lesson is not ready merely because its topic outline exists. Every public lesson must provide:

- An estimated duration.
- Prerequisite lessons and software.
- A pinned framework version.
- A starter checkpoint and a solution checkpoint.
- Exact files to inspect and edit.
- Copyable build, run, and request commands.
- Expected status codes and important response fields.
- One intentional failure or incomplete-state exercise.
- A clear "done when" checklist.
- Reset and cleanup instructions.
- Troubleshooting for likely errors.
- Focused tests that run from a clean checkout.
- A lesson-specific assistant implementation prompt and independent review prompt.
- Evidence that the assistant prompt was exercised from the declared starter checkpoint and reached
  the same tests and "done when" conditions as the manual path.
- A reviewed, machine-readable knowledge check with the question count and competencies required by
  the lesson's track.
- Deterministic answer keys and explanations for scored questions, plus explicit review criteria for
  questions that ask the learner to explain or defend an implementation.
- For lessons that declare theory media, one self-contained NotebookLM production script covering
  architecture, request/data flow, ownership boundaries, important failure behavior, visual
  direction, and accuracy guardrails.
- For those lessons, a reviewed source-provenance manifest and pre-lesson architecture explainer.

No lesson may be marked ready until a clean-environment CI job proves its checkpoint.

## Publishing Architecture

The course is one product published through four connected surfaces.

| Surface | Responsibility | Planned location |
| --- | --- | --- |
| Framework repository | Canonical curriculum, lesson Markdown, knowledge checks, assistant prompts, manifests, API evidence, release compatibility | `AI-Fabric-Framework/docs/course` |
| Learner repository | Standalone Spring Boot app, Maven wrapper, fixtures, checkpoints, tests | `Loom-AI-Labs/ai-fabric-course-support-assistant` |
| Website repository | Human-readable course hub, manual/assistant lesson paths, progress, video embeds, downloads | `aifabric` under `/course` |
| NotebookLM | Script-grounded conceptual explainers | One uploaded production script per video; no supporting upload sources; none is required for the action-first Quickstart |

The learner repository must consume AI Fabric only through Maven Central. It must not use relative
paths into the framework source tree or depend on unpublished `examples/real-apps` artifacts.

### Canonical Content Rule

Course Markdown is authored in this framework repository. The website receives a generated,
version-pinned copy. Website developers must not edit generated course lesson text directly.

Planned canonical authoring structure:

```text
docs/course/
  README.md
  AI_FABRIC_EXTERNAL_USER_COURSE.md
  course.yml
  shared/
    glossary.md
    troubleshooting-index.md
    architecture/
  quickstart/
    01-first-useful-result/
      lesson.md
      lab.md
      expected-results.md
      troubleshooting.md
      quiz.md
      notebooklm/
  core/
    01-mental-model/
    02-model-and-index/
    03-evidence-grounded-rag/
    04-governed-actions/
    05-chat-memory/
    06-security-and-privacy/
    07-test-and-ship/
  production/
    01-provider-profiles/
    02-indexing-lifecycle/
    03-rag-quality/
    04-managed-vector-provider/
    05-release-readiness/
  case-studies/
  coding-assistants/
  capstone/
```

The synchronization process must:

1. Read the course manifest from the immutable course source tag.
2. Copy only declared public course assets.
3. Record the course source tag, framework compatibility tag, and source commit in a generated manifest.
4. Fail when generated website content differs from canonical source.
5. Preserve website-only presentation components outside the generated content directory.

Suggested website generated directory:

```text
src/content/course/0.3.3/
  course.yml
  quickstart/
  core/
  production/
  case-studies/
  coding-assistants/
  capstone/
  source-manifest.json
```

### Versioning Rule

Every published course release is pinned to a framework release. Course content receives its own
immutable source tag because documentation may be completed after the framework release tag was
created. Do not make old lessons silently follow `main` or a moving `latest` version.

For this course release:

- Framework API compatibility: `ai-fabric-framework-v0.3.3`.
- Course content source: `ai-fabric-course-v0.3.3.1` after Phase 0 is complete.
- Learner checkpoint tags: `course-0.3.3-*` in the standalone learner repository.

Checkpoint naming convention:

```text
course-0.3.3-00-starter
course-0.3.3-01-first-search
course-0.3.3-02-rag
course-0.3.3-03-actions
course-0.3.3-04-memory
course-0.3.3-05-security
course-0.3.3-06-tested-solution
```

Checkpoint tags are immutable. A course correction creates a patch checkpoint or a new course
release rather than moving an existing tag.

## Course Product Structure

The complete course is a set of selectable tracks, not an eight-hour promise for every topic.

| Track | Duration | Outcome |
| --- | --- | --- |
| Quickstart | 60-90 minutes | Run a standalone app and prove a first semantic search |
| Core course | 7-9 hours | Build search, RAG, actions, memory, security, and tests in one app |
| Production track | 6-8 hours | Add providers, lifecycle, quality, diagnostics, and release checks |
| Real-app case studies | 2-4 hours | Study five deployed application shapes |
| Coding-assistant track | 1-2 hours | Use an assistant without inventing APIs or bypassing policy |
| Capstone | 4-8 hours | Build and defend a complete AI Fabric vertical slice |

Expected total for every track and capstone: approximately 21-33 hours.

The website must let learners distinguish:

- Required core lessons.
- Optional live-provider work.
- Production lessons.
- Case studies.
- Capstone work.

### Two Completion Paths Per Lesson

Every published lesson supports two paths to the same solution checkpoint:

| Path | Learner experience | Completion evidence |
| --- | --- | --- |
| Manual lab | The learner follows the files, build steps, requests, intentional failure, and tests directly. | Commands, expected output, tests, and the lesson's "done when" checklist |
| Assistant-assisted build | The learner gives a reviewed lesson-specific prompt to a coding assistant, then reviews its changes and verification report. | The same commands, failure reproduction, tests, and "done when" checklist plus a reviewed diff |

The assistant-assisted path is not a shortcut to the solution branch. It must begin from the same
starter checkpoint and implement only the current lesson. The assistant must not commit, push,
deploy, expose secrets, discard unrelated worktree changes, or claim success for checks it did not
run. A learner completes the lesson only after reviewing the diff and explaining the application,
AI Fabric, provider, data, and UI responsibilities affected by the change.

## Continuing Application

All quickstart and core lessons evolve one application:

> **Support Knowledge Assistant**

The application starts as a normal support knowledge service and grows into an evidence-grounded,
policy-aware assistant. This domain naturally supports retrieval, governed actions, memory,
privacy, and tenant safety without changing business context between lessons.

### Business Scenario

A software company stores support articles and customer tickets. Customers ask questions, retrieve
approved knowledge, inspect their own tickets, create or escalate a ticket, and continue follow-up
conversations. Support agents can access additional tenant-approved evidence, but no user can see
another tenant's private records.

### Domain Model

| Type | Purpose | AI Fabric role |
| --- | --- | --- |
| `KnowledgeArticle` | Approved support content | Searchable and embeddable evidence |
| `SupportPolicy` | Return, escalation, privacy, and service rules | RAG evidence |
| `SupportTicket` | Application-owned ticket state | Read and governed write actions |
| `CustomerAccount` | Current user, tenant, plan, and entitlements | Trusted action input and access context |
| `ChatSession` | Conversation and pending confirmation state | `ai-fabric-chat-session` persistence |

### AI Surfaces

| Surface | Contract |
| --- | --- |
| Semantic search | Search articles by meaning and return IDs, scores, and metadata |
| RAG query | Answer only from retrieved article or policy evidence when the lesson requires grounding |
| Read action | Read current user's ticket status without asking for an ID already known by the app |
| Write action | Create or escalate a ticket after confirmation |
| Chat memory | Resolve follow-ups from backend conversation state |
| Access policy | Scope evidence and actions to the current user and tenant |
| PII policy | Prevent raw sensitive data from being logged or indexed in privacy exercises |

### Provider Profiles

| Profile | Purpose | Expected dependency |
| --- | --- | --- |
| `local` | No-key semantic retrieval using real local embeddings | `ai-fabric-onnx-starter` plus Lucene |
| `test` | Fast deterministic contract tests with app-owned test providers | Test-scoped fixtures plus memory or Lucene |
| `openai` | Optional live generation and embeddings | `ai-fabric-provider-spring-ai` plus Lucene |

The local course path uses ONNX for real semantic behavior. Deterministic hash or fixture embeddings
may be used in unit tests, but lessons must label them as test wiring and must not present them as
semantic intelligence.

### Planned Learner Repository Shape

```text
ai-fabric-course-support-assistant/
  .mvn/
  mvnw
  mvnw.cmd
  pom.xml
  README.md
  scripts/
    download-onnx-model.sh
    reset-course.sh
  requests/
    quickstart.http
    rag.http
    actions.http
    memory.http
    security.http
  src/main/java/dev/aifabric/course/support/
    SupportAssistantApplication.java
    account/
    actions/
    chat/
    knowledge/
    policy/
    security/
    web/
  src/main/resources/
    application.yml
    application-local.yml
    application-openai.yml
    ai-entity-config.yml
  src/test/java/dev/aifabric/course/support/
```

### Stable Course API

The course app should keep a small API stable across checkpoints:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/demo/reset` | Clear learner-owned fixtures, vectors, and sessions |
| `POST /api/demo/seed` | Seed the lesson's domain data |
| `POST /api/demo/index` | Index current course evidence |
| `GET /api/demo/readiness` | Report data, vector, provider, and build readiness |
| `GET /api/knowledge/search?q=...` | Prove semantic retrieval directly |
| `POST /api/assistant/query` | Run RAG, action, and memory scenarios |
| `GET /actuator/health` | Prove application health |

Confirmation should use the same conversation endpoint and backend chat-session state. The learner
must be able to send a short follow-up such as `yes` with the same conversation ID. Do not teach a
frontend-owned confirmation or history protocol.

## Lesson Contract

Each lesson source directory uses this shape. The `notebooklm/` directory is present only when the
lesson declares a theory video:

```text
<track>/<lesson>/
  lesson.md
  knowledge-check.yml
  assistant-prompt.md
  assistant-review-prompt.md
  notebooklm/
    <DESCRIPTIVE_NAME>_NOTEBOOKLM_SCRIPT.md
    source-manifest.yml
```

Each lesson directory must include this front matter in `lesson.md`:

```yaml
---
id: core-04
slug: governed-actions
title: Governed Actions and Confirmation
track: core
order: 4
durationMinutes: 90
courseVersion: 0.3.3-course.1
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-02-rag
solutionRef: course-0.3.3-03-actions
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/getting-started/05-first-governed-action.md
  - docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md
  - examples/real-apps/it-support-action-bot/README.md
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validatedStarterRef: course-0.3.3-02-rag
  validationStatus: planned
  reviewedBy: null
  reviewedAt: null
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
video:
  status: planned
  generator: notebooklm
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 6
  theoryBrief: "#notebooklm-pre-lesson-theory"
  sourceManifest: notebooklm/source-manifest.yml
  publicUrl: null
  transcript: null
  reviewedBy: null
  reviewedAt: null
---
```

The `video` block is optional. If it is present, `course.yml` must declare the matching
`notebookSourceManifest`; if it is absent, the lesson has no theory-video completion gate. A
published lesson may omit theory media by design, as QS-01 does.

The manifest is review provenance. NotebookLM receives only the production script referenced by the
manifest's `outputs.transcript` field. Source-code and documentation paths listed under `sources` are
used by maintainers to audit the script and must not be uploaded as additional NotebookLM sources.

Every `lesson.md` follows this order:

1. Outcome.
2. Why this matters.
3. Optional NotebookLM pre-lesson theory brief when the lesson declares theory media.
4. Starting state.
5. Architecture and request flow.
6. Files to inspect.
7. Manual build steps.
8. Assistant-assisted build with copyable implementation and review prompts.
9. Run and request commands.
10. Expected result.
11. Intentional failure exercise.
12. Field lesson from a real application: symptom, cause, correct pattern, and proof.
13. Tests.
14. Done when.
15. Knowledge check.
16. Reset and cleanup.
17. Troubleshooting.
18. What changed from the previous checkpoint.
19. Next lesson.

The field lesson should be short and executable. It must link to
`AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md` for the full incident history rather than copying the
entire reference into every lesson.

### Knowledge Check Contract

Questions verify that the learner understands the implemented behavior; they are not trivia and do
not replace executable tests. The manual and assistant-assisted paths use the same knowledge check.
Copying a solution or accepting an assistant's patch therefore cannot complete a lesson without the
learner explaining the ownership boundary, request flow, failure mode, and verification evidence.

Required question counts:

| Track | Questions per lesson | Emphasis |
| --- | ---: | --- |
| Quickstart | 2-3 | Request flow, first failure diagnosis, and observable proof |
| Core | 4-5 | Ownership, request/data flow, failure diagnosis, verification, and domain transfer |
| Production | 3-5 | Operational ownership, failure visibility, security, and release evidence |
| Case studies | 3 | Capability ownership, deployed failure diagnosis, and reproduction evidence |
| Coding assistants | 4-5 | Scope, API evidence, diff review, verification, and implementation defense |

A question may cover more than one competency, but every lesson must cover:

1. **Ownership or request flow:** identify what belongs to the application, AI Fabric, provider,
   vector/data layer, UI, or deployment platform.
2. **Failure diagnosis:** distinguish an observed symptom from the owning cause and correct fix
   boundary.
3. **Verification:** identify the test, request, response field, or runtime evidence that proves the
   lesson outcome.

Core, production, and coding-assistant lessons also cover transfer: how the same contract applies to
another domain without hard-coding the course scenario. Every assistant-assisted implementation
lesson must include an `implementation-defense` question asking the learner to identify the changed
code, the behavior-preserving test, and the ownership boundary. A model-generated implementation
report is not an acceptable answer by itself.

Each lesson stores its reviewed questions in `knowledge-check.yml`:

```yaml
schemaVersion: 1
lessonId: core-04
passingScorePercent: 80
questions:
  - id: core-04-q1
    type: single-choice
    competency: ownership
    prompt: Which layer decides whether the support action requires confirmation?
    options:
      - id: application-policy
        text: The application action policy registered through AI Fabric
      - id: browser
        text: The browser after rendering the confirmation card
      - id: llm-provider
        text: The LLM provider based on its generated wording
    correctOptionIds:
      - application-policy
    explanation: The application declares the action policy; AI Fabric enforces the confirmation flow.
    sourcePaths:
      - docs/getting-started/05-first-governed-action.md
  - id: core-04-q2
    type: implementation-defense
    competency: verification
    prompt: Identify the registered handler, the confirmation-path test, and the evidence that proves the write did not execute before confirmation.
    reviewCriteria:
      - Names the application-owned handler file and method.
      - Names the focused test and its pre-confirmation assertion.
      - Distinguishes the AI Fabric confirmation state from domain write execution.
    sourcePaths:
      - examples/real-apps/it-support-action-bot/README.md
```

Supported question types:

| Type | Website behavior | Completion behavior |
| --- | --- | --- |
| `single-choice` | Select one option and show the reviewed explanation after submission | Deterministically scored |
| `multiple-choice` | Select all applicable options and show the reviewed explanation | Deterministically scored |
| `answer-reveal` | Learner writes or considers an answer, then reveals a reviewed model answer | Not auto-scored; learner records review |
| `implementation-defense` | Learner points to code, tests, and observable evidence, then compares with review criteria | Not auto-scored; required for assistant-assisted implementation lessons |

`passingScorePercent` applies only to scored choice questions. Lesson completion requires the
threshold plus explicit review of every `answer-reveal` and `implementation-defense` question. The
public website must not call an LLM to assign a semantic score or present self-review as instructor
certification. NotebookLM may draft candidate questions, but a maintainer must review and publish the
canonical YAML, answer keys, explanations, source paths, and review criteria.

### Assistant-Assisted Lesson Contract

The manual lesson remains the canonical explanation. `assistant-prompt.md` is an executable handoff
for a coding assistant; `assistant-review-prompt.md` is an independent verification handoff. Neither
file may introduce requirements, commands, APIs, or completion conditions that are absent from
`lesson.md`.

Prompt modes:

| Mode | Use | Expected assistant behavior |
| --- | --- | --- |
| `implement` | Lessons that change application code or configuration | Inspect, plan, edit, test, reproduce the field failure, and report |
| `analyze` | Mental-model or architecture lessons | Inspect supplied sources, produce the required design artifact, and make no code changes |
| `reproduce` | Real-app case studies | Exercise the documented live/local scenario and explain evidence without changing the deployed app |
| `verify` | Testing, release, and review lessons | Run the declared gates, diagnose failures, and avoid unrelated implementation work |

Every `assistant-prompt.md` must contain final lesson-specific values, not unresolved placeholders. It
must include:

1. **Role and bounded goal:** implement or analyze only the current lesson.
2. **Pinned context:** framework version/tag, Java/Spring Boot baseline, starter ref, and source paths.
3. **Starting-state checks:** current branch/ref, worktree status, prerequisites, provider/key posture,
   and expected prior checkpoint.
4. **Ownership map:** application, AI Fabric, provider, vector/data, UI, and deployment responsibilities
   relevant to the lesson.
5. **Allowed scope:** exact files or directories the assistant may edit and work it must leave alone.
6. **Implementation sequence:** the same ordered work as the manual build path.
7. **Intentional failure:** exact reproduction and the evidence that distinguishes symptom from cause.
8. **Validation:** exact tests, run commands, requests, and expected important fields.
9. **Completion contract:** every lesson-specific "done when" condition.
10. **Stop conditions:** missing prerequisites, incompatible source, unavailable required credentials,
    conflicting user changes, or a mismatch between documentation and current APIs.
11. **Final report:** changed files, commands and results, failure proof, unverified checks, and a short
    request/data-flow explanation.

Every implementation prompt must explicitly prohibit:

- checking out or copying the solution checkpoint as the implementation strategy;
- inventing AI Fabric classes, annotations, properties, endpoints, or provider behavior;
- implementing model reasoning with frontend/backend keyword shortcuts;
- bypassing access control, confirmation, tenant boundaries, privacy, or provider errors;
- sending raw secrets, PII, payment details, or browser-built chat history through prompts;
- deleting or reverting unrelated worktree changes;
- using `-DskipTests` for the lesson verification command;
- committing, pushing, deploying, or using live credentials without explicit user instruction;
- claiming a test, provider call, browser check, or deployment proof was run when it was not.

Every `assistant-review-prompt.md` must instruct a fresh review pass to:

1. inspect the diff and current repository before trusting the implementation report;
2. compare changes with the lesson's allowed scope, ownership map, and current AI Fabric APIs;
3. run the deterministic test and intentional-failure gates independently;
4. identify security, privacy, confirmation, memory, provider, and evidence regressions;
5. list findings first, ordered by severity and grounded in file/line references;
6. report unverified live-provider or deployed checks without treating them as passed;
7. avoid editing until the learner explicitly asks the reviewer to fix confirmed findings.

### Implementation Prompt Template

Course authors use the following template, replacing every bracketed value before publication:

```text
You are implementing AI Fabric lesson [LESSON_ID]: [LESSON_TITLE].

Work from starter checkpoint [STARTER_REF].
Use AI Fabric [FRAMEWORK_VERSION] / [FRAMEWORK_TAG], Java [JAVA_VERSION], and Spring Boot
[SPRING_BOOT_VERSION]. Do not checkout or copy [SOLUTION_REF].

Read first:
- [CANONICAL_SOURCE_PATH]
- [GETTING_STARTED_PATH]
- [RELEVANT_FIELD_LESSON_PATH_AND_HEADING]
- [NEAREST_REAL_APP_README]

Goal:
[ONE LESSON-SPECIFIC OUTCOME]

Before editing:
1. Verify the repository, starter ref, prerequisites, and worktree state.
2. Inspect the existing code and the supplied sources; do not invent AI Fabric APIs.
3. Explain the application, AI Fabric, provider, data/vector, UI, and deployment ownership relevant
   to this lesson.
4. Give a concise implementation and test plan.

Allowed scope:
- [EXACT FILES_OR_DIRECTORIES]

Required work:
1. [LESSON-SPECIFIC_STEP]
2. [LESSON-SPECIFIC_STEP]
3. [LESSON-SPECIFIC_STEP]

Intentional failure and field lesson:
- Reproduce: [EXACT_FAILURE]
- Record: [EXPECTED_SYMPTOM_AND_DIAGNOSTICS]
- Correct at: [OWNING_BOUNDARY]
- Prove with: [REGRESSION_TEST_OR_REQUEST]

Validation:
- [EXACT_TEST_COMMAND_WITHOUT_SKIP_TESTS]
- [EXACT_RUN_COMMAND]
- [EXACT_HTTP_REQUEST]
- Expected evidence: [STATUS_AND_IMPORTANT_FIELDS]

Done when:
- [LESSON_DONE_CONDITION]
- [LESSON_DONE_CONDITION]

Do not commit, push, deploy, expose secrets, discard unrelated changes, hide provider failures, or
claim an unexecuted check passed. Stop and report when a prerequisite is missing, the starter state
is incompatible, current APIs contradict the lesson, or user changes make the scoped edit unsafe.

Finish with:
- changed files and why;
- commands run and exact outcomes;
- intentional-failure and regression evidence;
- unexecuted or blocked checks;
- a concise explanation of the final request and data flow.
```

The review prompt uses the same pinned context and validation commands, but begins from an adversarial
assumption that the implementation may be incomplete or unsafe. Its output is a findings-first review,
not a second implementation narrative.

### Required Prompt Inventory

| Course item | Mode | Lesson-specific assistant outcome |
| --- | --- | --- |
| QS-01 | `implement` | Resolve Maven Central artifacts, seed/index articles, run semantic search, and prove the no-index failure |
| CORE-01 | `analyze` | Produce the module/ownership map and correct the unsafe direct-repository design without editing code |
| CORE-02 | `implement` | Model metadata, implement index lifecycle, and prove create/update/delete retrieval behavior |
| CORE-03 | `implement` | Add RAG dependency/configuration, evidence projection, and no-evidence regression behavior |
| CORE-04 | `implement` | Register read/write actions, collect typed parameters, gate writes, and project trusted results |
| CORE-05 | `implement` | Enable backend chat sessions and prove multi-turn context without browser-supplied history |
| CORE-06 | `implement` | Add tenant/user access policy and PII-safe processing with denied-path tests |
| CORE-07 | `verify` | Run the complete deterministic, packaged-app, metadata, and explicit-provider-failure release gate |
| PROD-01 | `implement` | Configure local ONNX and optional OpenAI profiles with truthful diagnostics and secret-safe failure proof |
| PROD-02 | `implement` | Implement repeatable backfill and vector create/update/delete/readiness lifecycle checks |
| PROD-03 | `implement` | Add golden evidence questions, prompt-overlay regression tests, and a failing no-source quality gate |
| PROD-04 | `implement` | Move the same retrieval contract to Qdrant and prove dimensions, tenant filters, and visible failure |
| PROD-05 | `verify` | Package with Docker and verify build metadata, session cleanup, served artifacts, and release evidence |
| CASE-01 | `reproduce` | Exercise staged Shopping evidence, attachments, governed cart/checkout actions, and result projection |
| CASE-02 | `reproduce` | Exercise resolver reads, policy grounding, follow-ups, parameter collection, confirmation, and policy outcomes |
| CASE-03 | `reproduce` | Compare provider posture, add behavior events, regenerate insight, and inspect allowlisted agentic UI |
| CASE-04 | `reproduce` | Prove allowed and denied tenant retrieval plus confirmed/rejected writes and deletion evidence |
| CASE-05 | `reproduce` | Prove backend PII processing, sanitized indexing/search, provider posture, and session isolation |
| ASSIST-01 | `analyze` | Assemble the minimum correct context pack and critique an under-specified assistant request |
| ASSIST-02 | `implement` | Use the standard prompt contract to add one bounded capability and independently review the result |

The capstone publishes an optional kickoff prompt and mandatory review prompt, but no prompt may
encode a complete domain-specific solution. The learner must choose the domain, action policy,
evidence model, and security boundary.

## Quickstart

### QS-01: First Useful Result

**Duration:** 60-90 minutes

**Checkpoint:** `course-0.3.3-00-starter` to `course-0.3.3-01-first-search`

**Keys required:** No

**Introduction:**

AI Fabric adds application-level AI capabilities to Spring Boot, including retrieval, governed
actions, memory, privacy, and provider orchestration. This quickstart demonstrates one small
capability before the Core track explains the complete architecture.

The Quickstart has no required NotebookLM video. Its purpose is to give the developer a useful,
inspectable result before a long conceptual explanation. CORE-01 introduces the framework in full,
and CORE-02 explains semantic evidence and indexing lifecycle in depth.

Outcome:

- Start a standalone Spring Boot app that resolves AI Fabric from Maven Central.
- Seed and index five support articles.
- Search using wording different from the stored article title.
- Inspect entity ID, score, content, and metadata in the response.

The learner does not clone or build the framework reactor.

Files to edit:

- `pom.xml`
- `src/main/resources/application-local.yml`
- `src/main/resources/ai-entity-config.yml`
- `knowledge/KnowledgeArticle.java`
- `knowledge/KnowledgeArticleService.java`
- `web/KnowledgeSearchController.java`

Required sequence:

1. Verify Java 21.
2. Check out the starter tag.
3. Download the local ONNX assets with the supplied script.
4. Run tests.
5. Start the app with the `local` profile.
6. Call reset, seed, and index.
7. Run a semantic query.

Intentional failure:

- Search before indexing.
- Observe a successful request with no evidence rather than a fabricated answer.
- Index the records and repeat the same query.

Field lesson - indexed evidence is a runtime fact:

- **Symptom:** the application is configured for semantic retrieval but returns no support article.
- **Cause:** domain rows exist, but no embedding/vector record has been created for them.
- **Correct pattern:** keep reset, seed, and index as explicit operations and never describe seeded
  database data as retrievable evidence.
- **Proof:** readiness reports five indexed articles and the same paraphrased query returns the
  expected article ID only after indexing.

Done when:

- Dependencies resolve from a clean Maven repository.
- The app starts without cloud keys.
- Search returns the expected article ID for the golden quickstart query.
- The response exposes evidence metadata.
- The learner can explain the difference between domain storage and vector storage.

Quickstart source material:

- `docs/getting-started/01-choose-your-path.md`
- `docs/getting-started/02-installation.md`
- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/getting-started/09-vector-storage-lucene.md`

## Core Course

QS-01 and CORE-02 share the first-search solution checkpoint. The quickstart is the compressed build
path; CORE-02 supplies the deeper data-modeling, metadata, update, and delete explanation. A learner
who completed QS-01 inspects and extends the same code instead of rebuilding it from scratch.

### CORE-01: What Is AI Fabric? Architecture And Mental Model

**Duration:** 60-75 minutes

**Code checkpoint:** no code change

**NotebookLM pre-lesson theory:**

- What AI Fabric is: a modular application-level coordination framework for AI workflows in Java and
  Spring Boot applications.
- What AI Fabric is not: an LLM, vector database, chatbot UI, persistence replacement, authorization
  system, or substitute for application business rules.
- Why the framework exists: to replace scattered provider calls, prompt strings, browser-owned
  intelligence, direct model-driven writes, and duplicated session/security logic with explicit,
  testable boundaries.
- When AI Fabric is a strong fit and when one direct Spring AI or native-provider call is the simpler
  design.
- The ownership split between application domain services and policy, AI Fabric orchestration and
  contracts, and concrete LLM, embedding, and vector providers.
- AI Fabric's layered architecture: application UI/API, application domain, capability modules,
  provider/vector contracts, and concrete models and stores.
- The smallest-capability module strategy: foundation, retrieval/indexing, RAG/provider, governed
  actions/session, and optional privacy, governance, behavior, and managed-vector modules.
- The two main request paths: evidence retrieval and confirmation-gated action execution, including
  where they converge in orchestration and where side effects remain application-owned.
- The ordered request lifecycle from trusted Spring Boot entry through security, access, mode policy,
  backend memory, intent extraction, target/vector-space resolution, information or action handling,
  provider invocation, response sanitization, and persistence.
- The configuration and extension model: conditional auto-configuration, scoped YAML and annotation
  precedence, purpose-aware provider selection, curated prompt overlays, and application-owned
  extension points.
- How backend chat memory, PII policy, and tenant-scoped retrieval protect multi-turn and sensitive
  workflows.
- Why modes are typed orchestration policy while positions are request context, and why neither the
  browser nor the LLM should directly own side effects.

Outcome:

- Distinguish application responsibilities, AI Fabric responsibilities, and provider
  responsibilities.
- Select the smallest module set for a feature.
- Trace a request from REST input through retrieval or action execution.

Lab:

- Map three requirements to AI Fabric modules.
- Identify one requirement that should remain ordinary application code.
- Draw the Support Knowledge Assistant request flow.

Intentional failure:

- Review a proposed design that lets an LLM write directly to a repository.
- Replace it with a registered, confirmation-gated application action.

Field lessons from deployed applications:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| A scenario chip appears to understand a request, but browser keyword matching already selected the action. | The UI was made responsible for intelligence that should come from orchestration. | Send natural language to the AI Fabric endpoint; label any manual operator controls as manual. | Inspect the browser request and prove the natural-language endpoint, intent extraction, and confirmation path were used. |
| The UI sends `position=resolver`, but default orchestration behavior still runs. | `position` is contextual metadata, not a configured mode. | Define and request a named mode through typed `OrchestrationProperties`. | Bind the real YAML in a test and assert the requested/default mode and its nested policy values. |

Done when:

- The module-selection table names required and deliberately excluded modules.
- The learner can explain why AI Fabric is not another persistence layer.

Sources:

- `docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_INTRO_NOTEBOOKLM_SCRIPT.md`
- `docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_ARCHITECTURE_MODULE_MAP_NOTEBOOKLM_SCRIPT.md`
- `docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_REQUEST_LIFECYCLE_NOTEBOOKLM_SCRIPT.md`
- `docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_CONFIGURATION_EXTENSION_MODEL_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/00-llm-start-here.md`
- `docs/getting-started/01-choose-your-path.md`
- `docs/llm-context/AI_FABRIC_MODULE_DECISION_TREE.md`
- `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`

### CORE-02: Model And Index Application Data

**Duration:** 60 minutes

**Checkpoint:** `course-0.3.3-00-starter` to `course-0.3.3-01-first-search`

**NotebookLM pre-lesson theory:**

- Why semantic similarity is different from keyword matching and from asking an LLM to answer from
  general knowledge.
- The evidence path from application entity through approved field projection, embedding provider,
  vector index, similarity query, and evidence result.
- The ownership split between application domain records, AI Fabric indexing and retrieval, the
  embedding provider, and vector storage.
- Why existing database rows are not retrievable evidence until indexing succeeds, and why an empty
  result is more trustworthy than a fabricated answer.
- The meaning of searchable, embeddable, contextual, and metadata fields and how each affects
  retrieval without making every domain field model-visible.
- The indexing lifecycle from stable entity identity through field projection, embedding, vector
  storage, metadata filtering, update, and deletion.
- Why vector spaces, entity IDs, tenant/category metadata, embedding dimensions, and index locations
  form a compatibility contract.
- Why annotation or YAML configuration describes intent but lifecycle tests prove that the
  application actually kept the vector index synchronized.

Outcome:

- Define searchable, embeddable, context, and metadata fields.
- Index a `KnowledgeArticle` into a dedicated vector space.
- Preserve stable entity and tenant metadata.

Lab changes:

- Add `KnowledgeArticle` and repository fixtures.
- Add annotation or YAML-driven AI entity configuration.
- Add explicit seed and indexing services.
- Add a golden retrieval test.

Intentional failure:

- Index an article without the expected tenant/category metadata.
- Observe the failing test, correct the metadata, and reindex.

Field lesson - configured entities still need lifecycle proof:

- **Symptom:** an entity is annotated or declared indexable, but updates, deletes, or metadata filters
  do not appear in retrieval.
- **Cause:** entity configuration describes the contract; it does not prove that every application
  lifecycle path invoked indexing correctly.
- **Correct pattern:** make seed, index, update, delete, and backfill flows explicit and preserve stable
  entity, tenant, and category metadata.
- **Proof:** one lifecycle test creates, updates, queries, deletes, and then proves the deleted entity
  is no longer retrievable.

Done when:

- Create, update, and delete are reflected in retrieval.
- Metadata required by later security lessons is present.
- A test proves the expected article is retrieved by a semantically related query.

Sources:

- `docs/course/core/02-model-and-index-data/notebooklm/AI_FABRIC_SEARCHABLE_EVIDENCE_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/09-vector-storage-lucene.md`
- `examples/real-apps/smart-faq-assistant/README.md`

### CORE-03: Evidence-Grounded RAG

**Duration:** 75 minutes

**Checkpoint:** `course-0.3.3-01-first-search` to `course-0.3.3-02-rag`

**NotebookLM pre-lesson theory:**

- The RAG pipeline: resolve mode -> retrieve approved evidence -> construct grounded context -> call
  generation provider -> return answer, evidence, and diagnostics.
- The difference between retrieved evidence, model-generated wording, application policy, and a
  citation/evidence ID exposed to the caller.
- How the RAG module, provider bean, vector-space allowlist, indexed data, and orchestration mode must
  all be ready for grounded generation.
- Why no-evidence behavior must remain explicit instead of silently falling back to unsupported
  model knowledge.

Outcome:

- Retrieve approved evidence before answer generation.
- Expose evidence IDs and snippets with the answer.
- Report no-evidence state without replacing it with generic model knowledge.

Lab changes:

- Add `SupportPolicy` evidence.
- Add `ai-fabric-rag`.
- Add the assistant query endpoint.
- Add response projection for answer, evidence, mode, and diagnostics.

Intentional failure:

- Clear the vector index and ask a policy question.
- Verify the app reports that no indexed evidence is available.
- Reseed, reindex, and repeat the query.

Field lesson - retrieval configuration is not retrieval readiness:

- **Symptom:** the mode enables retrieval, but diagnostics report `no RAGProvider bean present`, or
  the answer contains no policy evidence.
- **Cause:** either `ai-fabric-rag` is absent from the runtime classpath or the allowed vector space
  has not been populated.
- **Correct pattern:** declare the RAG module explicitly, register the evidence domain, index the
  documents, and allowlist that vector space in the mode.
- **Proof:** an application-context test finds `RAGProvider`; readiness proves expected vector counts;
  the policy question returns the expected evidence ID.

Done when:

- The answer is supported by returned article or policy IDs.
- The UI/API never labels database rows as retrieved evidence before indexing.
- A test fails if an expected source is absent.

Sources:

- `docs/course/core/03-evidence-grounded-rag/notebooklm/AI_FABRIC_EVIDENCE_GROUNDED_RAG_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/04-first-rag-chat.md`
- `examples/real-apps/smart-faq-assistant/README.md`
- `examples/real-apps/chat-capabilities-demo/README.md`

### CORE-04: Governed Actions And Confirmation

**Duration:** 75-90 minutes

**Checkpoint:** `course-0.3.3-02-rag` to `course-0.3.3-03-actions`

**NotebookLM pre-lesson theory:**

- The governed-action flow: intent/action selection -> registered schema -> typed parameter
  extraction -> application authorization -> confirmation state -> handler execution -> trusted
  result projection.
- The distinction between user-supplied `@Param` values and context-owned identity, tenant, account,
  cart, and session data resolved by the application.
- Why read and write access modes differ, why confirmation is framework-managed state, and how
  rejection and duplicate confirmation protect domain side effects.
- Why the application service still owns authorization, persistence, business thresholds, and policy
  explanations even when AI Fabric orchestrates the interaction.

Outcome:

- Register one read action and one write action.
- Extract typed parameters through `@Param`.
- Require confirmation before changing ticket state.
- Return an `ActionResult` containing concise trusted facts.

Required action shape:

```java
@AIAction(
    name = "create_support_ticket",
    description = "Create a support ticket for the current customer.",
    category = "support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class CreateSupportTicketActionHandler {

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subject", description = "Short ticket subject", required = true)
        String subject,
        @Param(value = "description", description = "Issue details", required = true)
        String description,
        ActionContext context
    ) {
        // Application service owns authorization and persistence.
        return ticketService.createForCurrentCustomer(subject, description, context);
    }
}
```

Lab changes:

- Add `get_my_ticket_status` as a `READ` action.
- Add `create_support_ticket` as a `WRITE_ONLY` action.
- Add `@ActionAllowed` where application authorization is required.
- Add confirmation, rejection, and duplicate-confirmation tests.

Intentional failure:

- Submit a write request with a missing required parameter.
- Verify clarification is returned and no ticket is created.
- Reject a complete action and verify state remains unchanged.

Field lessons from deployed action flows:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| The model asks for `userId`, `subscriptionId`, tenant, or another value the application already knows. | A context-owned value was exposed as `@Param`, so it became model-visible schema. | Resolve identity, tenant, active account, cart, and session from `ActionContext` and application services. | Assert registered action metadata contains only values a user should provide. |
| Iterative planning calls too many reads or increases latency without improving the result. | `ITERATIVE` planning was enabled without a narrow catalog and hard limits. | Require an allowlist, grounding eligibility, bounded iterations, and bounded actions per iteration. | A configuration test asserts limits; an orchestration test proves no unapproved read action can execute. |
| A successful action dumps repository objects or nested policy arrays into chat. | The UI recursively rendered a rich backend payload instead of projecting user-facing facts. | Keep structured backend data, but render concise outcome, status, safe identifiers, and remaining blockers. | UI tests reject raw JSON in the primary result card while API tests preserve structured fields. |
| An approval status is visible but its business reason is not. | Policy truth was inferred or decorated in the frontend instead of returned by the domain service. | Return `policyDecision`, `policyExplanation`, and relevant thresholds in `ActionResult`. | Service and handler tests prove both approved and review-required paths include their policy reason. |

Done when:

- Registration tests prove both actions are discoverable.
- The write action cannot execute before confirmation.
- Confirm executes once.
- Reject does not mutate state.
- The response does not dump raw persistence objects.

Sources:

- `docs/course/core/04-governed-actions/notebooklm/AI_FABRIC_GOVERNED_ACTIONS_CONFIRMATION_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/05-first-governed-action.md`
- `docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
- `examples/real-apps/it-support-action-bot/README.md`

### CORE-05: Backend-Owned Conversation Memory

**Duration:** 60 minutes

**Checkpoint:** `course-0.3.3-03-actions` to `course-0.3.3-04-memory`

**NotebookLM pre-lesson theory:**

- The conversation flow from a stable conversation ID to backend-owned recent turns, resolved
  context, pending action state, and the next orchestration request.
- Why the client should send only the new message and stable identifiers instead of rebuilding a
  prompt or supplying authoritative history.
- How a short follow-up such as `escalate it` or `yes` is interpreted against backend history and a
  pending confirmation without making the UI responsible for intent.
- Session ownership, access control, expiry, and the separation between durable conversation state
  and local presentation state such as an open panel or dismissed suggestion.

Outcome:

- Create and reuse a stable conversation ID.
- Store recent turns and pending confirmation in the backend.
- Resolve a short follow-up without the client rebuilding a history prompt.

Lab sequence:

1. Ask why a ticket remains unresolved.
2. Receive a grounded answer and suggested escalation.
3. Reply `escalate it` using the same conversation ID.
4. Reply `yes` to confirm.

Intentional failure:

- Send the follow-up with a new conversation ID.
- Observe that prior context is unavailable.
- Repeat with the original ID.

Field lessons from deployed conversations:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| A short follow-up such as `ok add it` becomes `OUT_OF_SCOPE` despite relevant prior context. | Backend history exists, but the app-specific intent prompt does not teach the domain follow-up contract. | Add a narrow app prompt overlay and keep shared default prompts as fallback. | Prompt-resolution tests prove overlay precedence; a two-turn smoke returns clarification or the supported action rather than `OUT_OF_SCOPE`. |
| The browser sends an array of previous messages or the app creates its own chat-turn table. | Conversation ownership was duplicated instead of using `ai-fabric-chat-session`. | Enable the framework module, provide `ChatSessionAccessControlPolicy`, and send only the new turn plus stable identifiers. | The second request omits browser history but resolves context from the backend session; cross-user access tests fail closed. |
| Closing a chat panel loses messages, or a suggestion cannot be dismissed. | Browser presentation state was confused with framework conversation state. | Keep panel visibility and dismissed cards local while preserving backend turns and action state. | UI tests reopen the same history without changing the conversation or deleting results. |

Done when:

- The client request contains only the new message and stable identity fields.
- Backend session state supplies recent context.
- Pending action state survives the confirmation turn.
- Tests prove sessions cannot cross users.

Sources:

- `docs/course/core/05-backend-conversation-memory/notebooklm/AI_FABRIC_BACKEND_CONVERSATION_MEMORY_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/06-chat-session-memory.md`
- `examples/real-apps/ai-fabric-account-resolver/README.md`

### CORE-06: Tenant Security And Privacy

**Duration:** 75-90 minutes

**Checkpoint:** `course-0.3.3-04-memory` to `course-0.3.3-05-security`

**NotebookLM pre-lesson theory:**

- The security order of operations: verify identity -> derive tenant/user scope -> authorize
  retrieval or action -> process sensitive data -> expose only allowed evidence or results.
- Why tenant and role constraints must be applied before evidence reaches generation rather than
  filtering an already-leaked response in the UI.
- The roles of `EntityAccessPolicy`, action authorization, application identity, metadata filters,
  and fail-closed behavior when required policy is missing.
- The PII data path across intake, redaction, optional protected original storage, indexing, query
  sanitization, logging, and response projection.

Outcome:

- Apply tenant and user scope before evidence reaches generation.
- Authorize actions against application identity.
- Redact sensitive support text before storage or indexing.

Lab changes:

- Seed two tenants with overlapping article and ticket names.
- Add an `EntityAccessPolicy`.
- Add action authorization for customer and support-agent roles.
- Add a PII-safe intake exercise.

Intentional failure:

- Attempt cross-tenant search.
- Attempt escalation as an unauthenticated or unauthorized user.
- Submit a message containing an email, phone number, or test SSN.

Field lessons from deployed security and privacy flows:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| Orchestration stops at `Pipeline step failed: AccessControl`. | The application did not provide the required `EntityAccessPolicy`; the framework correctly failed closed. | Add a narrow app-owned policy based on verified identity, tenant, operation, and resource metadata. | Unit tests allow only known resources and deny missing identity, unknown resources, and cross-tenant access. |
| Tenant cards look separated in the browser, but there is no evidence that retrieval was scoped. | The UI was presenting a governance claim that the backend had not proved. | Apply tenant filters before retrieval and return backend-generated boundary diagnostics. | Direct API tests prove allowed evidence is returned and denied-tenant IDs never reach the response. |
| A payment repair flow asks the model for full card data. | Secure payment capture was incorrectly placed inside chat/action parameters. | Capture payment details with a payment-provider UI and pass only a token/reference plus safe metadata to the action. | Prompt, action trace, logs, and storage tests contain no PAN or CVV. |
| The browser masks an email or SSN and labels the workflow private. | PII handling was implemented in presentation code instead of before storage, indexing, and retrieval. | Detect and process PII at the backend boundary and expose only sanitized proof DTOs. | Tests cover redacted storage, sanitized queries, no raw payload return, and cross-session isolation. |

Done when:

- Cross-tenant evidence is absent rather than filtered in the UI.
- Unauthorized actions fail closed.
- Raw sensitive values are not returned, logged, or indexed in the privacy path.
- Security tests run without an LLM key.

Sources:

- `docs/course/core/06-tenant-security-and-privacy/notebooklm/AI_FABRIC_TENANT_SECURITY_PRIVACY_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/10-security-access-policy.md`
- `examples/real-apps/tenant-knowledge-portal/README.md`
- `examples/real-apps/privacy-first-customer-facing-support/README.md`

### CORE-07: Test And Ship The Vertical Slice

**Duration:** 60-75 minutes

**Checkpoint:** `course-0.3.3-05-security` to `course-0.3.3-06-tested-solution`

**NotebookLM pre-lesson theory:**

- The verification architecture: domain unit tests, framework registration/context tests, local
  integration tests, packaged-runtime smoke tests, and separately keyed live-provider tests.
- Which behaviors can be deterministic and which require real provider evidence, including why one
  cannot be presented as proof of the other.
- How explicit provider failures, tenant denials, no-evidence results, confirmation state, and PII
  redaction become release gates rather than screenshot claims.
- Why health, source commit, provider posture, served frontend assets, and test reports are distinct
  pieces of deployment evidence.

Outcome:

- Separate deterministic tests from live-provider tests.
- Prove retrieval, RAG, actions, memory, security, and failure behavior.
- Publish health and build metadata.

Required test layers:

- Domain and action unit tests.
- Application context and action-registration tests.
- Local retrieval/index lifecycle tests.
- Chat-session integration tests.
- Tenant and PII regression tests.
- Docker or packaged-jar smoke test.
- Optional keyed OpenAI smoke test.

Intentional failure:

- Configure an unavailable provider while generation is enabled.
- Verify the failure is explicit and no deterministic answer is presented as live AI.

Field lessons from release verification:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| Health reports the expected commit, but the public UI still uses an old endpoint or behavior. | Wrapper metadata changed while an older generated bundle remained cached or deployed. | Verify the HTML, referenced JavaScript asset, and backend build metadata independently. | Search the served bundle for a release marker or new endpoint and call the backend health endpoint directly. |
| A demo appears intelligent, but users cannot tell whether it uses OpenAI, ONNX, or a deterministic local provider. | Runtime provider posture is absent or described only in documentation. | Return provider name/mode in health diagnostics and render it without marketing reinterpretation. | Local and live profiles produce distinct health output; provider failures stay visible. |
| Security and privacy claims pass only through screenshots. | Governance proof was left to frontend labels rather than deterministic tests. | Include tenant, access, and PII regression tests in the packaged application gate. | The same no-key test suite proves denied access and redacted persistence before release. |

Done when:

- `./mvnw clean verify` passes from a clean checkout.
- The packaged app starts in the local profile.
- Health includes application status and deployed source metadata.
- Skipped live-provider tests state exactly why they were skipped.

Sources:

- `docs/course/core/07-test-and-ship/notebooklm/AI_FABRIC_TESTING_SHIPPING_WORKFLOWS_NOTEBOOKLM_SCRIPT.md`
- `docs/getting-started/11-testing-and-verification.md`
- `docs/getting-started/13-production-checklist.md`
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md`

## Production Track

### PROD-01: OpenAI And Local ONNX Provider Profiles

**Duration:** 75 minutes

**Keys required:** OpenAI portion only

**NotebookLM pre-lesson theory:**

- The separate roles of generation models and embedding models, and how AI Fabric uses Spring AI
  underneath provider-neutral application contracts where appropriate.
- The local path using ONNX embeddings and the live path using OpenAI generation/embeddings,
  including which capabilities each profile enables.
- How profile selection, model name, endpoint, key, dimensions, and runtime bean availability resolve
  into the effective provider posture.
- Why provider diagnostics may expose safe model/mode facts but never credentials, and why a failed
  live provider must not be hidden by a local or deterministic fallback.

Build:

- Keep local ONNX embeddings available without cloud keys.
- Add OpenAI generation and embedding configuration through Spring AI.
- Externalize model, base URL, key, and embedding dimensions.
- Expose provider diagnostics without exposing credentials.

Failure exercise:

- Use an invalid key or endpoint and confirm the provider error remains visible.

Field lesson - provider posture must be runtime truth:

- **Symptom:** a useful result appears, but the learner cannot determine whether it came from OpenAI,
  ONNX, a deterministic test provider, or a fallback.
- **Cause:** provider configuration exists but health and response diagnostics do not expose the
  selected runtime path.
- **Correct pattern:** report provider name, provider mode, model where safe, and enabled AI
  capabilities without exposing credentials.
- **Proof:** local and OpenAI profiles return distinct diagnostics, and an invalid OpenAI key produces
  an explicit failure rather than a local answer labeled as live AI.

Done when:

- Local and OpenAI profiles are explicitly distinguishable.
- Tests prove secret redaction.
- The selected provider appears in diagnostics.

Sources:

- `docs/getting-started/07-real-provider-openai.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md`

### PROD-02: Indexing, Backfill, And Vector Lifecycle

**Duration:** 90 minutes

**NotebookLM pre-lesson theory:**

- The complete vector lifecycle: create, update, delete, reindex, backfill, query, and readiness
  proof, including how it relates to the application's source-of-truth data lifecycle.
- How resumable backfill, stable IDs, checkpoints, retries, and idempotency prevent duplicate or
  partially migrated evidence.
- Why embedding-model or dimension changes are index-schema migrations rather than ordinary runtime
  configuration changes.
- Why useful readiness is defined by a golden query retrieving expected evidence, not merely by
  database rows, vector counts, or a healthy process.

Build:

- Index new records.
- Update indexed content.
- Delete records and associated vectors.
- Backfill existing data.
- Report vector readiness and counts.

Failure exercise:

- Change embedding dimensions without changing the Lucene index path.
- Observe the mismatch and apply the documented migration/reset behavior.

Field lesson - database readiness is not vector readiness:

- **Symptom:** source records and vector counts look plausible, but the golden query cannot retrieve
  the expected evidence after a migration or dimension change.
- **Cause:** the data lifecycle, embedding configuration, and vector lifecycle moved out of sync.
- **Correct pattern:** make backfill resumable, make dimension/index changes explicit, and define
  readiness in terms of retrievable evidence rather than database row counts.
- **Proof:** the same lifecycle smoke previews, indexes, queries, updates, deletes, and safely repeats
  a backfill.

Done when:

- Search reflects create, update, and delete.
- Backfill is repeatable or safely resumable.
- Readiness proves expected evidence is retrievable, not merely present in the database.

Sources:

- `docs/Framework-Dev-Guides/retrieval-vectorization/MIGRATION_BACKFILL_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
- `examples/real-apps/vector-readiness-playground/README.md`
- `examples/real-apps/migration-enabled-product-catalog/README.md`

### PROD-03: RAG Quality And Prompt Regression

**Duration:** 60-75 minutes

**NotebookLM pre-lesson theory:**

- The distinct quality layers in RAG: source-data quality, indexing completeness, retrieval relevance,
  context construction, prompt behavior, generated answer, and evidence projection.
- How golden questions and expected evidence IDs create deterministic retrieval regression tests
  even when generated wording can vary.
- How default curated prompts and narrow application overlays are resolved, and why domain-specific
  interpretation should not silently weaken shared defaults.
- The proper role of evaluator-backed checks: useful additional evidence, never permission to pass an
  unsupported answer when required sources are missing.

Build:

- Define golden support questions and expected evidence IDs.
- Record retrieval scores and missing-evidence failures.
- Add optional evaluator-backed checks.
- Preserve prompt and mode identifiers in diagnostics.

Failure exercise:

- Remove a required article and run the quality gate.
- Verify the gate fails rather than grading an unsupported answer as acceptable.

Field lesson - improve app-specific interpretation without weakening framework defaults:

- **Symptom:** retrieved evidence and backend history are correct, but a short domain follow-up is
  still classified incorrectly.
- **Cause:** the app requires a domain rule that does not belong in every AI Fabric application.
- **Correct pattern:** override only the affected prompt files with an app bundle overlay and retain
  the shared curated bundle as fallback.
- **Proof:** prompt-resolution tests assert overlay precedence and default fallback; golden multi-turn
  requests pass before the prompt change is released.

Done when:

- Golden questions pass with expected sources.
- No-evidence cases are explicit.
- Prompt changes can be regression-tested before release.

Sources:

- `examples/real-apps/smart-faq-assistant/README.md`
- `docs/Framework-Dev-Guides/testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

### PROD-04: Move From Local Lucene To A Managed Vector Provider

**Duration:** 75-90 minutes

**Docker required:** Yes for the recommended Qdrant lab

**NotebookLM pre-lesson theory:**

- The stable AI Fabric vector contract versus provider-specific storage, lifecycle, filtering,
  diagnostics, and administration capabilities.
- The request flow from application retrieval through provider selection, collection/index,
  embedding dimensions, metadata filters, and Qdrant similarity results.
- What remains portable between Lucene and Qdrant and what must be configured or operationally
  verified for each provider.
- Why an unavailable endpoint, collection mismatch, dimension mismatch, or rejected filter must stay
  visible rather than triggering an unreported provider substitution.

Build:

- Keep the AI Fabric retrieval contract unchanged while selecting a different vector provider.
- Run Qdrant locally with Docker.
- Configure collection dimensions to match the selected embedding provider.
- Preserve tenant and entity metadata filters.
- Compare provider readiness, lifecycle, and admin diagnostics with the Lucene profile.

Failure exercise:

- Start with an unavailable Qdrant endpoint or a dimension mismatch.
- Verify readiness and search diagnostics identify the real provider problem.
- Correct provider configuration without changing application retrieval code.

Field lesson - provider substitution must not hide provider failure:

- **Symptom:** a managed-vector profile appears healthy because the application silently answered
  from another store or from model knowledge.
- **Cause:** fallback behavior obscured which provider served retrieval.
- **Correct pattern:** keep the AI Fabric retrieval contract stable while making the selected provider,
  collection, dimensions, and failure state explicit.
- **Proof:** the Qdrant profile fails visibly while Qdrant is unavailable and passes the same golden
  retrieval and tenant-filter tests after correction.

Done when:

- The same golden retrieval tests pass against Lucene and Qdrant profiles.
- Tenant metadata remains enforced.
- Provider-specific failure is visible and is not replaced by another provider silently.

Sources:

- `examples/real-apps/vector-readiness-playground/README.md`
- `examples/real-apps/cloud-qdrant-openai-vector-search/README.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`

### PROD-05: Operations And Release Readiness

**Duration:** 60 minutes

**NotebookLM pre-lesson theory:**

- The production topology from immutable application artifact and environment configuration to
  provider/vector dependencies, health endpoints, frontend deployment, and release evidence.
- Build metadata provenance: why source commit, build time, course/framework version, and served
  assets must describe the artifact actually handling requests.
- Public-demo state architecture, including canonical templates, per-session clones, explicit reset,
  bounded retention, and dependency-aware cleanup.
- The separation between deterministic local gates, secret-backed provider gates, deployment smoke,
  and live browser verification, with no hidden fallback between them.

Build:

- Package the app with Docker.
- Include source commit, course version, and build time in readiness output.
- Document required and optional environment variables.
- Run local smoke and optional live-provider gates.

Field lessons from public operations:

| Symptom | Cause | Correct pattern | Proof |
| --- | --- | --- | --- |
| One visitor changes another visitor's scenario, or refresh resets shared users. | A mutable public demo reused canonical seed records. | Clone scenario state per browser session, make reset explicit, and delete expired clones with a bounded TTL job. | Two concurrent sessions mutate independently; cleanup removes expired clones but preserves canonical templates. |
| The frontend commit marker is current while behavior is stale. | Deployment metadata and generated asset deployment diverged. | Verify HTML, served bundle, backend health, and a build-specific behavior before live smoke. | A release script records all four checks and the exact URLs/artifact names inspected. |
| A deterministic demo is described as live AI. | Documentation or UI inferred provider posture instead of reading runtime state. | Expose provider mode and capability posture from the backend and render it verbatim. | Health and UI agree for local and live profiles; failed LLM analysis is not replaced by a hidden rule. |
| Session clones grow indefinitely. | Isolation was added without a lifecycle contract. | Configure cleanup enablement, cutoff/TTL, schedule, ownership predicates, and deletion order. | Cleanup tests protect canonical records and remove all dependent state for expired sessions. |

Done when:

- Deployment metadata identifies the running commit.
- Reset and seed operations are explicit and protected appropriately.
- Logs and diagnostics contain no API keys or raw PII.
- The release checklist records every executed test layer.

Sources:

- `docs/getting-started/13-production-checklist.md`
- `docs/Framework-Dev-Guides/testing-verification/VERIFICATION_PLAYBOOK.md`
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md`

## Real-App Case Studies

Case studies are not substitutes for the continuing lab app. They show how the same framework
contracts adapt to different domains.

### CASE-01: AI Shopping Experience

Reference: `examples/real-apps/chat-capabilities-demo`

**NotebookLM pre-lesson theory:**

- The commerce architecture connecting products, policies, reviews, coupons, and support evidence to
  separate vector spaces and orchestration modes.
- How staged indexing changes what RAG can truthfully answer while ordinary product/cart pages remain
  application features rather than proof of retrieval.
- How attachments, conversation context, and target resolution let natural language refer to visible
  products without exposing canonical SKU handling to the user.
- The cart/checkout action flow through typed parameters, confirmation, domain execution, and
  commerce-specific result projection.

Teach:

- Staged RAG readiness.
- Product and policy evidence.
- Attachments and target resolution.
- Cart and checkout actions.
- Confirmation and domain-specific action results.

Deployed failure postmortem:

- **What failed:** a demo stage could look RAG-ready before the corresponding evidence was indexed,
  and generic action cards could expose too much backend structure.
- **Why:** source-data presence was confused with retrievable evidence, while the UI had no
  commerce-specific result projection.
- **Corrected design:** make every evidence stage explicit, route prompts through the real
  orchestration mode, and project cart/order outcomes from structured action results.
- **Proof learners inspect:** reset to no evidence, run the same prompt after each indexing stage,
  attach products for target resolution, and prove a confirmed cart or checkout mutation executes
  exactly once.

### CASE-02: AI Fabric Account Resolver

Reference: `examples/real-apps/ai-fabric-account-resolver`

**NotebookLM pre-lesson theory:**

- The resolver loop: read the current account profile -> retrieve applicable policy evidence -> let
  the model explain blockers -> select a permitted remedy -> collect user-owned parameters ->
  confirm -> execute.
- Why account identity and subscription context come from authenticated application state while card
  references, addresses, cancellation intent, or refund details may come from the user.
- How bounded iterative planning, backend conversation memory, and a resolver prompt overlay support
  short follow-ups without hard-coded browser intelligence.
- How application policy decides approval versus review and returns an explanation that AI Fabric can
  present without the UI inventing business reasons.

Teach:

- Resolver mode.
- Reading current-account facts.
- Policy-grounded blocker explanation.
- Payment, address, cancellation, plan, and refund actions.
- Backend-owned follow-up context.

Deployed failure postmortem:

- **What failed:** the live pipeline initially failed at AccessControl; later, context-owned IDs were
  exposed to the model and short follow-ups lost their intended action.
- **Why:** required application policy and chat-session hooks were missing, and resolver-specific
  follow-up behavior had not been expressed through an app prompt overlay.
- **Corrected design:** provide fail-closed access policy, resolve identity/account values from
  `ActionContext`, use bounded iterative reads, enable `ai-fabric-chat-session`, and return policy
  explanations in action results.
- **Proof learners inspect:** run blocker diagnosis, short follow-up, missing-parameter collection,
  confirmation, execution, and policy-explained refund outcomes with the same conversation ID and no
  browser-built history.

### CASE-03: AI Fabric Behavior Signals

Reference: `examples/real-apps/behavior-churn-signals`

**NotebookLM pre-lesson theory:**

- The behavior-analysis flow from raw application events to normalized evidence, previous insight,
  only newly observed events, structured model analysis, and stored current insight.
- The difference between event facts, LLM interpretation, application-owned action catalogs, and
  ordinary analytics rendering.
- How an allowlisted component catalog is described to the model and converted into a short
  structured `{name, reason}` selection for agentic UI composition.
- Why provider posture, session isolation, and explicit generation failures are necessary to prove
  that changing insights and components came from the declared AI workflow.

Teach:

- Raw application events as evidence.
- Previous insight plus newly recorded events.
- Structured behavior insight.
- LLM-selected allowlisted UI components.
- Explicit AI failure without hidden fallback.

Deployed failure postmortem:

- **What failed:** the public page did not make clear whether behavior insight came from a live LLM or
  a deterministic local provider, and follow-up analysis could resend old events as if they were new.
- **Why:** runtime provider posture and incremental-analysis boundaries were not visible.
- **Corrected design:** expose provider mode in health, analyze previous insight plus only newly
  recorded events, keep component selection allowlisted, and isolate each visitor's mutable data.
- **Proof learners inspect:** compare local and live profiles, add positive and negative events,
  regenerate insight, observe an LLM-selected home composition, and verify an LLM failure remains
  visible.

### CASE-04: AI Fabric Tenant Guard

Reference: `examples/real-apps/tenant-knowledge-portal`

**NotebookLM pre-lesson theory:**

- The tenant-safe retrieval flow from verified identity and role to metadata scope, access policy,
  vector query, evidence boundary proof, and generation.
- How tenant metadata filtering and application authorization complement each other, and why neither
  tenant-separated UI columns nor prompt instructions establish isolation.
- The governed-write path for tenant-owned content, including confirmation, rejection, deletion, and
  evidence that another tenant's IDs never entered the model context.
- How per-session mutable demo state and cleanup preserve simultaneous-user isolation without
  weakening canonical tenant boundaries.

Teach:

- Tenant metadata and retrieval boundaries.
- Role-aware evidence.
- Governed writes.
- Denied cross-tenant scenarios.

Deployed failure postmortem:

- **What failed:** tenant-separated UI columns looked convincing without proving that backend
  retrieval, writes, and deletion were actually tenant-scoped.
- **Why:** the presentation layer described governance that needed to be enforced and evidenced by
  backend services.
- **Corrected design:** apply metadata and role constraints before evidence is returned, include a
  backend-generated boundary proof, require confirmation for writes, and scope mutable state by
  session.
- **Proof learners inspect:** compare allowed and denied tenant requests directly, verify denied IDs
  never reach generation, execute and reject governed writes, and inspect deletion evidence and
  remaining tenant IDs.

### CASE-05: AI Fabric Privacy Shield

Reference: `examples/real-apps/privacy-first-customer-facing-support`

**NotebookLM pre-lesson theory:**

- The sensitive-data path from backend intake through PII detection, configured processing mode,
  safe persistence, sanitized indexing, sanitized query, and proof DTO.
- Why browser masking is presentation only and cannot prove what reached logs, storage, embeddings,
  vector retrieval, or another session.
- The distinction between redacted values, optionally protected originals, safe metadata, and data
  that must never be supplied to a generation provider.
- Why privacy capability proof can run without live generation, and how health diagnostics must state
  that posture rather than implying an LLM was used.

Teach:

- PII detection and redaction.
- Safe support-message storage.
- Sanitized indexing and retrieval.
- Difference between privacy capability proof and live LLM generation.

Deployed failure postmortem:

- **What failed:** a polished browser could appear privacy-safe while masking input locally or showing
  claims that did not prove what reached storage and retrieval.
- **Why:** privacy was at risk of becoming presentation behavior instead of a backend data-boundary
  contract.
- **Corrected design:** process PII before persistence and indexing, sanitize retrieval queries,
  return safe proof DTOs, isolate sessions, and report whether live generation is enabled.
- **Proof learners inspect:** submit known test PII, verify redacted API and stored/indexed content,
  search with a sensitive query, prove the processed query reached retrieval, and verify another
  session cannot see the record.

Each case-study page must include:

- Business problem.
- AI Fabric modules.
- Annotated or configured entities.
- Provider and vector choices.
- Request and data flow.
- Actions and policy boundaries.
- Test evidence.
- Live demo link.
- Backend source link.
- Deployed failure postmortem with symptom, cause, corrected design, and reproduction proof.
- A statement identifying which behavior is live AI, deterministic framework behavior, or ordinary
  application logic.

## Coding-Assistant Track

The per-lesson assistant prompts let a learner delegate execution immediately. This track teaches the
skills needed to use those prompts responsibly: context selection, ownership diagnosis, diff review,
independent verification, and refusal to accept invented APIs or unproved success. Copying a prompt is
not the same as understanding or reviewing its result.

### ASSIST-01: Give An Assistant Correct Context

**Duration:** 30 minutes

**NotebookLM pre-lesson theory:**

- How a coding assistant's output is constrained by its supplied evidence, including release-pinned
  docs, current source, the capability map, task guide, nearest real app, and relevant field lesson.
- The context hierarchy that separates canonical contracts from examples, planning documents,
  generated explanations, and stale or unverified claims.
- Why the assistant must first map ownership across application, AI Fabric, provider, vector/data,
  UI, and deployment boundaries before proposing a change.
- The limits of assistant evidence: plausible code is not proof of a real API, executed test,
  provider call, deployment, security property, or successful user flow.

Provide context in this order:

1. `docs/getting-started/00-llm-start-here.md`
2. `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md`
3. `docs/llm-context/AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md`
4. The relevant section of
   `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`.
5. `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`
6. The relevant Getting Started task guide.
7. The nearest real-app README.

Starter prompt:

```text
You are helping me build an AI Fabric application.
Use AI Fabric 0.3.3, Java 21, Spring Boot 4.1.x,
Maven group io.github.loom-ai-labs, and framework packages ai.fabric.*.

Read the supplied AI Fabric documents before proposing code.
Do not invent framework APIs.
Do not fake AI intelligence in the UI or backend.
Keep domain state, authorization, and side effects in application services.
Require confirmation for writes.
Use backend-owned chat-session memory.
Before editing, identify modules, entities, providers, actions, policies, and tests.
```

### ASSIST-02: Implement A Small Capability Safely

**Duration:** 45-60 minutes

**NotebookLM pre-lesson theory:**

- A bounded implementation prompt as an engineering contract: pinned starting state, allowed scope,
  current API evidence, required behavior, intentional failure, validation, and stop conditions.
- The assistant workflow of inspect -> explain ownership -> plan -> edit -> test -> report, and why
  jumping directly to generated code increases framework and domain mistakes.
- The difference between the implementation session's claims, the actual diff, independently run
  tests, and a fresh findings-first review.
- Why the learner remains responsible for security boundaries, confirmation behavior, provider
  truth, unrelated worktree state, and explaining every accepted change.

Task template:

```text
Goal:
Build [capability] for [domain application].

AI Fabric capabilities:
- [semantic search/RAG/actions/chat memory/security/provider]

References:
- docs/getting-started/[task].md
- examples/real-apps/[closest-app]/README.md
- docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md#[relevant-lesson]

Constraints:
- Use application-owned handlers and policies.
- Writes require confirmation.
- Return trusted, domain-shaped action facts.
- Do not display raw internal JSON.
- Do not hide LLM or provider failures.
- Preserve a local no-key test path.

Validation:
- Reproduce any existing failure through the backend API before editing.
- Identify whether the owning boundary is the app, framework, provider, data/index, UI, or deployment.
- Add tests before or with the change.
- Run [exact commands].
- Explain any unexecuted live-provider checks.
```

Lab:

- Ask an assistant to add one action or retrieval field to the course app.
- Start from the published `assistant-prompt.md` for the selected lesson rather than improvising an
  unbounded request.
- Require it to identify the current framework API from code or canonical docs.
- Give it one field failure from the troubleshooting playbook and require an ownership diagnosis
  before it proposes a change.
- Review its diff manually.
- Run tests independently.
- Ask it for a release-readiness review.

Done when:

- The generated code follows current AI Fabric contracts.
- The learner can explain every change.
- Tests prove the behavior.
- No duplicate orchestration, history, or policy layer was introduced.
- The independent `assistant-review-prompt.md` identifies any deliberate defect inserted for the lab.

### Assistant Review Checklist

- Are coordinates, release version, and packages current?
- Was the failing backend request reproduced directly before changing code?
- Does the diagnosis name the owning boundary instead of guessing from the browser message?
- Does every annotated action have exactly one `@ActionExecute` method?
- Are non-context action parameters annotated with `@Param`?
- Are context-owned identity, tenant, account, cart, and session values absent from model-visible
  parameters?
- Do write actions use `WRITE_ONLY` or `READ_WRITE` and require confirmation?
- Are answers grounded by evidence or trusted action facts?
- Is access enforced before evidence or execution?
- Does the UI avoid raw action JSON?
- Are provider failures visible?
- Does backend chat-session state own conversation history?
- Are `mode` and `position` used according to their distinct contracts?
- Were focused tests added and run?

## Shared Troubleshooting Playbook

Every course lesson uses the same ownership-first diagnostic sequence. A browser card is a symptom,
not a root-cause location.

### Diagnostic Sequence

1. Reproduce the exact backend request with `curl`, an HTTP request file, or an integration test.
2. Record status, response type, pipeline step, diagnostics, user/session/tenant identifiers, and the
   selected mode without copying secrets or raw PII.
3. Classify the failing boundary: controller, orchestration policy, access policy, provider, vector
   lifecycle, action schema/handler, chat session, domain service, UI projection, or deployment.
4. Verify app-owned hooks, dependencies, effective typed configuration, and indexed data before
   changing framework code.
5. Add the smallest regression proof at the owning boundary and then repeat the end-to-end request.
6. For a deployed application, verify backend build metadata and the actual served frontend bundle
   before judging the result.

### Triage Matrix

| Observed symptom | Inspect first | Required proof before closure |
| --- | --- | --- |
| `Pipeline step failed: AccessControl` | App-owned `EntityAccessPolicy`, verified identity, requested resource/operation | Allow known request; deny missing identity, unknown resource, and cross-tenant request |
| `Pipeline step failed: IntentExtraction` | Provider response, structured-output parsing/repair, resolved prompt bundle, conversation context | Golden intent request and malformed-provider-response regression |
| `Pipeline step failed: VectorSpaceResolution` | Entity/vector-space configuration, requested mode, retrieval allowlist | Typed configuration test and known-vector-space resolution test |
| `RAG module is not enabled` or no `RAGProvider` | Runtime POM/dependency graph and application context | Context test finds `RAGProvider`; packaged app starts with the expected module |
| RAG returns no expected evidence | Seed/index order, vector counts, embedding dimensions, metadata, allowlist | Golden query returns the expected evidence ID after indexing and not before |
| Natural-language action does not execute | Intent/action selection, model-visible parameters, confirmation state, registry/handler | Clarification, confirmation, rejection, and execute-once tests |
| Short follow-up becomes `OUT_OF_SCOPE` | Stable conversation ID, `ai-fabric-chat-session`, app prompt overlay | Second request sends only the new turn and resolves the expected action/context |
| Action succeeds but chat shows raw JSON | Active frontend result renderer and domain projection | Compact user-facing card plus preserved structured API result |
| Action status lacks an explanation | Domain service and `ActionResult` policy fields | Approved and review-required tests include policy decision and explanation |
| Browser reports API offline | Backend health, exact route, CORS, deployed frontend backend URL | Direct health/route request and browser request both reach the intended backend |
| Commit is current but behavior is stale | HTML asset references, served JavaScript bundle, CDN/deployment cache | Served bundle contains the new marker or endpoint and backend health reports the intended commit |
| Demo behavior looks scripted or too perfect | Frontend prompt handlers, manual endpoints, provider posture | Prompt reaches orchestration; UI labels deterministic versus live provider behavior accurately |
| One visitor changes another visitor's demo | Session-scoped data and reset/cleanup endpoints | Two-session isolation test and TTL cleanup test |
| Privacy appears to work only in the UI | Backend detection, processed DTO, persistence/indexing path, query sanitization | Raw PII absent from response, logs, storage, index, and cross-session retrieval |

### Evidence Bundle For A Resolved Failure

Each intentional-failure exercise and case-study postmortem should retain:

- the minimal request and sanitized response;
- the effective module/provider/mode configuration;
- the test that failed before the correction and passed afterward;
- vector evidence IDs or action result facts where relevant;
- backend build metadata and frontend bundle marker for deployed behavior;
- a short ownership statement explaining why the fix belongs in the application, framework,
  provider configuration, data lifecycle, UI, or deployment.

Do not close a lesson failure because the UI looks better. Close it when the owning contract and its
regression proof are correct.

## Capstone

### Goal

Build a production-oriented AI Fabric vertical slice for a domain application. The capstone may use
the Support Knowledge Assistant or a learner-owned domain.

### Minimum Requirements

- One searchable application entity.
- One explicit vector space with stable metadata.
- One evidence-grounded RAG endpoint.
- One read action.
- One confirmation-required write action.
- Backend-owned chat memory.
- Tenant or user access policy.
- One privacy or sensitive-data rule.
- Local no-key profile.
- Optional OpenAI profile.
- Index create, update, and delete proof.
- Unit, integration, and packaged-app smoke tests.
- One reproduced field failure with an ownership diagnosis, corrective change, and regression proof.
- README with setup, environment variables, requests, reset, and troubleshooting.

### Suggested Domains

- Support knowledge and ticket resolution.
- Account repair.
- Tenant knowledge portal.
- Product advisor with governed cart actions.
- Document intake and retrieval workbench.

### Assessment Rubric

| Area | Weight | Pass condition |
| --- | ---: | --- |
| Framework fit | 15% | Uses AI Fabric directly without a duplicate mini-framework |
| Retrieval | 15% | Indexed evidence is relevant, visible, and lifecycle-tested |
| RAG | 10% | Answers distinguish grounded evidence from no-evidence state |
| Actions | 15% | Writes require confirmation and return trusted facts |
| Memory | 10% | Follow-ups work from backend state |
| Security and privacy | 15% | Access fails closed and sensitive data follows declared policy |
| Testing | 15% | Deterministic and optional live-provider gates are documented, including one field-failure postmortem |
| Operations | 5% | Health, build metadata, configuration, and reset are clear |

A capstone passes at 75% only when every security, action-confirmation, and testing pass condition is
also satisfied. A high total cannot compensate for unsafe writes or cross-tenant leakage.

## NotebookLM Production Contract

NotebookLM is a production aid for conceptual explanations, not the source of framework truth.
Create one notebook per video and upload exactly one self-contained production script. Do not upload
the repository, lesson pack, implementation prompt, or several supporting sources: they encourage
the generated explanation to drift away from the video's learning objective. A lesson does not need
a video merely to satisfy a uniform template.

Coding-assistant prompts and NotebookLM production scripts serve different purposes. The former may
edit and verify the learner repository; the latter generates reviewed educational media. Never use a
NotebookLM production script as an implementation prompt or present generated video narration as
execution evidence.

Theory media follows the learning purpose rather than a per-lesson quota:

1. The Quickstart is action-first. It has one short written AI Fabric introduction and no required
   NotebookLM video.
2. CORE-01 publishes four focused, reviewed explainers before its analysis exercise. **What Is AI
   Fabric?** explains purpose, fit, ownership, and the main workflows. **AI Fabric Architecture And
   Module Map** then explains auto-configuration, annotations, orchestration, provider boundaries,
   required versus optional modules, and the smallest valid dependency sets. **AI Fabric Request
   Lifecycle** then traces the ordered runtime path from trusted entry through policy, intent,
   retrieval or actions, provider calls, finalization, and backend persistence. **AI Fabric
   Configuration And Extension Model** then explains conditional bean activation, scoped precedence,
   provider and prompt selection, curated defaults, and application extension contracts.
3. Later lessons may publish a reviewed 5-8 minute **pre-lesson architecture explainer** when a new
   conceptual model materially helps the developer. It must cover only the lesson's declared theory
   topics: business purpose, architecture/request flow, ownership boundaries, and important failure
   behavior.
4. Any lesson may publish an optional maintainer-recorded lab walkthrough when exact terminal output,
   IDE edits, or live application behavior needs to be visible.

The NotebookLM explainer prepares the learner to understand the lab; it is not a generated
code-along. It may show a small reviewed code shape when needed to explain a contract, but it must
not narrate every edit or command. The lesson page owns implementation steps, and a maintainer
walkthrough owns any exact screen-by-screen execution demonstration.

The website lesson, checkpoint, and tests remain authoritative. A generated video must never be the
only place where a command, API contract, expected result, or security requirement is documented.

### Single-Source Production Contract For Theory Media

Each lesson that declares a NotebookLM video has a `notebooklm/` directory containing one complete
production script and one maintainer-facing provenance manifest:

```text
notebooklm/
  <DESCRIPTIVE_NAME>_NOTEBOOKLM_SCRIPT.md
  source-manifest.yml
```

Production-script rules:

- Upload only the production script referenced by `source-manifest.yml` under
  `outputs.transcript`. Never upload all paths listed under `sources`.
- Start the script with generator instructions that explicitly say it is the only source and that
  unsupported external knowledge must not be added.
- Include production direction with title, audience, target duration, voice, learning objective, and
  visual style.
- Use ordered scenes. Every scene contains both reviewed visual direction and complete narration.
- Start with the application problem, trace at least one end-to-end request or data flow, identify
  ownership boundaries, show an important failure path, and finish with the practical lab handoff.
- Include a final request/ownership reference when the topic crosses application, framework,
  provider, storage, UI, or deployment boundaries.
- End with explicit accuracy guardrails covering APIs, module names, provider responsibility,
  security, execution claims, and unsupported metrics.
- Include only capabilities declared by the lesson and current framework release.
- The provenance manifest lists the script and exact source-code/documentation paths maintainers used
  to verify it. Those paths are audit evidence, not NotebookLM input.
- Keep assessment and implementation instructions in `knowledge-check.yml`, `assistant-prompt.md`,
  and `assistant-review-prompt.md`. Do not embed answer keys or a code-along in the video script.
- Explain which output comes from an LLM, AI Fabric, a provider, vector or data storage, and
  application code.
- Remove secrets, private operational notes, and live customer data.
- Keep expected LLM wording outcome-based. Do not require a model to reproduce one exact paragraph.

### Video Review Gate

Every generated video must be reviewed against:

- Complete and accurate coverage of every declared pre-lesson theory topic.
- A correct end-to-end request/data flow and explicit ownership boundaries.
- Clear separation between theory orientation and the practical lab walkthrough.
- Current framework enum values and annotations.
- Current Maven coordinates and release version.
- Correct action handler and confirmation flow.
- Correct provider and vector responsibility.
- Correct test commands.
- No unsupported performance, uptime, accuracy, compliance, or production-readiness claims.
- No implication that deterministic fixtures are live AI.
- No raw secrets, PII, or private documentation.

Reject and regenerate a video when any code, API, or security statement is wrong. Editing the
single-source production script is preferable when NotebookLM repeatedly misinterprets a statement.

After review, record the following in lesson front matter:

- Public or embedded video URL.
- Transcript path.
- Course source tag.
- Reviewer and review date.
- Output language.
- Purpose (`pre-lesson-theory`) and placement (`before-lab`).
- Reviewed theory-topic coverage and target/actual duration.
- Any correction note that learners must see.

## Website Course Experience

### Routes

```text
/course
/course/quickstart
/course/core/:lessonSlug
/course/production/:lessonSlug
/course/case-studies/:caseSlug
/course/coding-assistants
/course/troubleshooting
/course/capstone
```

### Course Hub

The hub shows:

- Public title and outcome.
- Current pinned framework version.
- Quickstart call to action.
- Track cards with honest duration.
- Prerequisites.
- Continuing application architecture.
- Progress summary stored locally unless accounts are introduced later.
- Links to the learner repository, Maven Central, live demos, and community.
- A visible troubleshooting entry that starts from observed symptoms rather than framework modules.
- A clear statement that every executable lesson supports manual and assistant-assisted completion.

### Lesson Page

Each lesson page renders:

- Track, lesson number, duration, and version badge.
- Learning outcome.
- When the lesson declares theory media, the reviewed NotebookLM architecture explainer immediately
  after the outcome and before either implementation path.
- For those lessons, a clear `Theory first` label, video purpose, framework/course version, duration,
  transcript, and production-script provenance link.
- Architecture/request-flow visual.
- Exact files and code excerpts.
- A segmented path control for `Build manually` and `Use a coding assistant`.
- Copy controls for the lesson-specific implementation prompt and independent review prompt.
- Assistant mode, validated starter ref, review status, and explicit no-commit/no-deploy boundaries.
- Copyable commands and requests.
- Expected output and evidence fields.
- Intentional failure exercise.
- Field lesson with symptom, cause, correct pattern, and regression proof.
- Test and completion checklist.
- A knowledge check with reviewed explanations and source evidence.
- Starter and solution checkpoint links.
- Reset and troubleshooting sections.
- Previous and next lesson navigation.
- A downloadable NotebookLM production script and provenance manifest for transparent source review
  when theory media is declared.
- An optional maintainer-recorded lab walkthrough beside the relevant implementation or verification
  section, never presented as the theory explainer.

Copying or opening an assistant prompt must not mark the lesson complete. Completion still requires
the shared tests, intentional-failure proof, reviewed diff, "done when" checks, and knowledge-check
contract. The website may offer provider-neutral copy/download controls; it must not claim it
executed a local coding assistant unless a separately designed and authenticated integration
actually did so.

Choice questions are scored locally from the pinned answer key. The interface shows why an answer is
correct and links back to the relevant lesson evidence. For `answer-reveal` and
`implementation-defense`, it records only that the learner reviewed the answer or criteria; it must
not display an invented AI score. Without user accounts, progress and attempts remain browser-local
and are explicitly described as self-assessment rather than certification.

The website must not execute fake AI responses. Interactive results must come from a deployed course
backend or be clearly labeled static expected-output examples.

### Website Content Sync

Add a repeatable command in `aifabric`, for example:

```bash
npm run course:sync -- --course-ref ai-fabric-course-v0.3.3.1
npm run course:verify
```

The exact implementation may clone the tagged framework repository or download the tagged archive.
The generated `source-manifest.json` must contain:

- Framework repository.
- Course source tag.
- Framework compatibility tag.
- Source commit.
- Course schema version.
- Synced file list and checksums.
- Generation timestamp.
- Assistant prompt paths, modes, validation status, and checksums.
- Knowledge-check paths, schema versions, question counts, pass thresholds, and checksums.
- For lessons that declare theory media: video purpose, placement, theory-topic checksum,
  source-manifest checksum, review status, duration, transcript, and public URL.

## Course Manifest

Create `docs/course/course.yml` before website implementation. It is the machine-readable navigation
and release contract.

Minimum shape:

```yaml
schemaVersion: 1
assistantPromptSchemaVersion: 1
knowledgeCheckSchemaVersion: 1
notebookLmVideoSchemaVersion: 1
courseId: ai-fabric-production-oriented-java
courseVersion: 0.3.3-course.1
title: Build Production-Oriented AI Workflows with Java and Spring Boot
subtitle: Semantic search, evidence-grounded RAG, governed actions, chat memory, and tenant security with AI Fabric
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
javaVersion: 21
springBootVersion: 4.1.x
learnerRepository: https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant
tracks:
  - id: quickstart
    title: Quickstart
    required: true
    lessons:
      - id: qs-01
        slug: first-useful-result
        source: quickstart/01-first-useful-result/lesson.md
        knowledgeCheck:
          source: quickstart/01-first-useful-result/knowledge-check.yml
          questionCount: 3
          passingScorePercent: 80
        video:
          purpose: pre-lesson-theory
          placement: before-lab
          targetDurationMinutes: 4
          theoryBrief: quickstart/01-first-useful-result/lesson.md#notebooklm-pre-lesson-theory
          sourceManifest: quickstart/01-first-useful-result/notebooklm/source-manifest.yml
          status: reviewed
        assistant:
          mode: implement
          implementationPrompt: quickstart/01-first-useful-result/assistant-prompt.md
          reviewPrompt: quickstart/01-first-useful-result/assistant-review-prompt.md
          validatedStarterRef: course-0.3.3-00-starter
          validationStatus: passed
          completionRequiresReview: true
  - id: core
    title: Core Course
    required: true
    lessons:
      - id: core-01
        slug: mental-model
        source: core/01-mental-model/lesson.md
sharedResources:
  - id: troubleshooting
    slug: troubleshooting
    source: AI_FABRIC_EXTERNAL_USER_COURSE.md#shared-troubleshooting-playbook
```

## Course CI And Release Gates

### Learner Checkpoint CI

Every starter and solution checkpoint must run in a clean environment:

1. Checkout only the learner repository.
2. Use Java 21.
3. Use an empty temporary Maven repository.
4. Resolve AI Fabric from Maven Central.
5. Run `./mvnw clean verify`.
6. Start the packaged app in the local profile.
7. Reset, seed, index, and execute the lesson smoke request.
8. Stop the app and upload test reports.

Do not install the framework reactor before this job. Doing so would hide a missing Maven Central
artifact or dependency.

### Documentation CI

The framework repository must validate:

- Course manifest schema.
- Every declared lesson and source path exists.
- A lesson either declares both NotebookLM video metadata and a source manifest or declares neither.
- Every declared NotebookLM production script covers its lesson theory topics, identifies itself as
  the only upload source, and has a source manifest that pins all reviewed paths and checksums.
- QS-01 declares no video and contains only the approved short AI Fabric introduction; CORE-01 owns
  the complete framework architecture introduction.
- Every lesson declares an existing `knowledge-check.yml` with the required track question count.
- Knowledge-check schema versions and lesson IDs match the course manifest and lesson front matter.
- Question IDs are globally unique; options, answer keys, explanations, source paths, and review
  criteria required by each question type are complete.
- Every lesson covers ownership or request flow, failure diagnosis, and verification; applicable
  tracks also cover transfer, and assistant implementation lessons include an implementation-defense
  question.
- No knowledge check contains unresolved placeholders, secrets, unsupported framework claims, or an
  LLM-graded completion path.
- Every lesson declares an assistant mode plus existing implementation and review prompt paths.
- Published assistant prompts contain no unresolved authoring placeholders and include the required
  scope, stop, test, reporting, no-commit, no-push, and no-deploy clauses.
- Prompt versions, starter refs, commands, expected evidence, and "done when" checks match the lesson.
- Internal links resolve.
- Framework version, compatibility tag, and course source tag agree with the manifest.
- Code-backed annotation and enum references match current source.
- Every published lesson includes its field lesson and links to the shared troubleshooting playbook.
- Every published case study includes a deployed-failure postmortem and reproduction proof.
- No stale `ActionAccessMode.WRITE` example exists.
- Real-app Maven commands use the reactor when sibling modules are required.
- Public demo count and names match the current real-app map.

### Website CI

The website must validate:

- Generated course checksums match the framework tag.
- Every manifest route renders.
- Previous and next navigation is complete.
- Code blocks and long paths fit desktop and mobile layouts.
- Declared videos have titles, captions when available, and source/version labels.
- Every published lesson that declares theory media places its reviewed explainer before the
  manual/assistant path and labels optional maintainer walkthroughs separately.
- For declared videos, purpose, theory-topic checksum, production-script provenance link, transcript,
  duration, and review status match the pinned course manifest.
- Starter and solution links resolve.
- Manual and assistant path controls render for every lesson and remain usable on mobile.
- Copying a prompt never marks a lesson complete; reviewed verification remains required.
- Rendered prompt metadata and checksums match the pinned course manifest.
- Every knowledge check renders accessibly on desktop and mobile with deterministic choice scoring,
  reviewed explanations, source links, answer-reveal behavior, and no fake semantic grading.
- Lesson completion requires the configured choice threshold and review of all non-scored questions.
- Rendered knowledge-check metadata and checksums match the pinned course manifest.
- No unpublished lesson is visible in public navigation.

### Assistant Prompt Validation Gate

Before a lesson's assistant path is published:

1. Start from a clean checkout of the declared starter ref with the framework reactor unavailable.
2. Give one supported coding assistant the final `assistant-prompt.md` and only its declared source
   pack; do not give it the solution checkpoint as an implementation source.
3. Confirm its diff remains within the allowed scope and preserves unrelated worktree state.
4. Run the lesson tests, intentional failure, regression proof, and "done when" checks independently.
5. Start a fresh assistant session with `assistant-review-prompt.md` and confirm it reviews the actual
   diff rather than trusting the first session's report.
6. Record assistant/product version, prompt checksum, starter ref, resulting commit or patch checksum,
   commands, outcomes, reviewer, date, and unresolved findings.
7. Set `validationStatus: passed` only when the manual and assistant paths reach behaviorally
   equivalent evidence. Wording and code layout may differ; security and contract behavior may not.

Provider-dependent prompts also run the deterministic local path. Optional live-provider evidence
belongs in the keyed gate and must remain explicitly unverified when credentials are unavailable.

### Optional Live-Provider Gate

Live OpenAI tests remain a separate keyed job. It must:

- Run only lessons marked `requiresOpenAi: true`.
- Use real credentials from secret storage.
- Record provider/model diagnostics without printing credentials.
- Fail when the provider call fails.
- Never replace failure with a local canned answer.

## Release Blockers Before Recording

These issues are verified against the `0.3.3` source and must be fixed before course recording:

### Blocker 1: Governed Action Example

`docs/getting-started/05-first-governed-action.md` currently uses the nonexistent
`ActionAccessMode.WRITE` and omits the required `@ActionExecute` and `@Param` shape.

Required fix:

- Use `WRITE_ONLY` for the example write action.
- Add exactly one `@ActionExecute` method.
- Add typed `@Param` parameters.
- Return a current domain-shaped `ActionResult`.
- Add a documentation or example compilation test.

### Blocker 2: Standalone No-Key Installation

`docs/getting-started/02-installation.md` selects `memory` and `smoke`, but the listed minimal
dependencies do not provide the memory vector module or the example-only smoke providers.

Required fix:

- Make the public quickstart use ONNX plus Lucene, with a tested model-download step.
- Keep deterministic providers in learner tests only, clearly labeled test fixtures.
- Remove any suggestion that external applications receive `smoke-support` from Maven Central.

### Blocker 3: Clean Real-App Commands

`docs/getting-started/11-testing-and-verification.md` runs individual app POMs that depend on the
sibling `smoke-support` module.

Required command shape:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl chat-capabilities-demo \
  -am test
```

Apply the same reactor form to smoke runtime instructions or explicitly install/package the reactor
first.

### Blocker 4: Website Documentation Drift

The framework and website copies of Getting Started content already differ. Implement the pinned
course synchronization contract before adding another manually copied content tree.

### Blocker 5: Public Demo Count

The previous curriculum mentioned four public demos. The case-study track now includes five:

1. AI Shopping Experience.
2. AI Fabric Account Resolver.
3. AI Fabric Behavior Signals.
4. AI Fabric Tenant Guard.
5. AI Fabric Privacy Shield.

## Implementation Backlog

### Phase 0: Correctness Gate

- [ ] Fix the governed-action guide and add compile-backed coverage.
- [ ] Replace the broken standalone smoke instructions.
- [ ] Correct real-app reactor commands.
- [ ] Align the framework and website real-app maps.
- [ ] Verify all Getting Started source links against `0.3.3`.

### Phase 1: Course Foundation

- [ ] Create `docs/course/course.yml`.
- [ ] Create the lesson directory structure.
- [ ] Implement the assistant prompt schema, authoring template, and static validation rules.
- [ ] Implement the knowledge-check schema, competency rules, deterministic validator, and authoring
  template.
- [ ] Publish the shared troubleshooting resource and field-lesson content schema.
- [ ] Implement optional theory-media metadata and validate declared single-source NotebookLM
  scripts and provenance manifests.
- [ ] Create the standalone learner repository.
- [ ] Add Maven wrapper, local ONNX setup, reset, seed, and readiness contracts.
- [ ] Add clean Maven Central consumer CI.
- [ ] Publish immutable starter and solution checkpoints.

### Phase 2: Quickstart Beta

- [ ] Write QS-01 completely.
- [ ] Author and validate QS-01 implementation and review prompts from the clean starter ref.
- [ ] Author and review the QS-01 knowledge check against its lesson evidence.
- [ ] Verify the no-index field failure and readiness proof from a clean checkout.
- [ ] Build the quickstart website page with manual/assistant path controls, prompt copy actions, and
  the accessible deterministic knowledge-check renderer.
- [ ] Verify QS-01 remains action-first, uses the approved short written introduction, and declares
  no theory-video publication gate.
- [ ] Run the quickstart with at least three external learners.
- [ ] Record setup time, failure points, and completion rate.
- [ ] Fix every reproducible blocker before recording the core track.

### Phase 3: Core Course

- [x] Publish the CORE-01 preview with four assigned theory videos, a user-directed architecture
  exercise, expected artifacts, intentional failure, assistant prompts, and a sourced knowledge check.
- [x] Publish the CORE-02 preview with searchable-evidence theory, an explicit projection and vector
  lifecycle lab, metadata failure proof, assistant prompts, and a sourced knowledge check.
- [ ] Implement CORE-01 through CORE-07.
- [ ] Generate and technically review the complete CORE-01 AI Fabric architecture explainer from its
  release-pinned NotebookLM script and source manifest.
- [ ] Author and validate the mode-appropriate implementation/analysis and review prompt for every
  core lesson.
- [ ] Add one executable field lesson and ownership diagnosis to every core lesson.
- [ ] Create and test every checkpoint.
- [ ] Publish lesson pages and reviewed videos incrementally.
- [ ] Review each Core video as a theory-first architecture explanation rather than a generated
  code-along.
- [ ] Add quizzes and completion checks.
- [ ] Validate every core knowledge check for track count, competencies, answer accuracy, and source
  evidence.
- [ ] Run a second external learner beta.

### Phase 4: Production And Case Studies

- [ ] Implement PROD-01 through PROD-05.
- [ ] Author and validate production implementation/verification prompts and case-study reproduction
  prompts.
- [ ] Publish all five code-backed case studies.
- [ ] Publish the deployed-failure postmortem and reproduction proof for every case study.
- [ ] Produce and review the Production and Case Study pre-lesson architecture explainers from their
  declared theory briefs.
- [ ] Publish reviewed production and case-study knowledge checks.
- [ ] Add optional live-provider CI.
- [ ] Add production and security review exercises.

### Phase 5: Coding Assistants And Capstone

- [ ] Publish the assistant context lesson and prompt sheet.
- [ ] Produce and review both Coding-Assistant pre-lesson theory videos without turning them into
  product-specific assistant tutorials.
- [ ] Publish assistant-track knowledge checks with mandatory implementation-defense questions.
- [ ] Test assistant ownership diagnosis against the shared troubleshooting playbook.
- [ ] Publish the capstone starter and rubric.
- [ ] Publish a bounded capstone kickoff prompt and mandatory findings-first review prompt without a
  domain solution.
- [ ] Require one reproduced field failure and regression proof in capstone submissions.
- [ ] Add project submission guidance.
- [ ] Collect learner projects, corrections, and testimonials with permission.

## Course Readiness Scorecard

| Area | Ready when |
| --- | --- |
| Technical correctness | Every snippet and checkpoint is compiled or exercised against the pinned release |
| Reproducibility | A clean learner machine can complete the quickstart without framework source |
| Lab quality | Every lesson satisfies the lesson contract |
| Assessment | Every lesson has a reviewed, source-backed knowledge check; deterministic scoring and self-review behavior pass CI |
| Assistant path | Every published prompt pair passes clean-starter execution, independent review, and manifest checksum validation |
| Website | Course manifest renders complete routes and navigation |
| Video | QS-01 remains action-first; CORE-01 publishes the complete framework introduction; every other declared theory video is reviewed, source-grounded, and placed before the lab; optional walkthroughs are labeled separately |
| Security | Unsafe writes, cross-tenant access, and hidden provider failures are tested |
| Versioning | Website, checkpoints, videos, and docs identify the same framework release |
| External proof | Beta learners complete the quickstart without maintainer intervention |

The course is ready for full public launch only when all ten areas pass. Until then, individual
lessons may be published as clearly versioned beta material.

## Authoritative References

Start with:

- `docs/getting-started/README.md`
- `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`
- `docs/release-notes/0.3.3.md`
- `examples/real-apps/README.md`

NotebookLM product references:

- `https://support.google.com/notebooklm/answer/16215270`
- `https://support.google.com/notebooklm/answer/16454555`
