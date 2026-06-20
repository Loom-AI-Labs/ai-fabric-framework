# Orchestration Pipeline Architecture

## Runtime Shape

The core orchestration engine is `DefaultOrchestrationPipeline`.

It receives:

- a non-blank user query;
- a validated `OrchestrationContext`;
- a Spring-injected list of `PipelineStep` implementations.

The pipeline sorts steps by `PipelineStep#getOrder()` and runs them in ascending order. Each step
receives an immutable `PipelineContext` and returns a new context when it adds state.

## Control Flow

Steps should:

- keep one responsibility;
- return a new context via `toBuilder()` or helper methods;
- call `PipelineContext#terminate(...)` for controlled fail-closed exits;
- throw only unexpected exceptions.

The pipeline catches unexpected step exceptions and converts them to a top-level error result:

```text
Pipeline step failed: <StepName>
```

After each step, the pipeline continues checking `shouldSkip(...)`. The default skip behavior avoids
running later steps after early termination.

## Result Finalization

At the end of execution, the pipeline:

- returns `context.intentResult`;
- creates an error if no result was produced;
- attaches timing metadata with total duration, early-termination flag, and step durations.

The final result is expected to have passed through normalization, metadata enrichment, smart
suggestions, response sanitization, and history persistence unless an earlier fail-closed step
terminated the pipeline.

## Design Principles

- Security and access decisions happen before LLM intent handling.
- LLM output is constrained by validators and normalization, not trusted directly.
- Provider variability is absorbed before the public result contract is returned.
- Debug output is bounded and redacted by design.

## Test Evidence

Current focused tests:

- `DefaultOrchestrationPipelineTest`
- `PipelineContextTest`
- individual `*StepTest` classes under `ai.fabric.intent.orchestration.pipeline.steps`

Run:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest=DefaultOrchestrationPipelineTest,PipelineContextTest test
```
