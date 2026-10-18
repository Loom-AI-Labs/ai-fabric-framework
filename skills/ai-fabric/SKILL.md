---
name: ai-fabric
description: Build or change Java and Spring Boot applications that use the public AI Fabric Framework for indexing, retrieval, governed actions, conversations, providers, or bounded execution. Use when selecting AI Fabric modules, applying its public contracts, adapting a released example, or verifying an external consumer; do not use for LoomAI Platform operations or private hosted-runtime administration.
---

# AI Fabric Application Development

Use released AI Fabric artifacts and public contracts to add AI capabilities while the application remains the authority for identity, policy, persistence, and business effects.

## Start With Evidence

1. Read the target application's `pom.xml`, runtime configuration, tests, and local development guide.
2. Identify its Java, Spring Boot, AI Fabric, provider, vector-store, database, and security choices.
3. Confirm the latest supported immutable AI Fabric release from Maven Central or the repository release notes. This package is validated for `0.8.36`; do not silently downgrade or consume a mutable source branch.
4. Find the closest maintained real app before designing a new integration shape.
5. State whether the task is indexing/retrieval, actions, conversations/execution, provider wiring, or a combination, then read only the matching reference below.

## Route The Task

- For module and provider selection, read [modules and providers](references/modules-and-providers.md).
- For entity sync, document ingestion, vector search, or grounded generation, read [indexing and retrieval](references/indexing-and-retrieval.md).
- For read/write actions, confirmation, connectors, or trust boundaries, read [actions and trust](references/actions-and-trust.md).
- For chat sessions, conversation control, handover, specialists, or declarative execution, read [conversation and execution](references/conversation-and-execution.md).
- Before declaring completion, read [verification](references/verification.md).

## Implementation Rules

- Use `@EnableAIInfrastructure` once in the Spring Boot application.
- Prefer the smallest released module set that provides the required contract. Import versions through `io.github.loom-ai-labs:ai-fabric-bom` when practical.
- Keep domain repositories and writes application-owned. AI Fabric may coordinate only capabilities the application explicitly exposes and authorizes.
- Treat identity, tenant/deployment scope, allowed actions, allowed vector spaces, confirmation, and resource provenance as trusted structured inputs. Fail closed when required authority is absent.
- Let validated structured model output, trusted structured application input, or a configured retrieval/classification provider make semantic decisions. Do not add keyword, substring, regex, token-overlap, or phrase-order routing that changes intent, source selection, action choice, parameters, or evidence.
- Do not hide an invalid model decision with a silent fallback. Improve schema/catalog/prompt guidance, use an explicitly configured bounded repair policy, or return a typed clarification/error.
- Read actions may fetch current structured facts and do not require a vector-space mapping. Retrieval allowlists govern retrieval; action allowlists and authorization govern actions.
- Never invent a public endpoint, annotation, configuration key, or lifecycle contract. If the required contract is absent, identify the gap with source evidence instead of copying framework internals into the app.
- Keep secrets external. Do not place provider keys, connection strings, private URLs, or customer data in source, examples, logs, or generated guidance.

## Completion Contract

Return a concise account of modules chosen, application-owned boundaries, files changed, focused tests, packaged/empty-cache consumer evidence, real-provider evidence when available, and any unsupported contract. A compile alone does not prove retrieval quality, action safety, tenant isolation, or conversation continuity.

## Install This Skill

Copy the complete `skills/ai-fabric` directory into the skill directory supported by the coding assistant. For Codex, use `${CODEX_HOME:-$HOME/.codex}/skills/ai-fabric`; for a project-scoped Cursor installation, use `.cursor/skills/ai-fabric`. Keep this directory as the canonical source and copy it as a unit so relative references continue to resolve.
