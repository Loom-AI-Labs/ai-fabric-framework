# NotebookLM Production Script

## Provider Architecture And Purpose-Specific Models In AI Fabric

### Production instructions

Create a focused technical explanation for Java and Spring Boot developers. The target duration is
about ten minutes. AI Fabric must remain the subject throughout the recording.

Use this file as the only NotebookLM source. Do not add general provider documentation or unrelated
AI sources. Do not introduce APIs, properties, providers, or behavior not stated here.

The recording should:

- use clean Spring Boot architecture diagrams and short configuration excerpts;
- distinguish application-owned decisions from framework and provider responsibilities;
- pronounce `LlmPurpose` as “L-L-M purpose”;
- show `ORCHESTRATION`, `GENERATION`, `EMBEDDINGS`, and `DEFAULT` exactly as code identifiers;
- label recording providers as deterministic test fixtures, never live AI;
- label OpenAI as an optional live-provider exercise;
- avoid provider marketing, benchmark claims, and promises about cost or quality;
- never display an API key, complete user prompt, or sensitive provider response.

### Learning promise

After this explanation, a developer should understand why an AI Fabric application may use one
model for orchestration, another for answer generation, ONNX for embeddings, and Lucene or another
provider for vector storage. The developer should also understand how AI Fabric routes a call,
where Spring AI fits, what diagnostics may safely expose, and why a failed live provider must remain
visible.

## Opening: One AI Feature, Several Different Jobs

Start with the continuing Support Knowledge Assistant.

A customer writes:

> I am locked out after too many sign-in attempts. What should I do?

It is tempting to describe the whole request as one LLM call. That description hides the real
architecture.

The application may need to:

1. classify the request and decide whether it is informational or actionable;
2. embed the question so semantic evidence can be retrieved;
3. search approved support articles under tenant and visibility filters;
4. generate an answer constrained by the retrieved evidence;
5. preserve conversation and diagnostic state.

These jobs have different contracts. They can have different latency, consistency, cost, privacy,
and failure requirements. AI Fabric models those differences instead of forcing one provider choice
onto every operation.

On screen, show:

```text
Support request
  -> orchestration decision
  -> query embedding
  -> vector retrieval
  -> grounded answer generation
  -> governed application response
```

Say clearly:

> Provider architecture is not only a list of vendor names. It is a map from a specific application
> purpose to a configured implementation, model, endpoint, and failure policy.

## The Four Provider Concerns

### Orchestration LLM

The orchestration model supports control-oriented language work such as:

- intent extraction;
- request classification;
- action selection;
- typed parameter extraction;
- confirmation interpretation;
- planning where the selected mode permits it.

Orchestration output is often structured and consumed by backend code. Lower temperature and a
bounded token budget are usually sensible defaults, but the application still validates the
result. The model does not authorize the action, choose tenant identity, or bypass confirmation.

### Generation LLM

The generation model produces user-facing content such as:

- an evidence-grounded RAG answer;
- a summary;
- an explanation of an application-owned state;
- a narrative action result where the application permits generation.

Generation may need a different model or sampling configuration from orchestration. Its response is
still bounded by retrieved evidence, typed output, public projection, and application policy.

### Embedding provider

The embedding provider converts text into vectors for semantic similarity. It does not write a
natural-language support answer.

In the course application, ONNX runs locally for embeddings. A cloud LLM key is not required for
the mandatory embedding and retrieval path.

### Vector provider

The vector provider stores and searches derived semantic evidence. The course begins with Lucene.
AI Fabric also has provider modules for other vector engines, but selecting a vector store is a
separate decision from selecting an LLM or embedding provider.

The application database remains the source of truth for support articles. Vectors are derived and
rebuildable evidence, not the business record.

## `LlmPurpose`: The Routing Contract

Show the current enum:

```java
public enum LlmPurpose {
    ORCHESTRATION,
    GENERATION,
    EMBEDDINGS,
    DEFAULT
}
```

Explain each value:

