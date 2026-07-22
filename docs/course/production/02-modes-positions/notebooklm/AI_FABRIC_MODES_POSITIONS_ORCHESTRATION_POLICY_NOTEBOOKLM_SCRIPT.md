# NotebookLM Video Script: Modes, Positions, And Orchestration Policy

## Production Instruction

Create a focused ten-minute technical explanation for Java and Spring Boot developers. AI Fabric is
the subject. Use only this script. Do not present a generic chatbot tutorial.

## Opening

A production AI endpoint should not expose the same capabilities everywhere. A knowledge page may
retrieve approved support articles but must never create a ticket. A ticket workspace may retrieve
the same evidence and offer a governed write, but only after server authorization and confirmation.

AI Fabric models that distinction with positions, modes, and orchestration policy. They are related,
but they are not interchangeable.

## Position

A position is application context. In the Support Knowledge Assistant, `knowledge` means the user is
reading help content and `ticket` means the user is working in a support-resolution surface. These
names belong to the application. Another app might use `catalog`, `checkout`, or `account`.

An application position is not an authorization claim. A browser saying `ticket` cannot grant itself
permission to write.

## Mode

A mode is a server-defined AI Fabric capability bundle. In this lesson, `support_assistant` enables
retrieval and disables actions. `support_resolver` enables retrieval and governed actions. Modes can
also constrain vector spaces, document budgets, context size, suggestions, deep retrieval, and
planner behavior.

The server allowlists modes in `ai.orchestration.modes`. With strict mode routing enabled, an unknown
mode fails visibly. It does not quietly become a default.

## The Ownership Boundary

Describe this diagram on screen:

```text
browser position or approved mode
          |
          v
application position resolver
          |
          v
OrchestrationContext
          |
          v
AI Fabric policy resolution
          |
          +-- retrieval gate and budgets
          +-- action gate
          +-- suggestions and planning policy
          |
          v
identity + tenant + action authorization + confirmation
```

The application maps its own position vocabulary. AI Fabric Core stays application-neutral and
enforces the selected server mode. Security controls remain separate.

## Precedence

If a request explicitly asks for an approved mode, the application preserves it and Core validates
it. If no mode is supplied, a known position may map to a mode. If neither is supplied, the server
default applies. Unknown positions fail at the application boundary when strict position routing is
enabled.

This precedence makes diagnostics understandable. Policy metadata can report the effective mode,
position, source, action gate, retrieval gate, and RAG budgets.

## Concrete Flow

Consider the message, "Create a ticket about missing recovery emails."

At position `knowledge`, the test provider emits a structured action intent. AI Fabric still returns
`CLARIFICATION_REQUIRED` with reason `ACTIONS_DISABLED_BY_POLICY`. The model cannot override the
mode.

At position `ticket`, the application maps to `support_resolver`. AI Fabric discovers the typed
action, resolves trusted account context, and returns `CONFIRMATION_REQUIRED`. The action still has
not executed. Confirmation and authorization remain mandatory.

## Incorrect Architecture

An incorrect design accepts arbitrary mode names from JavaScript and maps names like `admin` to more
powerful behavior. A second incorrect design puts "never execute actions" in a prompt while leaving
actions enabled. Both assign authority to untrusted or probabilistic inputs.

The correct design uses a small allowlisted application map, strict Core mode routing, deterministic
capability flags, authenticated context, and action-level policy.

## Visible Failure

If the browser sends `untrusted_admin`, Core returns an unsupported-mode error. If it sends an unknown
position without a mode, the application returns HTTP 400. Neither failure is hidden by fallback.

## Lab Bridge

In PROD-02 you will configure both modes, implement `SupportModeResolver`, extend the request with
bounded optional routing fields, and prove every branch through the real orchestration pipeline.
You will finish by rerunning all earlier action and memory tests so new policy does not erase existing
framework behavior.
