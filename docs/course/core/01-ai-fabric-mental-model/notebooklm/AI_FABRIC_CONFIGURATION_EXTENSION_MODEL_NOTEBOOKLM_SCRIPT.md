# NotebookLM Single-Source Production Script: AI Fabric Configuration And Extension Model

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general Spring Boot,
Spring AI, provider, prompt-engineering, or framework-extension knowledge. Do not request or rely on
another source.

Create a structured technical explainer titled **AI Fabric Configuration And Extension Model: From
Dependency To Application Policy**. Follow the fourteen scenes in order. Use every **Visual** block
as production direction and every **Narration** block as the spoken message. Natural transitions are
allowed, but do not omit, replace, or contradict the technical content.

Keep the current AI Fabric configuration and extension model as the subject. This is an architecture
explainer, not a property-reference reading and not a code-along. Do not invent configuration keys,
annotations, precedence rules, auto-configuration conditions, beans, providers, prompt paths,
fallback guarantees, or extension interfaces. Apply the accuracy guardrails at the end of this file
to the complete output.

## Production Direction

- Title: **AI Fabric Configuration And Extension Model: From Dependency To Application Policy**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who have already watched the AI Fabric architecture and
  request-lifecycle explainers.
- Voice: direct, calm, technically precise, and practical. Address the developer as **you**.
- Learning objective: by the end, you can explain how a dependency becomes a conditional runtime
  capability, apply each scoped precedence rule, select providers and prompt overlays deliberately,
  and choose the correct application extension point without replacing the framework.
- Example application: a Spring Boot Support Knowledge Assistant with searchable support articles,
  a governed `create_support_ticket` action, backend chat memory, and the support curated pack.
- Visual style: use layered configuration diagrams, small YAML and annotation excerpts, a provider
  routing diagram, a prompt-overlay stack, and an extension-point map. Keep labels readable and
  avoid decorative AI imagery.

## Scene 1: Configuration Is A Layered Runtime Model

**Visual:** Show one Support Knowledge Assistant surrounded by five questions.

```text
Which capability modules exist?
Which beans activate?
Which application data becomes evidence?
Which providers and prompts are selected?
Which policies and domain actions remain application-owned?
```

Then replace a single giant configuration box with five connected layers.

**Narration:**

AI Fabric is not configured by one master YAML file and it is not extended by copying its
orchestrator.

Its runtime shape emerges from five cooperating layers: Maven dependencies, Spring Boot
auto-configuration, typed `ai.*` properties, entity YAML and annotations, and application-owned
beans or handlers.

Each layer answers a different question. Dependencies make implementations available. Conditions
decide which beans activate. Entity metadata defines what application records contribute to AI.
Provider and prompt configuration selects external behavior. Application extension points supply
business policy, persistence choices, and domain side effects.

The important idea is scoped precedence. There is no universal rule that YAML always beats Java or
that annotations always beat defaults. Each subsystem defines its own explicit resolution order.

## Scene 2: Dependencies Trigger Spring Boot Auto-Configuration

**Visual:** Show Maven artifacts adding entries from Spring Boot's auto-configuration imports.

```text
ai-fabric-core
  -> AIInfrastructureAutoConfiguration

ai-fabric-rag
  -> RAGAutoConfiguration

ai-fabric-chat-session
  -> ChatSessionAutoConfiguration

vector or provider artifact
  -> its own AutoConfiguration
```

Show this note beside the application class:

```text
@EnableAIInfrastructure = optional marker
dependency + Boot auto-configuration = runtime wiring
```

**Narration:**

Adding an AI Fabric artifact places its auto-configuration class on Spring Boot's
`AutoConfiguration.imports` path. Core contributes `AIInfrastructureAutoConfiguration`. Optional
modules contribute their own configuration and normally declare ordering relative to core or other
Spring infrastructure.

The master core switch is `ai.enabled`. It defaults to true. Optional modules have their own feature
properties, such as RAG, indexing, chat, PII, or governance settings.

