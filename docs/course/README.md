# AI Fabric Course Materials

This directory is the canonical source for the public AI Fabric course. It defines the curriculum,
learner application, website publishing contract, NotebookLM source packs, and release gates.

Start with:

- [Machine-readable course catalog](course.yml)
- [Build Production-Oriented AI Workflows with Java and Spring Boot](AI_FABRIC_EXTERNAL_USER_COURSE.md)
- [QS-01 preview lesson package](quickstart/01-first-useful-result/lesson.md)
- [Real-application field lessons](../Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md)

The curriculum integrates these field lessons into intentional failures, troubleshooting exercises,
and deployed case-study postmortems. The shared troubleshooting playbook is defined in the master
course document and will be published at `/course/troubleshooting`.

Every published lesson is planned with two equivalent completion paths: a manual lab and a validated
assistant-assisted build using lesson-specific implementation and independent review prompts.
Both paths finish with the same reviewed, source-backed knowledge check. Choice questions are scored
deterministically; explanation and implementation-defense questions use explicit review criteria and
must never present LLM-generated grading as course evidence.

Before either implementation path, every lesson uses a short, reviewed NotebookLM architecture
explainer. Its lesson-specific theory brief covers the request/data flow, ownership boundaries, and
important failure behavior. Exact IDE, terminal, and live-application walkthroughs remain separate
maintainer-recorded artifacts.

The course is pinned to a framework release. Public website copies and generated learning assets
must identify the source tag and must not silently follow `main`.

## Publishing Contract

`course.yml` is the website catalog source. A rendered lesson must also provide lesson Markdown,
a deterministic knowledge check, implementation and independent-review prompts, and a NotebookLM
source manifest. The website sync validates these files, writes checksummed generated artifacts,
and fails when IDs, routes, versions, answer keys, or required publication evidence disagree.

The QS-01 package is intentionally marked `preview`: its conceptual content and questions are ready,
while the learner repository checkpoints and approved theory video are not. Preview material must
not be described as a completed or executable public lab.
