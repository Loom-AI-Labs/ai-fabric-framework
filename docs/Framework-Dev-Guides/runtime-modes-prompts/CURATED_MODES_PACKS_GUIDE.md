# Curated Modes Packs (OSS / Pro-ready)

## Goal
Make it easy to enable a **coherent orchestration setup** (profile + modes + prompt bundles) with **one config key**, without adding custom code or bypassing framework guardrails.

Related planning:
- `../architecture/actions-governance/PLAN_READ_ONLY_ACTION_RESOLVER_AND_THINKER_MODES.md`

Curated packs are **transparent**:
- They only ship **configuration defaults** and **prompt templates**.
- They do **not** replace the pipeline, validators, confirmation handling, or security.

## How it works
1) Add a curated pack dependency (example: catalog pack).
2) Set `ai.curated.pack` in your app configuration.
3) The framework loads the pack YAML from:
   - `classpath:ai-curated/packs/<pack>.yml`
4) Pack properties are added with **lowest precedence** (application config can override them deterministically).

Implementation detail:
- Core loads the pack via `CuratedPackEnvironmentPostProcessor`.

Important:
- Curated packs are **configuration only**. Core does not branch on specific mode names.
- `mode` is treated as a **selector key**: core looks up `ai.orchestration.modes.<mode>` and applies those overrides as a policy capability bundle.

## Available packs
- `commerce` → production chat defaults for e-commerce flows:
  - `navigator` (keep current behavior)
  - `navigator_deep` (deep retrieval, actions disabled)
  - `executor` (action-first; optionally restricted retrieval)
  - `cart_assistant` (action-oriented commerce helper)

Planned (not shipped in this repo yet):
- `catalog`
- `support`
- `resolver_assistant`
- `thinker`

## Enable a pack
Example (commerce):

```yaml
ai:
  curated:
    pack: commerce
```

Dependency (Maven):

```xml
<dependency>
  <groupId>com.ai.fabric</groupId>
  <artifactId>ai-curated-commerce</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Override pack defaults (recommended pattern)
Because pack properties are loaded as **defaults**, you can override anything explicitly in your app:

```yaml
ai:
  orchestration:
    profile: PRODUCTION_CHAT
    strict-mode-routing: true
```

Mode resolution semantics (core):
1) If request has `mode` and it exists under `ai.orchestration.modes.*` → requested mode wins.
2) Otherwise, `ai.orchestration.default-mode` is used (must also exist under `ai.orchestration.modes.*`).

`position` is carried for observability only; core does **not** route based on it.

If you want “position → mode” convenience:
- keep a `position-routing` map in your curated pack as advisory config, and
- implement a small app/web-layer router that sets `mode` when it is missing.

## Capability flags (policy-driven, mode-agnostic)
Modes can set explicit capability flags that drive core behavior (no mode-name branching), e.g.:
- `actions-preferred` (bias extraction toward actions when actions are enabled)
- `knowledge-base-overview-enabled` (include/exclude KB overview in prompts)
- `retrieval-allowlist-required` (require a retrieval allowlist when retrieval is requested)
- `vector-space-selection-required` (require explicit vectorSpace selection when allowlist has >1 space)

These appear in `metadata.orchestrationPolicy` for debugging.

## Optional: READ→RAG probe visibility (debug)
Some modes may treat READ actions as “helper tools”: if a READ action returns an empty successful payload, the orchestrator can fall back to RAG.

By default, the final response does **not** include any extra metadata about the attempted READ action (to keep payloads minimal).

To surface the attempted READ action in the final response (as `result.metadata.readProbe`), enable it per mode:

```yaml
ai:
  orchestration:
    modes:
      cart_assistant:
        expose-read-probe-fallback-attempt: true
```

## Prompt bundle pinning
Each pack enables its prompt overrides by adding an overlay bundle version via:
- `ai.prompts.bundle.overlays`

The framework resolves templates by trying overlay versions first, then falling back to the base bundle version (`ai.prompts.bundle.base-version`, default `v1`).

You can override it in your app if you need to run a different prompt bundle version.

## Optional: retrieval hints (strict contract)
The intent extractor may optionally return a retrieval hint in the top-level metadata:
- `metadata.retrievalQueryHint`

The orchestrator will only apply it when:
- there is exactly **one** retrieval intent in the response
- the hint passes strict validation (bounded length, no obvious PII markers)

This is designed to be **safe and fail-closed**: invalid/unsafe hints are ignored deterministically.
