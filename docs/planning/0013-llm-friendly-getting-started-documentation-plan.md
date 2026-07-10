# LLM-Friendly Getting Started Documentation Plan

## Status

Implemented.

Canonical framework docs now live in:

- `docs/getting-started`
- `docs/llm-context`

The public `aifabric` website mirrors those docs as presentation-layer content.

## Why This Is Needed

AI Fabric now has enough real framework surface area that scattered documentation creates friction:

- `docs/guides/*` already contains user-facing markdown for installation, concepts, modules,
  configuration, use cases, examples, and quickstart.
- `docs/Framework-Dev-Guides/*` contains deeper framework guides, LLM lessons learned, architecture
  notes, runtime modes, action governance, RAG, security, verification, and provider guidance.
- `examples/real-apps/*/README.md` contains valuable, concrete app-level integration evidence.
- The public docs UI in `aifabric` has a `Getting Started` section, but it currently links into a
  broad story-heavy documentation tree that is not optimized for first-time implementation or LLM
  coding assistant context.

The goal is not to write more disconnected docs. The goal is to create a clear documentation system
with stable entry points, short context packs, copyable implementation recipes, and explicit source
ownership.

## Primary Goals

1. Make the Getting Started section useful for a developer integrating AI Fabric into a real Spring
   Boot application.
2. Make the same material usable by LLM coding assistants without forcing them to scan dozens of
   narrative pages.
3. Keep one source of truth in the framework repo, then render or mirror selected pages into the
   public website.
4. Separate quick adoption docs from maintainer/internal architecture docs.
5. Make every guide explicit about modules, dependencies, configuration, code hooks, tests, and real
   examples.
6. Avoid fake certainty: mark experimental, planned, smoke-only, and production-ready paths clearly.

## Repository Ownership Decision

Build the canonical documentation in the **framework repository** first:

```text
AI-Fabric-Framework/
  docs/getting-started/
  docs/llm-context/
```

The `aifabric` website repository is the **presentation layer**, not the source of truth:

```text
aifabric/
  src/pages/docs/
  src/components/docs/
```

This matters because the Getting Started material is framework behavior, dependency, configuration,
testing, and release guidance. It must be versioned with the framework code and releases so GitHub
users, Maven users, release notes, and LLM coding assistants all read the same canonical markdown.

The website should render, mirror, or summarize the canonical framework markdown after the content is
stable. It should include source links back to the framework repo docs. Avoid hand-maintaining a
separate website-only implementation guide unless the page is clearly a visual wrapper around the
canonical source.

Decision summary:

| Repo | Role | Owns |
| --- | --- | --- |
| `AI-Fabric-Framework` | Source of truth | Markdown docs, LLM context pack, code-backed recipes, real app references, versioned release guidance. |
| `aifabric` | Presentation layer | Website routes, sidebar/navigation, visual rendering, source links, discoverability. |

Implementation order:

1. Create and stabilize `docs/getting-started` and `docs/llm-context` in the framework repo.
2. Validate snippets and real app references against the framework repo.
3. Update the public website docs navigation and pages in `aifabric`.
4. Add a lightweight sync/render policy so website content does not drift from canonical markdown.

## Non-Goals

- Do not move private credential handoff files into public docs.
- Do not turn story pages into the primary implementation path.
- Do not copy every internal planning document into Getting Started.
- Do not make the website the only source of truth; markdown in the framework repo should remain the
  canonical source for LLM and developer consumption.

## Current Documentation Evidence

Current useful source material:

