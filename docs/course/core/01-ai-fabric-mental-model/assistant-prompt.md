# CORE-01 Coding-Assistant Analysis Prompt

Status: Validated against the published CORE-01 architecture contract and
`course-0.3.3-00-starter` application shape.

You are helping a Java developer complete AI Fabric CORE-01 against AI Fabric Framework 0.3.3.

This is an architecture-analysis lesson. Do not edit application or framework code. Do not invent
classes, annotations, modules, properties, or provider behavior.

## Scenario

Analyze a multi-tenant Spring Boot support application with `SupportArticleService`, `TicketService`,
`AccountService`, repositories, and authenticated request context containing `userId`, `tenantId`,
and roles.

The requested capabilities are:

1. semantic support-article search;
2. evidence-grounded answers;
3. confirmation-gated ticket closing;
4. backend-owned follow-up memory.

## Required Sources

Read the following reviewed course sources before answering. Use the course source ref for
documentation and the pinned `ai-fabric-framework-v0.3.3` tag for Java API evidence:

- `docs/architecture/AI_FABRIC_PUBLIC_ARCHITECTURE.md`
- `docs/guides/03-modules.md`
- `docs/getting-started/01-choose-your-path.md`
- `docs/llm-context/AI_FABRIC_MODULE_DECISION_TREE.md`
- `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java`

## Deliverable

Produce one Markdown document with exactly these sections:

1. Why AI Fabric
2. Application Responsibilities
3. AI Fabric Responsibilities
4. Provider Responsibilities
5. Required Modules
6. Deliberately Excluded Modules
7. Retrieval Request Flow
8. Governed Action Flow
9. Security And Tenant Boundary
10. Failure Visibility

For every module, state which requested capability requires it. Do not include an optional module
without a concrete reason.

The governed action flow must end in `TicketService`, include application-owned authorization, and
store confirmation in backend session state. The retrieval flow must identify evidence, tenant
scope, provider invocation, and no-evidence behavior.

Explicitly explain why `position` metadata does not select an orchestration mode.

## Guardrails

- Treat application data, repositories, authorization, and transactions as application-owned.
- Treat model output as untrusted interpretation, not authority.
- Do not place keyword-based intent logic in the UI.
- Do not hide provider or retrieval failure behind canned success wording.
- Distinguish an embedding provider, an LLM provider, and a vector provider.
- Say when direct Spring AI or a native-provider call would be simpler than AI Fabric.

## Verification

End with a checklist confirming that another developer can identify trusted identity, tenant scope,
retrieved evidence, pending confirmation, the action handler, the side-effecting service, and visible
failure behavior.