`@EnableAIInfrastructure` is intentionally only an optional marker for documentation and
discoverability. It does not import the framework manually. Normal Spring Boot auto-configuration
does the wiring and preserves Boot's ordering rules.

This means the first design decision is the dependency set. A property cannot activate code that is
not on the classpath, and adding a module does not guarantee every bean inside it will activate.

## Scene 3: Conditions Build The Effective Bean Graph

**Visual:** Animate four gates in front of an optional bean.

```text
class present
AND property enabled
AND prerequisite bean present
AND no application replacement when backoff is supported
  -> bean created
```

Use RAG and chat as examples.

```text
RAGProvider default:
RAG enabled + embedding/search/vector prerequisites + no custom RAGProvider

Chat session:
chat enabled + access policy + storage provider
```

**Narration:**

Auto-configuration is a conditional bean graph, not a blanket component switch.

AI Fabric uses Spring conditions such as `ConditionalOnClass`, `ConditionalOnProperty`,
`ConditionalOnBean`, and, for selected defaults, `ConditionalOnMissingBean`. The default RAG
provider, for example, needs the RAG module, enabled RAG configuration, embedding and search
services, and vector contracts. It backs off when an application provides another `RAGProvider`.

Chat memory activates only when `ai.chat.enabled=true`. It also requires a
`ChatSessionAccessControlPolicy` and a storage provider. JPA can supply the default storage, while an
application can provide another `ChatSessionStorageProvider`.

Do not assume that every framework bean supports replacement merely because some defaults do.
Backoff is a bean-by-bean contract. Read the auto-configuration condition or prove it with an
application-context test.

When a capability is absent, inspect the condition report in this order: artifact, property,
required class, prerequisite bean, and competing bean.

## Scene 4: Keep The Configuration Planes Separate

**Visual:** Show four files or code locations feeding distinct runtime concerns.

```text
application.yml / environment
  -> infrastructure, modes, providers, feature switches

ai-entity-config.yml
  -> entity behavior, fields, CRUD indexing policy

Java annotations
  -> entity field projection, indexing strategy, actions

classpath prompt resources and curated packs
  -> prompt versions and coherent mode defaults
```

Add a fifth box outside them:

```text
application beans
  -> access policy, security policy, stores, providers, pipeline steps
```

**Narration:**

Spring properties under `ai.*` configure infrastructure. They choose enabled capabilities, modes,
provider names, models, vector type, retrieval budgets, chat behavior, and related runtime policy.

`ai-entity-config.yml` is a separate entity catalog. It describes entity types, searchable or
embeddable fields, metadata fields, feature gates, and CRUD indexing behavior.

Annotations describe code-local facts: which class maps to an entity type, which fields contain
searchable text or context metadata, how indexing strategies resolve, and which Spring beans expose
governed actions.

Versioned prompt resources and curated packs supply reusable language and coherent defaults.
Application beans implement policies or SPIs that cannot be expressed safely as static
configuration.

Mixing these planes creates brittle designs. A prompt should not decide tenant access. A browser
flag should not register a provider. Entity YAML should not contain a repository side effect.

## Scene 5: Application Configuration Overrides Curated Defaults

**Visual:** Show a precedence stack.

```text
higher-priority Spring Boot configuration
  command line / environment / profile / application config

lower-priority AI Fabric curated pack defaults
```

Show the curated pack loader adding its property source last.

**Narration:**

Normal Spring Boot property-source precedence applies to `ai.*` properties. Secrets should remain
externalized through environment variables or a secret manager rather than committed into YAML.

AI Fabric gives curated packs one additional, explicit rule. `CuratedPackEnvironmentPostProcessor`
reads `ai.curated.pack`, loads `classpath:ai-curated/packs/<pack>.yml`, and adds that property source
last. The pack therefore has lowest precedence. Your application configuration can override its
mode, budget, or prompt-bundle defaults deterministically.

The selected pack resource must exist on the classpath. An unknown pack fails startup instead of
silently pretending that defaults were applied.

