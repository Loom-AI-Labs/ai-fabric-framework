# AI Fabric Course Materials

This directory is the canonical source for the public AI Fabric course. It defines the curriculum,
learner application, website publishing contract, single-source NotebookLM scripts, and release gates.

Start with:

- [Machine-readable course catalog](course.yml)
- [Build AI-Enabled Applications with Java and Spring Boot](AI_FABRIC_EXTERNAL_USER_COURSE.md)
- [QS-01 published lesson package](quickstart/01-first-useful-result/lesson.md)
- [Single-source course introduction video script](notebooklm/AI_FABRIC_COURSE_INTRO_NOTEBOOKLM_SCRIPT.md)
- [Single-source AI Fabric introduction video script](core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_INTRO_NOTEBOOKLM_SCRIPT.md)
- [Single-source architecture and module map video script](core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_ARCHITECTURE_MODULE_MAP_NOTEBOOKLM_SCRIPT.md)
- [Single-source request lifecycle video script](core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_REQUEST_LIFECYCLE_NOTEBOOKLM_SCRIPT.md)
- [Single-source configuration and extension model video script](core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_CONFIGURATION_EXTENSION_MODEL_NOTEBOOKLM_SCRIPT.md)
- [Single-source searchable evidence video script](core/02-model-and-index-data/notebooklm/AI_FABRIC_SEARCHABLE_EVIDENCE_NOTEBOOKLM_SCRIPT.md)
- [Single-source evidence-grounded RAG video script](core/03-evidence-grounded-rag/notebooklm/AI_FABRIC_EVIDENCE_GROUNDED_RAG_NOTEBOOKLM_SCRIPT.md)
- [Single-source governed actions and confirmation video script](core/04-governed-actions/notebooklm/AI_FABRIC_GOVERNED_ACTIONS_CONFIRMATION_NOTEBOOKLM_SCRIPT.md)
- [Single-source backend-owned conversation memory video script](core/05-backend-conversation-memory/notebooklm/AI_FABRIC_BACKEND_CONVERSATION_MEMORY_NOTEBOOKLM_SCRIPT.md)
- [Single-source tenant security and privacy video script](core/06-tenant-security-and-privacy/notebooklm/AI_FABRIC_TENANT_SECURITY_PRIVACY_NOTEBOOKLM_SCRIPT.md)
- [Single-source testing and shipping AI workflows video script](core/07-test-and-ship/notebooklm/AI_FABRIC_TESTING_SHIPPING_WORKFLOWS_NOTEBOOKLM_SCRIPT.md)
- [Real-application field lessons](../Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md)

The curriculum integrates these field lessons into intentional failures, troubleshooting exercises,
and deployed case-study postmortems. The shared troubleshooting playbook is defined in the master
course document and will be published at `/course/troubleshooting`.

Every published lesson provides two equivalent completion paths: a manual lab and a validated
assistant-assisted build using lesson-specific implementation and independent review prompts.
Both paths finish with the same reviewed, source-backed knowledge check. Choice questions are scored
deterministically; explanation and implementation-defense questions use explicit review criteria and
must never present LLM-generated grading as course evidence.

The Quickstart is action-first and intentionally has no required theory video. It introduces AI
Fabric in one short paragraph, proves one useful capability, and then hands the developer to the Core
track. CORE-01 provides the full framework introduction: why AI Fabric exists, when to use it,
module and ownership boundaries, and the main request flows. Later lessons may use a reviewed
NotebookLM architecture explainer when conceptual preparation adds value. NotebookLM receives one
self-contained production script per video; supporting manifests are maintainer review provenance,
not additional upload sources. Exact IDE, terminal, and live-application walkthroughs remain
separate maintainer-recorded artifacts.

The course is pinned to a framework release. Public website copies and generated learning assets
must identify the source tag and must not silently follow `main`.

## Publishing Contract

`course.yml` is the website catalog source. A rendered lesson must also provide lesson Markdown,
a deterministic knowledge check, and implementation and independent-review prompts. A lesson that
declares a theory video must also declare its NotebookLM source manifest; video metadata and the
manifest are optional only as a pair. The website sync validates these files, writes checksummed
generated artifacts, and fails when IDs, routes, versions, answer keys, or required publication
evidence disagree.

QS-01 and CORE-01 through CORE-07 are published against immutable checkpoints in the
[`ai-fabric-course-support-assistant`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant)
repository. QS-01 intentionally has no theory-video gate. Production, case-study, and
coding-assistant lessons remain planned and must not be described as executable public labs.
