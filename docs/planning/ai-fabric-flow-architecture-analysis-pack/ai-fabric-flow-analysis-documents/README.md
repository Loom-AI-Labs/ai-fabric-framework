# AI Fabric Business Flow Visual Pack

This pack translates the AI Fabric specialist-defined enablement proposal into a business story:

> Any trusted starting point → governed specialist intelligence → typed, evidence-linked outcome
> → optional human decision or application-owned action.

The pack intentionally separates current foundations from proposed phases so the vision can be
explained without presenting future work as already released.

## Reference Proof Isolation

The existing `ai-fabric-account-resolver` real app and live demo remain unchanged as the behavioral
baseline. Agentic implementation work belongs in a separately deployable copy:

```text
examples/real-apps/agentic-ai-action-resolver
```

The new app will own its configuration, Dockerfile, tests, seed/session state, backend deployment,
and website demo route. It may register the domain specialist as `account-resolver@1`; the
specialist ID describes the reusable capability, while `agentic-ai-action-resolver` names the
reference product that proves the new architecture.

## Visuals

| # | Visual | Business question answered |
| --- | --- | --- |
| 01 | AI Fabric Enablement Landscape | What kinds of intelligent products can the enablement layer support? |
| 02 | Interactive Intelligent Application | How does a user receive one coherent, live-data-aware response? |
| 03 | Application-Called Intelligence | How can a Java service use AI without creating a chat or fake user? |
| 04 | Fixed Multi-Specialist Plan | How can repeatable expert work be split into typed, auditable stages? |
| 05 | Parallel Specialist Analysis | How can independent checks run together without sharing privileges or conversation state? |
| 06 | Proactive Intelligence | How can an event, schedule, file, or batch start intelligence before a user asks? |
| 07 | Missing Input and Safe Resume | How can one branch ask for a missing fact instead of guessing or restarting everything? |
| 08 | Durable Human Review | How can sensitive work pause for a qualified human decision and resume safely? |
| 09 | Conversation Manager | How can one intelligent front door route users across governed capabilities? |
| 10 | Delegation and Handoff | How can specialists request help or transfer responsibility without privilege union? |
| 11 | Governed Action Lifecycle | How does natural-language intent become an authoritative application result? |
| 12 | Live Data Intelligence Loop | How does searchable AI evidence remain aligned with application truth? |

Each visual is available as:

- editable SVG for documentation and design tools;
- 1600 × 1000 PNG for presentations, websites, and technical articles.

`00-contact-sheet.png` provides a quick overview of the complete set.

## Suggested Presentation Order

For a business or product audience:

1. Enablement Landscape
2. Interactive Intelligent Application
3. Application-Called Intelligence
4. Proactive Intelligence
5. Governed Action Lifecycle
6. Durable Human Review
7. Product-specific flow relevant to the audience

For an architecture audience:

1. Enablement Landscape
2. Interactive Intelligent Application
3. Fixed Multi-Specialist Plan
4. Parallel Specialist Analysis
5. Missing Input and Safe Resume
6. Conversation Manager
7. Delegation and Handoff
8. Governed Action Lifecycle
9. Live Data Intelligence Loop

## Proposal Phase Labels

- **Current foundation** — capabilities already present in the existing bounded orchestration,
  live sync, RAG, action, session, or confirmation paths.
- **P1** — canonical specialist contract and execution ingress.
- **P2** — fixed execution plans, dialogue ownership, projected context, and typed input resume.
- **P3** — durable execution, proactive triggers, human review, delegation, and handoff.
- **P4** — optional governed conversation manager and bounded supervised choice.
- **P5** — bounded parallel specialist execution after isolation and cancellation are proven.

## Visual Language

- Cyan: source data, triggers, and live evidence.
- Blue: deterministic AI Fabric control.
- Violet: specialist intelligence.
- Amber: input, confirmation, and human review boundaries.
- Green: validated results and application-issued outcomes.
- Rose: unresolved or unknown action outcomes.
- Navy/grey: application-owned policy, services, and truth.

The repeated footer is the architectural promise behind every flow:

> The application remains the authority.