Treat a pack as a reviewed starting configuration, not an invisible runtime personality. Your
deployed application configuration remains authoritative over pack defaults.

## Scene 6: Entity YAML And Annotations Have Scoped Precedence

**Visual:** Show a support article entity linked by entity type.

```java
@AICapable(entityType = "support-article")
class SupportArticle {
    @AISearchable private String title;
    @AISearchable private String body;
    @AIContext private String tenantId;
}
```

```yaml
ai-entities:
  support-article:
    features: [embedding, search]
    auto-process: true
    metadata-fields:
      - name: tenantId
        type: string
```

Display this rule prominently:

```text
No global YAML-versus-annotation winner
```

**Narration:**

`@AICapable(entityType=...)` links a Java type to the entity key loaded from
`ai-entity-config.yml`. The default file can be changed with `ai.config.default-file`.

For searchable content extraction, `AICapabilityService` prefers `@AISearchable` fields when the
class declares any. If none exist, it falls back to searchable or embeddable fields from the entity
YAML. This is an extraction rule, not a universal configuration hierarchy.

For annotation-derived `@AIContext` metadata registration, another rule applies. When JPA metadata
discovery is available and enabled, AI Fabric can merge annotation-derived metadata fields into an
existing YAML entity config. Existing YAML field definitions are preserved, missing annotation
fields are appended, and an annotation can fill a missing type without replacing a configured one.

Creating an entirely missing entity config from annotations is disabled by default through
`ai.config.annotation-metadata.create-missing-entity-config=false`. YAML remains the deliberate
entity catalog unless you opt into generated minimal configs.

## Scene 7: Indexing Strategy Has Its Own Annotation Order

**Visual:** Show three annotation levels resolving one operation.

```text
@AIProcess method strategy, when not AUTO
  > @AICapable operation strategy
  > @AICapable default strategy
  > framework fallback ASYNC
```

Beside it, show YAML as a capability gate rather than another item in that strategy chain.

```text
entity YAML:
auto-process + features + CRUD operation flags
```

**Narration:**

Indexing strategy demonstrates why precedence must be discussed per subsystem.

`IndexingStrategyResolver` first uses an explicit method-level `@AIProcess` strategy. When that is
`AUTO`, it checks the operation-specific `@AICapable` strategy for create, update, or delete. When
that is also `AUTO`, it uses the entity's default annotation strategy, with asynchronous indexing as
the final fallback.

Entity YAML still controls whether the configured AI features and CRUD work are enabled. The
strategy decides how approved work is scheduled; it does not override an entity capability gate.

This separation lets code express lifecycle mechanics close to the method while deployment and
entity configuration decide whether the capability is active. Tests should prove both the resolved
strategy and the resulting indexing action plan.

## Scene 8: Provider Registration And Provider Selection Are Different

**Visual:** Show two phases.

```text
Phase 1: register implementations
provider artifact + enabled provider block -> provider beans

Phase 2: select an implementation
ai.providers.* selection -> effective provider and model
```

Show independent lanes.

```text
LLM provider
embedding provider
vector database service
```

**Narration:**

A provider can be selected only after its implementation is registered. The Spring AI provider
module conditionally registers LLM and embedding adapters for the provider blocks it supports. The
native ONNX module can register local embeddings. Vector modules independently contribute
`VectorDatabaseService` implementations.

LLM, embedding, and vector choices are separate. `ai.providers.llm-provider` selects the global LLM
name. `ai.providers.embedding-provider` selects the embedding contract. `ai.vector-db.type` selects
the vector implementation. You can combine a remote LLM, local ONNX embeddings, and Lucene vector
storage without changing application orchestration code.

Custom `AIProvider` or `EmbeddingProvider` implementations must expose a stable provider name and be
registered as Spring beans. A custom `VectorDatabaseService` can replace a default where the vector
auto-configuration explicitly backs off.

