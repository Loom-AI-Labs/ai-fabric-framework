# Starter Issue Backlog

These issues are ready to create in GitHub once repository issue-write access is available to the release operator or automation. They are intentionally small, externally useful, and safe for first-time contributors.

## Add a minimal RAG quickstart app using Maven Central artifacts only

Labels: `good first issue`, `documentation`, `help wanted`

Create a tiny runnable example that uses the published AI Fabric Maven Central artifacts without depending on local reactor modules.

Acceptance criteria:
- Use AI Fabric `0.4.0` from Maven Central.
- Show one indexed document and one semantic/RAG query.
- Include exact commands for a clean local run.
- Document required Java, Spring Boot, and provider configuration.

## Document provider configuration for OpenAI, ONNX, and Spring AI

Labels: `documentation`, `help wanted`

Create a concise provider setup guide that explains which properties are required for OpenAI, local ONNX embeddings, and Spring AI-backed providers.

Acceptance criteria:
- Include a small configuration example for each provider family.
- Explain which tests require live API keys.
- Call out safe local/offline options.

## Add a smoke test for Privacy Shield safe-search behavior

Labels: `good first issue`, `test coverage`

Add a focused smoke test that proves sensitive input is redacted before retrieval/search output is exposed.

Acceptance criteria:
- Cover at least email, phone, and payment-like values.
- Assert safe output does not expose the original sensitive value.
- Keep the test deterministic and runnable without live LLM keys.

## Improve real-app README screenshots and request-flow diagrams

Labels: `documentation`, `help wanted`

Each deployed demo should have a short diagram showing browser, demo backend, AI Fabric modules, provider, and storage flow.

Acceptance criteria:
- Cover the five deployed demos.
- Keep diagrams text-based or Mermaid so they remain reviewable.
- Link each README to its live demo and Dockerfile.

## Add troubleshooting notes for action confirmation endpoint mismatches

Labels: `documentation`, `good first issue`

Document how action confirmation routes work in AI Fabric demos and how to diagnose `404`/mismatched confirm endpoint problems.

Acceptance criteria:
- Include a failing request example and corrected route shape.
- Explain the difference between app orchestration endpoints and AI Fabric action confirmation endpoints.
- Reference at least one real app using governed actions.

## Add focused tests for chat-session follow-up handling

Labels: `test coverage`, `help wanted`

Add tests that prove short follow-up utterances can use AI Fabric chat session context instead of requiring the UI to resend full history.

Acceptance criteria:
- Cover a previous assistant answer followed by a short user reply.
- Confirm the UI only needs to send the latest user message when chat sessions are wired.
- Avoid brittle phrase matching in the production framework path.

## Document vector provider lifecycle capability differences

Labels: `documentation`, `help wanted`

Create a capability matrix for AI Fabric vector providers, including metadata filtering, lifecycle/admin operations, counts, deletes, scans, and diagnostics.

Acceptance criteria:
- Distinguish similarity search from full lifecycle/admin compatibility.
- Note where provider-native SDK capability is used.
- Call out caveats without framing them as hidden failures.

## Add an example for tenant-scoped metadata filtering with Lucene

Labels: `good first issue`, `examples`, `security`

Add a small example or test showing tenant-safe retrieval with metadata filters.

Acceptance criteria:
- Use tenant-scoped test data.
- Prove cross-tenant documents are not returned.
- Include a short note explaining where app authorization ends and AI Fabric retrieval filtering begins.
