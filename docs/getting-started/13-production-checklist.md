# Production Checklist

Use this before releasing an app that depends on AI Fabric.

## Dependencies

- Import `ai-fabric-bom` with the intended release version.
- Use only needed modules.
- Pin Java and Spring Boot versions in CI.
- Keep smoke profile separate from production profile.

## Providers

- Configure LLM and embedding providers from environment variables.
- Do not commit API keys.
- Verify real provider health with a small request.
- Confirm embedding dimensions match vector index dimensions.
- Decide whether ONNX, OpenAI embeddings, or another provider is production default.

## Vector Store

- Choose durable storage for production.
- Verify restart behavior.
- Verify metadata filtering semantics for tenant/user boundaries.
- Use separate indexes/collections for incompatible embedding dimensions.
- Keep vector admin endpoints protected.

## RAG

- Seed or migrate required data before claiming the app is RAG-backed.
- Show retrieved evidence in diagnostics or UI.
- Fail honestly when evidence is missing.
- Keep app policies readable to users and maintainers.

## Actions

- Side effects require confirmation.
- Action handlers use current server-side account context.
- Missing required parameters return clarification.
- Action results are concise and domain-shaped.
- Reject and expired confirmation paths are tested.

## Chat Session

- Stable conversation IDs are scoped per user/session.
- Backend owns history; frontend sends the new turn.
- Cleanup old demo/public sessions.
- Verify cross-user access is blocked.

## Security

- Enforce auth before retrieval and action execution.
- Derive tenant/account IDs server-side.
- Sanitize or redact PII where required.
- Do not expose private handoff files or credentials.

## CI And Release

- Framework unit tests pass.
- Affected real apps compile and run smoke tests.
- Real provider smoke is documented and run when keys are available.
- Release notes include migration notes for dependencies, config, and behavior changes.
- Public docs and real app READMEs match deployed behavior.