Configuration names alone are not proof of the effective runtime. Verify bean registration,
provider availability, selected names, model, embedding dimensions, and vector diagnostics.

## Scene 9: LLM Purposes And Request Defaults Resolve Deliberately

**Visual:** Show provider selection for two LLM purposes.

```yaml
ai:
  providers:
    llm-provider: openai
    orchestration:
      llm-provider: openai
      model: gpt-4o-mini
    generation:
      llm-provider: anthropic
      model: <generation-model>
```

Then show the option precedence.

```text
explicit request model / maxTokens / temperature
  > purpose defaults
  > global provider defaults
```

**Narration:**

AI Fabric distinguishes orchestration from generation. Intent extraction and planning use
`LlmPurpose.ORCHESTRATION`. User-facing answers and summaries use `LlmPurpose.GENERATION`.

Each purpose can select a provider and model. When a purpose provider is absent, it falls back to
the global LLM provider. For model, token, and temperature fields, an explicit server-created
request value is preserved; purpose defaults fill missing fields, and provider defaults fill the
remaining gaps.

Purpose configuration can also carry trusted connection overrides such as an API key, base URL,
deployment, or API version. `AICoreService` merges these into an internal provider override before
dispatch. They are server-side connection data, not values a browser should be allowed to invent.

`AIProviderManager` prefers the requested available provider and can use other available providers
according to its selection and fallback behavior. Transient file URL requests do not fall back after
failure because AI Fabric must preserve provider-specific document-usage evidence. Provider failures
must remain visible rather than becoming a fabricated successful answer.

## Scene 10: Embedding And Vector Selection Need Independent Proof

**Visual:** Show a semantic-search path with two independently selected contracts.

```text
text
  -> selected EmbeddingProvider
  -> vector with known dimensions
  -> selected VectorDatabaseService
  -> similarity results
```

Show a warning beside selection configuration.

```text
configured name != runtime proof
```

**Narration:**

Core constructs `AIEmbeddingService` from registered `EmbeddingProvider` beans. It first tries to
match `ai.providers.embedding-provider` by name. In the current implementation, when no provider
matches but providers exist, it can select the first registered provider. That makes startup and
smoke-test evidence important: assert the effective provider instead of trusting a misspelled name.

An explicit embedding model on the server-created request is preserved. Otherwise AI Fabric applies
the selected provider's embedding default. Caching keys include provider and effective model, and an
optional ONNX fallback can be used when configured and available.

The resulting vector dimensions must match the selected vector index. Changing an embedding model
or dimension is a data migration concern, not merely a YAML edit.

Vector selection is owned by `VectorDatabaseService`, not Spring AI prompt configuration. Provider
health, metadata-filter support, and lifecycle diagnostics should be tested for the chosen store.

## Scene 11: Curated Packs Supply Transparent Defaults

**Visual:** Show the current pack choices as dependency plus selector.

```text
ai-fabric-curated-default   + ai.curated.pack=default
ai-fabric-curated-commerce + ai.curated.pack=commerce
ai-fabric-curated-support  + ai.curated.pack=support
```

Then open one pack into two outputs.

```text
orchestration profile and named modes
prompt bundle overlay version
```

**Narration:**

A curated pack packages a coherent set of orchestration defaults and prompt overlays for a domain
or usage style. The current repository contains default, commerce, and support packs.

You add the corresponding artifact and set `ai.curated.pack`. The pack can define a profile, named
modes, capability flags, retrieval budgets, position-routing guidance, and a prompt overlay version.

The pack contributes configuration only. It does not replace the orchestration pipeline, action
registry, target validation, confirmation enforcement, access checks, or response sanitization.
Core remains mode-name agnostic; a named mode is a key that resolves a typed capability bundle.

Because pack properties have lowest precedence, an application can narrow an allowlist, lower a
budget, change its default mode, or select a different overlay without editing the pack artifact.

## Scene 12: Prompt Overlays Replace Only The Templates They Contain

**Visual:** Show deterministic prompt resolution.

