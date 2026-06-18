# Chat Session Integration Tests — Coverage Plan

## Goals
- Validate **chat-session behavior end-to-end inside orchestration**, not only persistence wiring.
- Cover **critical + edge scenarios** around conversation enrichment/recording, access control, PII handling, and intent/action flows.
- Keep tests **production-grade**:
  - deterministic integration tests for fast, stable verification
  - RealAPI tests for provider realism (minimal assertions to avoid flakiness)

## Non-goals (for this module)
- UI/transport concerns (REST controllers, WebSocket, etc.)
- Application-specific domain actions (orders, products, etc.) beyond framework-safe test handlers
- Performance benchmarking (separate suite)

---

## Test Layers (what we will add)

### A) Fast Integration Tests (no external LLM)
Purpose: validate chat logic + orchestration contracts without network/keys.

Approach:
- Use a **test-only action handler** (safe/no side effects) to verify ACTION execution paths.
- Use **deterministic test doubles** for intent extraction and/or generation where needed.
  - Acceptable here because the objective is the chat+pipeline contract, not provider quality.

Output expectations:
- assert `OrchestrationResultType`, `success`, `errorCode`, `metadata`, and conversation history persistence
- avoid asserting exact natural language response text

### B) RealAPI Integration Tests (provider-backed)
Purpose: validate real provider behavior with conversation history + orchestration.

Approach:
- Keep assertions **structural** (type, non-empty message, history recorded, no crashes).
- Use controlled prompts that are likely stable across providers.
- Avoid destructive actions; use **framework-safe test action handler** shipped with this module only.

---

## Coverage Matrix (Critical + Edge)

### 1) Conversation lifecycle (critical)
- Create session automatically on first use (`auto-create-sessions=true`)
- Record turns + preserve ordering (timestamp ASC)
- Load context (history formatting, includes both user/assistant)
- Delete conversation (and verify it is gone)

Proposed tests:
- ✅ `ChatSessionWiringIntegrationTest` (turn persistence + context formatting)
- ✅ `ChatSessionDeletionIntegrationTest`
- ✅ `ChatSessionLifecycleIntegrationTest` (auto-create + multi-turn)

### 2) Conversation enrichment correctness (critical)
- When `conversationId` is present, `ConversationEnrichmentStep` must:
  - load history for the correct owner (`context.getIdentifier()`)
  - enrich via `processedQuery` (not `originalQuery`)
  - apply `window-size` and `max-context-chars` constraints

Proposed tests:
- ✅ `ConversationEnrichmentStepIntegrationTest` (window-size, truncation, no-op)

### 3) Conversation recording correctness (critical)
- `ConversationRecordingStep` must:
  - record **sanitized assistant message** (prefer sanitized payload message)
  - redact PII from user query before persisting (when PII module enabled)
  - skip recording if pipeline terminates early (fail-fast), except for `CLARIFICATION_REQUIRED` which should be recorded to preserve chat continuity

Proposed tests:
- ✅ `ConversationRecordingStepIntegrationTest` (sanitized message, early termination skip, PII redaction)

### 4) Security + access control (critical / fail-closed)
- Deny access to a conversation:
  - enrichment must terminate with `ERROR` / `ACCESS_DENIED`
  - no turn should be recorded
- Owner mismatch must be fail-closed (no partial access)

Proposed tests:
- ✅ `ConversationAccessDeniedTerminatesPipelineIntegrationTest`
- ✅ `ConversationOwnerMismatchDeniedIntegrationTest`

### 5) Intent types in a chat context (critical behavior)
Ensure the user still receives an answer and history is recorded for:
- `INFORMATION_PROVIDED` (simple “How are you?”)
- `OUT_OF_SCOPE` (nonsensical/unsafe request)
- `CLARIFICATION_REQUIRED` (missing vectorSpace for retrieval intent)

Proposed tests:
- ✅ `ChatOutOfScopeTurnRecordedIntegrationTest` (pipeline-level, no LLM)
- ✅ `ChatInformationTurnRecordedIntegrationTest`
- ✅ `ChatClarificationRequiredIntegrationTest`

