# Coding Assistant Prompt: Implement PROD-02 Modes And Positions

Work in `Loom-AI-Labs/ai-fabric-course-support-assistant` from tag
`course-0.4.0-p01-provider-routing`.

Before editing, verify that the tag exists. If it does not, stop and report that the 0.4 learner
checkpoint is not published; never substitute `main` or an older 0.3 tag.

Implement the PROD-02 lesson contract without changing AI Fabric framework code:

1. Add `support_assistant` retrieval-only and `support_resolver` governed-action modes.
2. Keep `support_resolver` as the no-hint default to preserve Core lesson behavior.
3. Add application-owned mapping from `knowledge` and `ticket` positions.
4. Preserve explicit mode input for strict Core validation; do not silently replace unknown modes.
5. Add optional validated `mode` and `position` request fields. Do not accept identity, tenant,
   pending action, history, or authorization context from the browser.
6. Add deterministic tests for default, mapped positions, explicit mode precedence, unknown mode,
   unknown position, retrieval-enabled metadata, and action gating.
7. Update readiness, packaged smoke, requests, and README checkpoint inventory.

Run targeted compatibility tests first, then `clean verify` and the packaged smoke. Report exact
tests and any behavior changes. Do not use message keyword matching or prompt text as policy.