```text
family + name
  -> overlay version 1
  -> overlay version 2
  -> base version
```

Show the classpath shape.

```text
prompts/<family>/<version>/<name>.md
```

Example:

```yaml
ai:
  prompts:
    bundle:
      overlays:
        - v1-support-app
        - v1-support
      base-version: v1
```

**Narration:**

`PromptTemplateResolver` receives a prompt family and name. It tries configured overlay versions in
order and then the base version. Duplicate versions are removed while preserving the first
occurrence. The resolved key is cached.

An overlay is intentionally partial. Your application can add one uniquely named classpath version
containing only the templates it needs to change. Missing templates fall through to the next
overlay, then to the complete base bundle. If no candidate contains the requested template,
resolution fails visibly.

Use a new overlay version instead of relying on classpath collisions at the same path. Test the
resolved version and important prompt clauses, not just that a Markdown file exists.

Prompts influence interpretation and wording. They cannot grant a mode capability, authorize a
tenant, register an action, or bypass confirmation.

## Scene 13: Extend Through Contracts, Policies, And Governed Actions

**Visual:** Group application extension points by responsibility.

```text
Infrastructure contracts
  AIProvider, EmbeddingProvider, VectorDatabaseService, RAGProvider

Pipeline and storage
  PipelineStep, ChatSessionStorageProvider, PendingActionStore

Application policy
  EntityAccessPolicy, SecurityAnalysisPolicy,
  ChatSessionAccessControlPolicy, ComplianceCheckProvider

Domain behavior
  @AIAction + @ActionExecute
  AIActionRegistryContributor for reviewed non-annotation sources
```

**Narration:**

Choose the narrowest extension point that owns the missing behavior.

Implement a provider contract when you are adding infrastructure. Add an ordered `PipelineStep`
when a cross-cutting request stage genuinely belongs in orchestration. Supply a storage SPI when the
default persistence does not fit. Implement an access, security, chat, or compliance policy when the
decision depends on your application's verified subject and rules.

For domain behavior, declare an `@AIAction` Spring bean with one `@ActionExecute` method and optional
allowed, confirmation-message, or post-action-facts methods. The registry can also accept reviewed
`AIActionRegistryContributor` sources such as connector or database catalogs. Duplicate stable
action names fail instead of using source precedence.

The framework still validates mode policy, action metadata, parameters, targets, authorization, and
confirmation before it invokes application code. An extension contributes behavior at a defined
boundary; it does not receive permission to bypass the boundary.

## Scene 14: Trace One Complete Support Configuration

**Visual:** Build the Support Knowledge Assistant from left to right.

```text
Dependencies
  core + Spring AI provider + RAG + Lucene + chat-session + support curated pack

Application configuration
  provider names + vector type + support pack + chat + mode overrides

Entity model
  support-article YAML + @AICapable + @AISearchable + @AIContext

Application extensions
  ChatSessionAccessControlPolicy + create_support_ticket action
```

Then show startup and request proof.

```text
condition report
effective typed properties
effective provider and model
resolved prompt version
registered vector space and action
retrieval and confirmation integration test
```

**Narration:**

Trace the complete model once.

Dependencies make core, provider, retrieval, vector, chat, and support-pack implementations
available. Spring conditions build the bean graph. Application configuration selects providers,
Lucene, chat, and the support pack, while any explicit app values override pack defaults.

The support article's entity key joins its YAML behavior with annotation-driven field projection.
The application supplies chat access policy and the real `create_support_ticket` action. Prompt
resolution selects an application overlay when present, otherwise the support overlay, otherwise the
base prompt.

Verification should mirror those layers. Use an application-context test for conditions and
backoff. Bind real YAML into typed properties. Test entity merge and extraction. Assert provider
purpose routing and embedding dimensions. Resolve each critical prompt and assert its selected
version. Finally, run retrieval and two-turn action confirmation through the public application
boundary.

When something fails, diagnose the owner: bean graph, property binding, entity projection, provider
routing, prompt resolution, policy, or domain handler. Do not repair every configuration problem by
making the prompt more forceful.