| Source | Value | Issue |
| --- | --- | --- |
| `README.md` | Project summary, release coordinates, high-level feature list | Too short for implementation. |
| `docs/guides/README.md` | Existing public user-guide index | Good base, but not enough task routing for LLM assistants. |
| `docs/guides/01-installation.md` | Maven coordinates, Java/Spring requirements, module setup | Should become part of the main install path. |
| `docs/guides/02-understanding-ai-fabric.md` | Mental model | Should be shortened into a first-hour learning path. |
| `docs/guides/03-modules.md` | Module catalog | Needs task-oriented module recipes. |
| `docs/guides/04-configuration.md` | Config reference | Useful but too large for first-run LLM context. |
| `docs/guides/06-example-apps.md` | Real app map | Should connect directly to demo/readme architecture pages. |
| `docs/guides/07-quickstart.md` | Hands-on first app | Good base; needs current release validation and LLM-friendly copy blocks. |
| `docs/Framework-Dev-Guides/README.md` | Internal/deep guide map | Useful for maintainers, too broad for Getting Started. |
| `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md` | Hard-earned framework-user lessons | Should feed a public “LLM assistant rules” page. |
| `examples/real-apps/*/README.md` | Concrete working app patterns | Should be indexed into app-specific implementation recipes. |
| `aifabric/src/components/docs/DocsSidebar.tsx` | Public website docs taxonomy | Too many story pages before implementation docs. |
| `aifabric/src/pages/docs/QuickStart.tsx` | Current website quickstart page | Visual and story-rich, but not ideal as canonical LLM context. |

## Proposed Information Architecture

Create a new canonical docs area:

```text
docs/getting-started/
  README.md
  00-llm-start-here.md
  01-choose-your-path.md
  02-installation.md
  03-first-semantic-search.md
  04-first-rag-chat.md
  05-first-governed-action.md
  06-chat-session-memory.md
  07-real-provider-openai.md
  08-local-onnx-embeddings.md
  09-vector-storage-lucene.md
  10-security-access-policy.md
  11-testing-and-verification.md
  12-real-apps-map.md
  13-production-checklist.md
```

Create a compact LLM context pack:

```text
docs/llm-context/
  README.md
  AI_FABRIC_CONTEXT_INDEX.md
  AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md
  AI_FABRIC_CAPABILITY_MAP.md
  AI_FABRIC_MODULE_DECISION_TREE.md
  AI_FABRIC_COMMON_TASK_RECIPES.md
  AI_FABRIC_TROUBLESHOOTING_PLAYBOOK.md
  AI_FABRIC_REAL_APP_REFERENCE.md
```

Keep existing deep docs, but treat them as references:

```text
docs/Framework-Dev-Guides/
docs/guides/
docs/release-notes/
docs/planning/
examples/real-apps/*/README.md
```

## Human Getting Started Flow

The public website Getting Started section should become:

1. **Start Here**
   - What AI Fabric is
   - Supported Java/Spring versions
   - Current recommended release
   - What to build first

2. **Choose Your Path**
   - Semantic search only
   - RAG chat over app data
   - Governed actions and confirmations
   - Chat memory
   - Behavior analysis
   - Tenant-safe retrieval

3. **Install**
   - BOM
   - starter
   - provider
   - vector provider
   - optional modules
   - exact Maven snippets

4. **Build First Semantic Search**
   - minimal Spring Boot app
   - index one entity
   - search it
   - smoke profile
   - test command

5. **Add RAG Chat**
   - add RAG module
   - define vector spaces
   - run query
   - inspect evidence

6. **Add Governed Actions**
   - action handler shape
   - confirmation required
   - parameter extraction
   - action result shape

7. **Provider Setup**
   - OpenAI through Spring AI provider
   - ONNX embeddings
   - smoke profile
   - fail-closed behavior

8. **Production Readiness**
   - access policy
   - auth context
   - persistence
   - verification tests
   - CI gates

9. **Real Apps**
   - Shopping
   - Account Resolver
   - Behavior Signals
   - Tenant Guard
   - smaller examples

## LLM Coding Assistant Flow

LLM sessions need concise, authoritative prompts and routing rules.

### `00-llm-start-here.md`

Purpose: a compact document users can paste into a coding assistant session.

Required sections:

- Current AI Fabric version and Java/Spring assumptions.
- Do not bypass AI Fabric with frontend keyword routing.
- Always identify the app module and AI Fabric modules used.
- Prefer existing real apps as templates.
- Add tests for every framework-facing integration change.
- Never hide LLM/provider failures behind deterministic fallback unless the guide explicitly says so.
- Use smoke profile for no-key local tests; use real provider tests only when keys are present.

### `AI_FABRIC_CONTEXT_INDEX.md`

Purpose: map user requests to the smallest set of docs an LLM should read.

Example routing:

| User asks | Read these docs |
| --- | --- |
| "Add semantic search" | `03-first-semantic-search.md`, `09-vector-storage-lucene.md` |
| "Add RAG chat" | `04-first-rag-chat.md`, `AI_FABRIC_CAPABILITY_MAP.md` |
| "Add actions" | `05-first-governed-action.md`, action governance guide |
| "Fix Pipeline step failed: AccessControl" | troubleshooting playbook, access policy guide |
| "Use OpenAI" | `07-real-provider-openai.md`, Spring AI provider guide |
| "Make a real app demo" | `12-real-apps-map.md`, matching app README |

### `AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md`

Purpose: enforce philosophy and prevent bad implementation shortcuts.

Must include:

- UI scenario chips must send natural language to the AI Fabric app endpoint unless explicitly labeled
  as manual/debug controls.
- Do not fake intelligence with string matching.
- Do not alter framework contracts just to make a demo pass.
- App-owned policy hooks are required; missing `EntityAccessPolicy` is not an LLM issue.
- RAG requires indexed evidence.
- Chat history should use AI Fabric chat-session when the framework supports it.
- Live demo guides should expose real LLM behavior, not hidden fallback.

### `AI_FABRIC_COMMON_TASK_RECIPES.md`

Purpose: copyable mini-recipes with dependencies, config, code, and tests.

Initial recipes:

1. Add AI Fabric starter to a Spring Boot app.
2. Configure smoke profile.
3. Configure OpenAI LLM and embedding provider.
4. Configure ONNX embeddings.
5. Configure Lucene vector store.
6. Add searchable entity data.
7. Add a RAG endpoint.
8. Add an action handler with confirmation.
9. Add an access policy.
10. Add chat memory.
11. Add behavior event provider.
12. Write smoke and real-provider tests.

## Canonical Page Template

Every Getting Started page should follow this shape:

```text
# Title

## What You Will Build
One paragraph.

## When To Use This
Bullets.

## Modules Required
Table with Maven artifact, why, required/optional.

## Minimal Configuration
YAML block.

## Code
Small complete snippets.

## Run It
Commands.

## Verify It
Expected responses and tests.

## Common Failures
Symptoms, cause, fix.

## Real App Reference
Links to examples/real-apps modules.

## LLM Assistant Notes
Rules a coding assistant should follow while implementing this page.
```

## Website Changes

Update `aifabric` docs navigation:

```text
Getting Started
  Start Here
  Choose Your Path
  Installation
  First Semantic Search
  First RAG Chat
  First Governed Action
  Chat Memory
  Providers
  Production Checklist
  Real Apps Map
  LLM Assistant Context
```

Move story pages below implementation docs. Stories remain useful, but they should not be the first
path for someone trying to integrate the framework.

Website should either:

1. render the canonical markdown from the framework repo, or
2. mirror generated content with a clear source path and last-updated commit.

Do not maintain divergent hand-written React docs and markdown docs for the same guide.

## Source Of Truth Policy

- Canonical docs live in `AI-Fabric-Framework/docs/getting-started` and
  `AI-Fabric-Framework/docs/llm-context`.
- Website docs should be generated from or synchronized with those markdown files.
- Website docs in `aifabric` must be treated as presentation. If a guide's technical content changes,
  update the framework markdown first, then update the website rendering.
- Website pages should include a visible or metadata-level source reference to the canonical
  framework markdown path.
- Deep internal docs stay in `docs/Framework-Dev-Guides`.
- Planning docs stay in `docs/planning`.
- Release migration notes stay in `docs/release-notes`.
- Real app behavior must be backed by `examples/real-apps/*/README.md` and runnable tests.

## Migration Map From Existing Docs