### 6) Actions inside chat (critical)
Scenarios:
- Action requested in mid-chat should execute (authenticated user)
- Anonymous user should be denied for ACTION (`ACTION_DENIED`)
- Compound intents: ACTION + INFORMATION in one request should not crash and should return child results
- Multiple ACTION intents should not crash; compound normalization should preserve children

Proposed tests:
- ✅ `ChatActionExecutedIntegrationTest` (test-only safe action handler)
- ✅ `ChatAnonymousActionDeniedIntegrationTest`
- ✅ `ChatCompoundActionPlusInfoIntegrationTest`
- ✅ `ChatMultiActionCompoundIntegrationTest`

### 7) Multi-turn disambiguation / “follow-up” semantics (edge but important)
Scenarios:
- Turn 1 establishes context; Turn 2 uses pronouns (“do it”, “that one”)
- Turn 2 depends on prior clarification answer

Proposed tests:
- ✅ `ChatFollowUpReferenceIntegrationTest`
- ✅ `ChatClarificationThenExecuteIntegrationTest`

### 8) Failure modes and robustness (edge / framework hardening)
Scenarios:
- Intent extraction returns malformed JSON → repair path or safe fallback
- Missing action handler → `ERROR` with `ACTION_NOT_FOUND`
- Provider exception → pipeline returns `ERROR` and does not corrupt conversation history

Proposed tests:
- `ChatIntentExtractionMalformedJsonFallbackIntegrationTest`
- `ChatMissingActionHandlerIntegrationTest`
- `ChatProviderFailureDoesNotCorruptHistoryIntegrationTest`

---

## RealAPI Scenario Pack (what we will add)

These should run under `-P realapi` and be included in the Manual Action matrix.

### RealAPI “Core” (low flake, low cost)
- Greeting/info-only turn records history
- Clarification-required flow returns `CLARIFICATION_REQUIRED`

Proposed tests:
- ✅ `realapi/ChatSessionConversationRealApiIntegrationTest` (records turns + logs responses for conversational queries)
- ✅ `realapi/ChatSessionClarificationFlowRealApiIntegrationTest` (clarification-style prompt + follow-up; asserts no ERROR and turns recorded)
- ✅ `realapi/ChatSessionAnonymousSessionRealApiIntegrationTest` (anonymous sessionId owner flow)

### RealAPI “Actions” (safe actions only)
- Execute a **safe test action handler** (no external side effects)
- Verify action result reaches user and is recorded

Proposed tests:
- ✅ `realapi/ChatSessionSafeActionConfirmationRealApiIntegrationTest` (explicit safe action invocation; asserts confirmation message + structured action result)
- ✅ `realapi/ChatSessionAnonymousSessionRealApiIntegrationTest` (kept as a realism smoke test; not relied on for action selection)
- ✅ `realapi/ChatSessionActionPlusInfoCompoundRealApiIntegrationTest` (action + follow-up explanation; asserts no ERROR and action present even when compound-normalized)

### RealAPI “PII & Sanitization”
- Send a query with PII; verify:
  - PII is not echoed back unsafely
  - conversation stored user text is redacted (when PII module is enabled)

Proposed tests:
- ✅ `realapi/ChatSessionPiiRedactionRealApiIntegrationTest`

### RealAPI “Persistence & Ownership”
- Conversation context windowing respects `ai.chat.window-size`
- Owner mismatch is fail-closed
- Deleting a conversation removes it

Proposed tests:
- ✅ `realapi/ChatSessionContextWindowingRealApiIntegrationTest`
- ✅ `realapi/ChatSessionOwnerMismatchRealApiIntegrationTest`
- ✅ `realapi/ChatSessionDeletionRealApiIntegrationTest`

---

## Implementation Notes (to avoid flaky tests)
- Prefer assertions on:
  - `OrchestrationResultType`, `success`, `errorCode`, `metadata` keys, presence of children
  - `ChatSession.turns.size()` growth across turns
  - conversation context contains `"User:"` and `"Assistant:"`
- Avoid asserting exact assistant phrasing in RealAPI tests.
- Add **test-only** action handlers under this module (never use destructive framework actions like `clear_vector_index` in RealAPI).
