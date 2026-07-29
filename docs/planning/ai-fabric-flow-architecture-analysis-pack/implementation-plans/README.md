# Agentic Enablement Implementation Plans

This directory contains executable delivery plans derived from the architecture proposal and
verdicts in the parent directory.

Keep architecture, product analysis, and case verdicts at the analysis-pack root. Keep
implementation sequencing, file-level changes, test matrices, release gates, and delivery
checklists here.

## Plan Index

| Plan | Status | Scope |
| --- | --- | --- |
| [0001 - Agentic enablement P0/P1](./0001-agentic-enablement-p0-p1-implementation-plan.md) | Implemented and approved | Compatibility foundation, governed capabilities/actions, optional execution module, typed application calls, and the first Agentic AI Action Resolver proof |
| [0001 - P0/P1 approval scorecard](./0001-agentic-enablement-p0-p1-approval-scorecard.md) | Approved | Test evidence, real-provider matrix, security verdict, and explicit read-only release decision |
| [0001 - Module and migration guide](./0001-agentic-enablement-module-and-migration-guide.md) | Complete | Dependency, adoption, compatibility, evidence, and rollback guidance |
| [0001 - Security and troubleshooting](./0001-agentic-enablement-security-and-troubleshooting-guide.md) | Complete | Trust boundaries, failure semantics, diagnostics, and operational guidance |
| [0001 - Release impact](./0001-agentic-enablement-release-impact.md) | Proposed release documentation | Additive artifact and compatibility impact for a proposed `0.5.0` |
| [0002 - Governed specialist write and receipt](./0002-governed-specialist-write-and-receipt-implementation-plan.md) | Implemented and verified; not released | Durable, profile-pinned confirmation receipts and one governed specialist WRITE |
| [0003 - Configurable specialist manifest runtime](./0003-configurable-specialist-manifest-runtime-implementation-plan.md) | Implemented and verified; not released | Startup-loaded JSON Schema-backed specialists, exact-version resources, governed receipt pinning, and Loom AI configuration authoring |
| [0004 - Typed specialist input wait and safe resume](./0004-typed-specialist-input-wait-and-safe-resume-implementation-plan.md) | Implemented and verified; not released | Typed `NeedsUserInput`, authority-scoped same-process resume, bounded pending state, and one billing-resolution proof |
| [0005 - Fixed sequential specialist plans](./0005-fixed-sequential-specialist-plan-implementation-plan.md) | Implemented and verified; not released | Immutable exact-version plans, registered typed mappings and aggregation, independent specialist execution, process-local checkpoints, and a two-step Agentic Resolver proof |

## Plan Rules

1. Number plans in delivery order.
2. State the reviewed framework commit and release baseline.
3. Link every material change to current code evidence.
4. Separate required work from later options.
5. Give each implementation slice its own tests and acceptance gate.
6. Do not add empty interfaces, placeholder implementations, disabled tests, or speculative public
   contracts.
7. Preserve existing behavior with regression tests before changing shared execution paths.
8. Run unit tests normally. Do not use `-DskipTests` in implementation or release verification.
9. Keep credentials and provider keys out of plans, source control, test output, and logs.
10. Mark a plan complete only after its packaged-runtime and release gates pass.

## Source Documents

- [Specialist-defined agentic enablement proposal](../Full-Proposal/Product-evolution-proposal.md)
- [Agentic enablement portfolio verdict](../AI_FABRIC_AGENTIC_ENABLEMENT_VERDICT.md)
- [Agentic product verdict and delivery strategy](../AI_FABRIC_AGENTIC_PRODUCT_VERDICT_AND_DELIVERY_STRATEGY.md)
- [Flow analysis documents](../ai-fabric-flow-analysis-documents/README.md)