- `ORCHESTRATION` resolves the orchestration LLM configuration.
- `GENERATION` resolves the generation LLM configuration.
- `DEFAULT` resolves the global LLM defaults for a call without a purpose override.
- `EMBEDDINGS` exists in the shared purpose vocabulary, while embedding requests use the embedding
  provider contract and embedding-specific configuration.

The important application call shape is:

```java
aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
```

or:

```java
aiCoreService.generateContent(request, LlmPurpose.GENERATION);
```

The purpose is supplied by trusted backend code according to the operation being performed. The
browser does not send a provider name or arbitrary purpose that AI Fabric trusts.

## Configuration Precedence

Show this simplified configuration:

```yaml
ai:
  providers:
    llm-provider: openai
    embedding-provider: onnx
    enable-fallback: false
    orchestration:
      llm-provider: openai
      model: gpt-4o-mini
      temperature: 0.1
    generation:
      llm-provider: openai
      model: gpt-4o-mini
      temperature: 0.3
```

Explain the resolution order precisely:

1. AI Fabric receives the trusted `LlmPurpose` at its Java API.
2. For `ORCHESTRATION`, it resolves `ai.providers.orchestration`.
3. For `GENERATION`, it resolves `ai.providers.generation`.
4. A missing provider or model inside that purpose uses the corresponding global provider defaults.
5. A request field explicitly supplied by trusted application code can override a resolved request
   default.
6. Connection overrides such as endpoint profile or base URL can be applied for that purpose when
   configured.

Do not claim that a purpose block creates a provider client by itself. The matching provider module,
configuration, and available runtime bean must exist.

## Request Flow Through AI Fabric And Spring AI

Describe this component diagram:

```text
Application service
  | AIGenerationRequest + LlmPurpose
  v
AICoreService
  | resolves purpose provider/model/defaults
  v
AIProviderManager
  | selects the named available AIProvider
  v
Spring AI provider adapter
  | resolves/caches the model client for effective connection settings
  v
Spring AI ChatModel
  |
  v
Hosted or compatible model endpoint
```

`AICoreService` applies the purpose-specific provider, model, maximum tokens, temperature, timeout,
and supported connection overrides.

`AIProviderManager` selects the configured provider and enforces the configured fallback posture.

For supported LLM providers, AI Fabric's Spring AI adapter uses Spring AI model clients underneath
the AI Fabric application contract. `SpringAiModelResolver` can resolve and cache model clients for
effective connection settings. This allows AI Fabric to retain application-level concepts such as
purpose, governance, transient-input policy, diagnostics, and orchestration while avoiding duplicate
provider client implementations for ordinary LLM calls.

The application should call AI Fabric, not call a provider SDK and then attempt to reconstruct AI
Fabric policy around the result.

## Why Purpose-Specific Models Matter

Use a comparison table:

| Concern | Orchestration | Generation |
| --- | --- | --- |
| Primary output | structured decision | user-facing content |
| Typical priority | consistency and schema adherence | grounded usefulness and presentation |
| Typical temperature | lower | application-dependent |
| Typical token budget | bounded decision payload | enough for approved answer contract |
| Failure effect | no trusted intent/action decision | no generated answer |

The table is a design aid, not a promise that one model is universally best. Teams must test their
selected models against their own structured contracts, evidence boundaries, latency budgets, and
release gates.

One provider may serve both purposes with different models or settings. Two providers may serve the
two purposes. The same architecture supports either choice.

## Keyless Proof Versus Live-Provider Proof

Explain the course's two evidence classes.

### Required deterministic proof

The test profile registers two unmistakably test-only providers:

```text
course-orchestration-test -> course-test-orchestration
course-generation-test    -> course-test-generation
```

A recording fixture captures which provider and model received each request. The test calls the real
`AICoreService` with each `LlmPurpose` and asserts the captured route.

This proves:

- configuration binding;
- purpose default resolution;
- provider-manager selection;
- request model propagation;
- no-fallback behavior when a selected provider fails.

It does not prove:

- an OpenAI key is valid;
- the public network is available;
- a hosted model currently accepts the request;
- generated wording meets application quality expectations.