| New doc | Seed from |
| --- | --- |
| `README.md` | `docs/guides/README.md`, project `README.md` |
| `00-llm-start-here.md` | `AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`, framework philosophy |
| `01-choose-your-path.md` | `docs/guides/05-use-cases.md`, demo app README files |
| `02-installation.md` | `docs/guides/01-installation.md` |
| `03-first-semantic-search.md` | `docs/guides/07-quickstart.md`, `smart-faq-assistant` |
| `04-first-rag-chat.md` | RAG guides, `chat-capabilities-demo`, shopping demo |
| `05-first-governed-action.md` | action governance docs, account resolver, IT support bot |
| `06-chat-session-memory.md` | chat-capabilities demo README and chat-session module docs |
| `07-real-provider-openai.md` | Spring AI provider guide, release notes 0.3.x |
| `08-local-onnx-embeddings.md` | ONNX provider docs, quickstart |
| `09-vector-storage-lucene.md` | vector provider docs, tenant guard, smart FAQ |
| `10-security-access-policy.md` | access policy docs, lessons learned |
| `11-testing-and-verification.md` | CI guide, RealAPI provider matrix guide |
| `12-real-apps-map.md` | `docs/guides/06-example-apps.md`, all real app README files |
| `13-production-checklist.md` | release notes, verification playbook, production lessons |

## Implementation Phases

### Phase 1: Inventory And Skeleton

- Create `docs/getting-started` and `docs/llm-context`.
- Add the canonical page template.
- Add stub pages with source links and TODO markers.
- Add a docs index that clearly separates public adoption docs from maintainer docs.

### Phase 2: LLM Context Pack

- Write `00-llm-start-here.md`.
- Write `AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md`.
- Write `AI_FABRIC_CONTEXT_INDEX.md`.
- Extract the strongest lessons from `AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`.
- Keep each context file short enough to paste into a coding assistant session.

### Phase 3: First Implementation Path

- Rewrite installation.
- Rewrite first semantic search.
- Add smoke profile instructions.
- Add exact Maven dependencies for 0.3.3.
- Add verification commands.
- Link to `smart-faq-assistant` and/or a minimal quickstart example.

### Phase 4: Production AI Paths

- Add first RAG chat.
- Add first governed action.
- Add OpenAI provider.
- Add ONNX embeddings.
- Add Lucene vector provider.
- Add access policy and security checks.

### Phase 5: Real App Documentation

- Create the real apps map.
- For each real app, capture:
  - purpose
  - AI Fabric modules used
  - providers used
  - annotated entities
  - action handlers
  - endpoints
  - run command
  - tests
  - what a user should copy from it

### Phase 6: Website Integration

- Update `aifabric` docs sidebar.
- Add Getting Started pages in the same order as the canonical markdown.
- Add a route for LLM Assistant Context.
- Reduce prominence of story docs in the default Getting Started path.
- Add source links back to markdown files.

### Phase 7: Verification

- Run markdown link checks.
- Run website build.
- Run at least one smoke example from the docs.
- Verify snippets compile or come from tested examples.
- Add a CI docs check if practical.

## Acceptance Criteria

This project is complete when:

- A first-time user can install AI Fabric and run a smoke semantic search app from the docs.
- A user can choose a path: RAG, actions, chat memory, behavior, tenant-safe retrieval.
- An LLM coding assistant can read one context index and know which docs to open for a task.
- The docs clearly identify required modules and configuration for every path.
- The website Getting Started section mirrors the canonical markdown structure.
- Story pages are still available but no longer dominate the onboarding path.
- Real app README files are linked as implementation evidence.
- Common live-demo and integration failures are documented with symptoms and fixes.

## Risks

| Risk | Mitigation |
| --- | --- |
| Docs drift from code | Source snippets from working real apps where possible; add verification commands. |
| Website and markdown diverge | Treat framework markdown as canonical; add source links or generation workflow. |
| LLM context grows too large | Keep context pack small and task-routed. |
| Too many paths overwhelm users | Start with semantic search, RAG, actions, providers, production checklist. |
| Internal docs leak into public docs | Keep `Framework-Dev-Guides` as deep/internal reference and curate public docs. |

## Open Questions

1. Should the website render markdown directly from the framework repo, or should we copy generated
   content into `aifabric` at release time?
2. Should `docs/llm-context` include a root `llms.txt` style index for external crawlers and coding
   tools?
3. Should the first quickstart use only smoke providers, or should it include an optional OpenAI path
   inline?
4. Should the canonical docs target the released version only (`0.3.3`) or also document unreleased
   `main` behavior?
5. Should we add a tiny dedicated quickstart app under `examples/real-apps` that exactly matches the
   Getting Started guide?
