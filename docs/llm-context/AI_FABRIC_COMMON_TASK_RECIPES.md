# Common Task Recipes

## Add Search To An Entity

1. Add `@AICapable(entityType = "...")` to the entity.
2. Add `@AISearchable` to fields that should be embedded.
3. Add `@AIContext` to fields useful for answer context but not primary search ranking.
4. Add entity config in `ai-entity-config.yml`.
5. Index seed data or enable migration/backfill.
6. Test query with different wording.

## Add RAG To An Existing Search App

1. Add `ai-fabric-rag`.
2. Enable generation, embeddings, search, and RAG.
3. Ensure vector data exists before running chat.
4. Show evidence documents and scores in diagnostics or UI.
5. Test no-evidence behavior.

## Add A Side-Effect Action

1. Create an `@AIAction` class.
2. Set `accessMode = ActionAccessMode.WRITE`.
3. Set `requiresConfirmation = true`.
4. Use server-side current account context.
5. Return a concise domain result.
6. Test missing params, confirmation, reject, and access denied.

## Add A Read Action

1. Create an `@AIAction` with `ActionAccessMode.READ`.
2. Set `readActionResolutionEligible = true` only after review.
3. Return user-friendly structured fields.
4. Use the read result as evidence for a later LLM response.

## Add Chat Memory

1. Add `ai-fabric-chat-session`.
2. Enable `ai.chat.enabled`.
3. Send stable `conversationId` and owner/current user id.
4. Stop sending UI-built chat history prompts.
5. Test "yes", "add it", and "compare those" follow-ups.

## Add A Public Demo Reset

1. Create a per-session demo user.
2. Seed only that user's data.
3. Show full-page loading while reset is happening.
4. Clean up old demo users with TTL.
5. Verify reset does not mutate shared seed users.