### Optional live OpenAI proof

The OpenAI profile receives its key only from a private runtime environment:

```bash
OPENAI_ENABLED=true
OPENAI_API_KEY=<private secret>
AI_ORCHESTRATION_MODEL=gpt-4o-mini
AI_GENERATION_MODEL=gpt-4o-mini
```

That exercise proves the selected credential, endpoint, model, network, current provider response,
and adapter compatibility for the requests actually executed.

A skipped live check is `SKIPPED`, not `PASS`.

## Diagnostics Without Secret Leakage

The course health endpoint reports:

```json
{
  "provider": {
    "mode": "live-openai",
    "generationEnabled": true,
    "orchestration": "openai",
    "orchestrationModel": "gpt-4o-mini",
    "generation": "openai",
    "generationModel": "gpt-4o-mini",
    "embedding": "onnx",
    "vector": "lucene",
    "fallbackEnabled": false
  }
}
```

These values help an operator understand what the running artifact intends to use.

Health must not expose:

- API keys or token fragments;
- user bearer tokens;
- raw prompts or chat history;
- action parameters containing sensitive data;
- provider response content;
- transient file URLs.

When the local profile disables generation, health reports orchestration and generation as
`disabled`. Reporting the OpenAI default there would imply a runtime capability that is not active.

## Failure Path: Invalid Credential With No Hidden Success

Walk through the failure:

```text
openai profile selected
  -> OPENAI_API_KEY is invalid
  -> GENERATION request reaches selected OpenAI provider
  -> provider returns authentication failure
  -> fallback is disabled
  -> application returns explicit generation/provider failure
  -> no canned answer and no write action
```

The failure may also be detected during startup if configuration validation rejects the missing or
invalid setup before requests are accepted.

Both outcomes are honest. The unacceptable outcome is:

```text
OpenAI fails
  -> an unrelated local/test provider returns a useful answer
  -> response is shown as live AI success
```

That hides the broken release path and falsifies the evidence.

## Incorrect Architecture To Avoid

Show this design with a red rejection marker:

```text
Browser request includes provider=openai and model=anything
  -> controller trusts those values
  -> application calls provider SDK directly
  -> on failure, hardcoded text is returned
  -> UI labels the response successful AI
```

Explain why it fails:

1. an untrusted client controls cost and endpoint selection;
2. application policy and provider integration are split across ad hoc code;
3. purpose-specific defaults and diagnostics are bypassed;
4. fallback hides operational failure;
5. tests cannot state which contract was actually exercised.

Replace it with:

```text
trusted application operation
  -> fixed LlmPurpose
  -> externalized allowlisted configuration
  -> AI Fabric provider manager
  -> Spring AI-backed provider adapter
  -> explicit result or explicit failure
```

## Application Ownership Boundaries

Reinforce these boundaries:

- The application owns authenticated identity, tenant context, business authorization, and the
  decision that an operation is orchestration or generation.
- AI Fabric owns purpose-aware provider resolution, orchestration contracts, governance hooks, and
  provider-neutral application APIs.
- Spring AI supplies supported model client integration beneath the AI Fabric adapter.
- The provider supplies model inference and usage information available from that call.
- ONNX supplies local embeddings in this course.
- Lucene supplies local vector lifecycle and search in this course.
- The application database remains the source of truth for support records.

No prompt can replace these ownership boundaries.

## Final Recap

End with five statements:

1. An AI workflow contains different provider jobs, not one universal model call.
2. `LlmPurpose` lets trusted backend code route orchestration and generation independently.
3. Embeddings and vector storage remain separate from both LLM purposes.
4. Deterministic recording providers prove routing; only an executed keyed check proves a live
   hosted-provider path.
5. Provider failure must stay visible, and diagnostics must explain posture without exposing secrets
   or user content.

Bridge directly into the lab:

> Next, configure the Support Knowledge Assistant with separate purpose routes, prove them with
> recording providers, expose safe runtime posture, and run the optional OpenAI path only after the
> keyless release gate is green.