## Final Scoped Precedence Reference - Do Not Narrate As A List

| Decision | Resolution order |
| --- | --- |
| Spring properties | Normal Spring Boot property-source precedence; curated pack defaults are explicitly last. |
| Searchable entity content | `@AISearchable` fields when present; otherwise entity YAML searchable or embeddable fields. |
| Annotation metadata merge | Existing YAML metadata definition; annotation fills missing type or appends missing field; optional generated minimal config last. |
| Indexing strategy | Explicit method `@AIProcess`; operation-specific `@AICapable`; entity default; `ASYNC` fallback. |
| LLM provider name | Purpose-specific provider; global LLM provider. |
| LLM request options | Explicit server-created request value; purpose default; provider default. |
| Embedding model | Explicit server-created request model; selected embedding-provider default. |
| Prompt template | Overlay versions in configured order; base version. |
| Action names from multiple sources | No winner; duplicate normalized names fail registration. |

## Final Extension Point Reference - Do Not Narrate As A List

| Need | Preferred extension shape |
| --- | --- |
| New LLM or embedding implementation | Spring bean implementing `AIProvider` or `EmbeddingProvider`, with a stable provider name. |
| New vector implementation | Spring bean implementing `VectorDatabaseService`; verify lifecycle and filter capabilities. |
| Specialized retrieval | `RAGProvider` or `AdvancedRAGProvider` implementation where the default auto-configuration backs off or accepts it. |
| Cross-cutting orchestration stage | Ordered `PipelineStep`, with termination, sanitization, and failure tests. |
| Entity access decision | Application `EntityAccessPolicy`. |
| Additional threat analysis | Application `SecurityAnalysisPolicy`. |
| Chat ownership and persistence | Required `ChatSessionAccessControlPolicy` plus optional custom `ChatSessionStorageProvider`. |
| Compliance decision | Governance `ComplianceCheckProvider` with compliance enabled. |
| Domain read or write behavior | Annotated `@AIAction` bean with application service execution. |
| Non-annotation action catalog | Validated `AIActionRegistryContributor`; duplicate action names fail. |
| Application prompt specialization | Unique classpath overlay version plus explicit `ai.prompts.bundle.overlays` order. |

## Accuracy Guardrails For NotebookLM

- Keep the current AI Fabric configuration and extension model as the subject.
- State that dependencies make implementations available, while Spring conditions determine the
  effective bean graph.
- State that `@EnableAIInfrastructure` is an optional marker and does not manually import the
  framework auto-configuration.
- Do not imply that adding an optional module automatically activates every bean it contains.
- Do not claim that every AI Fabric bean is replaceable. Mention backoff only where the relevant
  auto-configuration uses `ConditionalOnMissingBean` or another explicit extension contract.
- Do not present one universal YAML-versus-annotation precedence rule. Use the scoped rules in this
  script.
- State that annotation metadata auto-registration depends on JPA metadata discovery and its
  configuration, and that creating missing entity configs is disabled by default.
- Do not imply that `@AIContext` text is embedded as searchable content.
- Keep LLM, embedding, and vector provider selection separate.
- Do not imply that a configured provider name alone proves the provider used at runtime.
- Do not expose API keys, endpoint secrets, transient file URLs, or trusted connection overrides to
  the browser or course output.
- State that curated packs provide lowest-precedence configuration and prompt defaults only.
- Use the prompt path `prompts/<family>/<version>/<name>.md` exactly.
- Do not imply that a prompt overlay can authorize access, enable an unconfigured capability, or
  bypass action confirmation.
- Do not imply that action sources use precedence when names collide; duplicate names fail.
- Do not imply that an LLM, provider adapter, or curated pack owns an application domain side effect.
- Do not invent configuration keys, supported provider combinations, annotations, extension
  interfaces, startup output, benchmarks, compliance claims, or customer outcomes.
- Do not present a generated video as runtime or test evidence.
