# LLM Standard Chat Prompting Guide (Provider‑Native Multi‑Message)

**Document Purpose:** Define the framework’s single, production-standard way to talk to LLMs.

**Status:** Active (Greenfield Standard)  
**Applies to:** Intent extraction, generation, and any LLM call using `AIGenerationRequest`

---

## Why this exists

We intentionally **do not** build “one giant prompt string” that mixes:
- conversation history
- attachments / pinned targets
- current user query

That pattern:
- pollutes RAG/embedding queries,
- makes the model confuse “history” with “current user input”,
- breaks unpredictably under truncation.

Instead, the framework uses **provider‑native multi‑message prompting**.

---

## The Core Contract

### `AIGenerationRequest` (the only supported LLM input shape)

`AIGenerationRequest` is the canonical carrier for LLM calls:

- `systemPrompt` → rules/instructions (provider “system” equivalent)
- `messages` → prior turns only (history), typed as `List<AIChatMessage>`
- `prompt` → the *current user message* (the final user turn)

### `AIChatMessage`

Each history message is typed:
- `role`: `USER` | `ASSISTANT` | `SYSTEM`
- `content`: message text

**Rule:** `SYSTEM` history messages are not supported; use `systemPrompt` instead.

---

## How Chat Requests Are Built (Pipeline)

### 1) Conversation history → typed messages

`ConversationEnrichmentStep` loads persisted chat turns and converts them into typed messages:
- source: `ChatSessionService.getConversationMessages(...)`
- strategy: `MemoryStrategy.toMessages(turns)`
- output: `PipelineContext.historyMessages`

Truncation is safe:
- we drop **oldest whole messages** to respect window limits,
- we never cut marker-based text blobs.

### 2) Attachments / pinned targets → bounded user context

`AttachmentPromptAugmentationStep` renders a bounded “pinned targets” block and stores it as:
- `PipelineContext.pinnedTargetsContext`

This block is treated as **user-provided context** (not system authority).

### 3) Intent extraction uses `IntentExtractionInput`

`IntentExtractionStep` builds:
- `userQuery` → the real query used for retrieval and validation (usually `PipelineContext.getEffectiveQuery()`)
- `currentUserMessage` → `pinnedTargetsContext + userQuery`
- `historyMessages` → `PipelineContext.historyMessages`

This is passed to the extractor as `IntentExtractionInput`.

---

## RAG / Embeddings Rule (Critical)

**Never** send conversation scaffolding or attachment blocks into embedding text.

RAG retrieval queries must be derived from:
- `PipelineContext.getEffectiveQuery()` (processed/redacted when available; otherwise original)
- plus optional `intentMetadata.retrievalQueryHint` (per ADRs / orchestration policy)

Hard requirements:
- No `Conversation History:`
- No `---BEGIN HISTORY---`
- No `User:` / `Assistant:`
- No attachment/pinned-target dumps

---

## Provider Support (Required)

We only support providers that can represent:
- a system instruction (or equivalent), and
- a list of prior turns, and
- the final user message

### Implemented provider mappings

- **OpenAI / Azure OpenAI**
  - `systemPrompt` → system message
  - `messages[]` → appended as chat history
  - `prompt` → final user message

- **Anthropic**
  - `systemPrompt` → `system`
  - `messages[]` → `messages`
  - `prompt` → final user message

- **Cohere**
  - `systemPrompt` → `preamble`
  - `messages[]` → `chat_history`
  - `prompt` → `message`

- **Gemini**
  - `systemPrompt` → `systemInstruction`
  - `messages[]` → `contents[]` (role `user`/`model`)
  - `prompt` → final `contents[]` user entry

### Greenfield enforcement

If a provider cannot implement this cleanly, we do not “fallback” to legacy string concatenation.

---

## Observability (Debug)

The pipeline exposes prompting metadata under `metadata.llmPrompting`, including:
- `standard = MULTI_MESSAGE`
- `historyMessagesCount`
- `currentUserMessageChars`
- `pinnedTargetsContextChars`

This is designed to prove:
1) history is passed as history,
2) the current user message is separate,
3) we are not polluting retrieval queries.

---

## How to test

Run the full framework verification:

`mvn -f ai-infrastructure-module/pom.xml verify`

Provider modules are exercised through unit + integration suites. RealAPI suites require keys (see `../testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`).

