# Spring AI Provider Integration Guide

This guide covers the AI Fabric Spring AI provider module:

- `ai-fabric-provider-spring-ai`
- OpenAI, Azure OpenAI, Anthropic, Google Gemini chat through Spring AI
- OpenAI, Azure OpenAI, Google Gemini, and optional Spring AI Transformers ONNX embeddings
- request-scoped Spring AI advisors
- optional AI Fabric action tool-calling bridge
- redacted Spring AI observation diagnostics

AI Fabric uses Spring AI for commodity model execution. AI Fabric still owns provider selection,
fallback, governance, transient-file safety, action authorization, RAG policy, and response
metadata.

## Module

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
```

Select providers with the normal AI Fabric properties:

```yaml
ai:
  providers:
    llm-provider: openai
    embedding-provider: openai
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
      embedding-model: text-embedding-3-small
```

## Execution Path

```text
AIGenerationRequest
  -> AIProviderManager selects the AI Fabric provider
  -> SpringAiModelResolver resolves model/options/endpoints
  -> SpringAiChatClientFactory builds a Spring AI ChatClient
  -> optional request advisors and action tool callbacks attach
  -> Spring AI executes the provider call
  -> AI Fabric maps the response, usage, metadata, status, and safety evidence
```

`SpringAiChatClientFactory` applies registered `ChatClientBuilderCustomizer` beans and the
available `ObservationRegistry`. This keeps Spring AI observability/customization paths available
without exposing raw Spring AI clients through AI Fabric's public SPI.

`SpringAiModelResolver` also passes the same application `ObservationRegistry` into Spring AI chat
and embedding model instances it builds dynamically. This covers request-scoped endpoint/model
overrides as well as the default cached model path.

## Redacted Observation Diagnostics

When an application provides an `ObservationRegistry`, the provider auto-configures
`SpringAiObservationHandler` and `SpringAiObservationDiagnostics`.

The bridge listens to Spring AI observations for:

- ChatClient calls
- chat model calls
- embedding model calls
- advisor execution
- tool calling

The diagnostics snapshot records only AI Fabric-safe operational fields:

- observation type, provider, operation, component name, and streaming flag
- started/completed/error counts
- total, average, and maximum duration
- prompt/completion/total token counters when Spring AI exposes usage
- last error type, not the error message

It deliberately does not copy:

- prompt text
- completion text
- tool call arguments
- tool call results
- tool call ids
- hidden action context
- transient file URLs

Use the diagnostics bean from trusted server-side code or an operator endpoint that applies your
normal admin authorization:

```java
import ai.fabric.provider.springai.SpringAiObservationDiagnostics;

import java.util.Map;

public class AiOpsDiagnostics {

    private final SpringAiObservationDiagnostics diagnostics;

    public AiOpsDiagnostics(SpringAiObservationDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public Map<String, Object> springAiSnapshot() {
        return diagnostics.snapshot();
    }
}
```

## Request-Scoped Advisors

Use request-scoped advisors when server-side application code needs one model call to use a Spring
AI advisor, such as a tenant-specific redaction advisor, prompt augmentation advisor, memory-window
advisor, or diagnostics advisor.

Attach advisors with `SpringAiRequestAdvisorSupport`. The helper stores actual `Advisor` instances
in `AIGenerationRequest.parameters`, so it is intended for trusted server-side Java code. String
advisor names or JSON input do not activate advisors.

```java
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.springai.SpringAiRequestAdvisorSupport;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Map;

public class TenantAssistant {

    private final AICoreService aiCoreService;
    private final Advisor tenantRedactionAdvisor;

    public TenantAssistant(AICoreService aiCoreService, Advisor tenantRedactionAdvisor) {
        this.aiCoreService = aiCoreService;
        this.tenantRedactionAdvisor = tenantRedactionAdvisor;
    }

    public AIGenerationResponse answer(String tenantId, String prompt) {
        Map<String, Object> parameters = SpringAiRequestAdvisorSupport.requestParameters(
            Map.of("tenantId", tenantId),
            List.of(tenantRedactionAdvisor)
        );

        return aiCoreService.generateContent(AIGenerationRequest.builder()
            .prompt(prompt)
            .parameters(parameters)
            .build());
    }
}
```

The Spring AI provider attaches request advisors through
`ChatClient.ChatClientRequestSpec.advisors(List<Advisor>)`.

Response metadata includes only safe advisor evidence:

- `springAiRequestAdvisors`: advisor count
- `springAiRequestAdvisorNames`: nonblank advisor names

The provider does not copy request parameters, prompt content, completion content, tool arguments,
or hidden action context into response metadata.

## Action Tool Calling

The Spring AI provider can also expose selected AI Fabric actions as provider-native tool callbacks.
Use `AIActionToolCallbackFactory.requestParameters(actionContext, actionNames)` for this path.

See:

- `../actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`

Advisor attachment and action tool attachment are independent. A request may use either or both:

```java
Map<String, Object> parameters = SpringAiRequestAdvisorSupport.requestParameters(
    AIActionToolCallbackFactory.requestParameters(actionContext, List.of("lookup_order")),
    List.of(tenantRedactionAdvisor)
);
```

The model may request a tool call, but AI Fabric still validates action access, hidden parameters,
confirmation gates, and connector policy before execution.

### Read-Only Commerce Example

For a customer-facing commerce assistant, expose only read actions first:

```java
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.intent.orchestration.OrchestrationContext;

import java.util.List;
import java.util.Map;

public class CommerceReadToolAssistant {

    private final AICoreService aiCoreService;

    public CommerceReadToolAssistant(AICoreService aiCoreService) {
        this.aiCoreService = aiCoreService;
    }

    public AIGenerationResponse answer(String shopperId, String shopperSessionId, String prompt) {
        ActionContext actionContext = new ActionContext(
            OrchestrationContext.forUser(shopperId),
            null,
            Map.of("shopperSessionId", shopperSessionId)
        );

        return aiCoreService.generateContent(AIGenerationRequest.builder()
            .prompt(prompt)
            .parameters(AIActionToolCallbackFactory.requestParameters(
                actionContext,
                List.of("get_order_details", "list_orders", "view_cart")
            ))
            .build());
    }
}
```

In this example, the model can supply public tool arguments such as `orderNumberOrId`, but hidden
runtime values such as `shopperSessionId` come only from trusted server-side `ActionContext`
parameters. Model-supplied hidden values are ignored.

Executable coverage:

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiReadOnlyActionToolExampleTest.java`

## Safety Boundary

- Request advisors are trusted Java objects, not user-supplied names.
- Invalid advisor parameter values are ignored instead of converted to advisors.
- Action tool callbacks require an explicit trusted `ActionContext`.
- Observation diagnostics record counters, timing, provider/operation names, component names, token
  totals, and error type only.
- Observation diagnostics do not record prompt/completion text, tool arguments, tool results, tool
  call ids, hidden action context, or transient file URLs.
- Transient file URL policy remains AI Fabric's responsibility and fails closed for unsupported
  provider/media combinations.
- Do not enable prompt/completion/tool-argument logging in advisors unless your application has
  explicit redaction and retention policy for that data.

## Verification

Focused provider verification:

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl providers/ai-fabric-provider-spring-ai -am \
  test \
  -Dtest='SpringAiProviderAdapterTest,SpringAiObservationDiagnosticsTest,SpringAiReadOnlyActionToolExampleTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Release verification should also run the broader framework test suite and `git diff --check`.
