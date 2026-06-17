# RAG Orchestrator User Guide

## Overview

The RAG Orchestrator coordinates retrieval-augmented generation with security, access control, PII detection, and compliance gates. It uses a **pipeline architecture** where each concern is handled by an independent, composable step.

## Architecture

### Pipeline Pattern

The orchestrator delegates all processing to a `Pipeline` composed of ordered `PipelineStep` implementations:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         RAGOrchestrator                              │
│                    (delegates to Pipeline)                           │
└─────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DefaultOrchestrationPipeline                      │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │  Security    │→ │AccessControl │→ │PIIDetection  │→ ...          │
│  │  (Order 10)  │  │  (Order 20)  │  │  (Order 30)  │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
│                                                                      │
│  ... → ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│        │SmartSuggest  │→ │ResponseSanit │→ │  History     │         │
│        │  (Order 80)  │  │  (Order 90)  │  │ (Order 100)  │         │
│        └──────────────┘  └──────────────┘  └──────────────┘         │
└─────────────────────────────────────────────────────────────────────┘
```

### Pipeline Steps (Execution Order)

| Order | Step | Responsibility |
|-------|------|----------------|
| 10 | `SecurityAnalysisStep` | Analyzes request for security threats; terminates if blocked |
| 20 | `AccessControlStep` | Verifies user access permissions; fail-closed on denial |
| 30 | `PIIDetectionStep` | Detects and processes PII in input query |
| 40 | `ComplianceCheckStep` | Validates compliance requirements; terminates if non-compliant |
| 50 | `IntentExtractionStep` | Extracts user intent(s) from the query |
| 60 | `IntentHandlingStep` | Routes and processes intents (ACTION, INFORMATION, etc.), using the processed query from `PIIDetectionStep` |
| 70 | `MetadataBuildingStep` | Builds request metadata for the result |
| 80 | `SmartSuggestionsStep` | Generates smart suggestions based on next-step recommendations |
| 90 | `ResponseSanitizationStep` | Sanitizes response and merges PII detection info |
| 100 | `HistoryPersistenceStep` | Records the interaction in intent history |

### Core Components

#### PipelineStep Interface

```java
public interface PipelineStep {
    PipelineContext process(PipelineContext context);
    String getStepName();
    int getOrder();  // Lower = earlier execution
    boolean shouldSkip(PipelineContext context);  // Skip if terminated
}
```

#### PipelineContext

Immutable data carrier that flows through all steps:

```java
PipelineContext context = PipelineContext.builder()
    .originalQuery("Cancel my subscription")
    .orchestrationContext(ctx)
    .requestId("req-123")
    .processedQuery("Cancel my subscription")  // After PII processing
    .detectedPiiTypes(List.of())
    .intentResponse(multiIntentResponse)
    .intentResult(orchestrationResult)
    .metadata(Map.of())
    .build();
```

#### Pipeline Interface

```java
public interface Pipeline {
    OrchestrationResult execute(String query, OrchestrationContext context);
    List<PipelineStep> getSteps();
}
```

## Core Entry Point

```java
OrchestrationResult orchestrate(String query, OrchestrationContext context)
```

### OrchestrationContext Checklist

- `userId` for authenticated users, `sessionId` for anonymous (one required).
- Optional: `ipAddress`, `userAgent`, `locale`, `metadata` (tier, device, referrer, etc.).
- `requestId` auto-generates if absent; call `context.validate()` to enforce identifiers.
- Factory helpers: `forUser(userId)`, `forSession(sessionId)`, `anonymous()`, `forTest()`.

### Behavior Integration (Optional)

- Core defines SPI `BehaviorContextProvider`; behavior module or custom apps can implement it.
- If present, behavior context is added to `SystemContext` and prompt (tone/recommendations).
- `userId` is opaque; non-UUID IDs are supported.

## Execution Flow

### Security Gate (Steps 10-40)

1. **SecurityAnalysisStep**: Calls `AISecurityService.analyzeRequest` - terminates if `shouldBlock=true`
2. **AccessControlStep**: Calls `AIAccessControlService.checkAccess` - fail-closed if `accessGranted=false`
3. **PIIDetectionStep**: Calls `PIIDetectionService.detectAndProcess` - updates processed query
4. **ComplianceCheckStep**: Calls `AIComplianceService.checkCompliance` - terminates if `overallCompliant=false`

### Intent Processing (Steps 50-60)

5. **IntentExtractionStep**: Calls `IntentQueryExtractor.extract(query, context)` - terminates if no intents
6. **IntentHandlingStep**: Routes based on intent type, prioritizing `processedQuery` from `PIIDetectionStep` (or `optimizedQuery` when provided) before the raw intent text:
   - `ACTION`: Routed to `AIActionRegistry` (actions declared via `@AIAction`; access/confirmation is action-defined)
   - `INFORMATION`: Performs RAG via `RAGService.performRag()` or `performRAGQuery()` with the already-redacted query; RAG no longer runs PII detection
   - `COMPOUND`: Processes multiple intents sequentially
   - `OUT_OF_SCOPE`: Returns guidance message

### Enrichment (Steps 70-100)

7. **MetadataBuildingStep**: Builds metadata (requestId, sessionId, intentsCount, etc.)
8. **SmartSuggestionsStep**: If next-step recommendation exists with sufficient confidence, performs secondary RAG
9. **ResponseSanitizationStep**: Sanitizes response, merges input/output PII detection
10. **HistoryPersistenceStep**: Calls `IntentHistoryService.recordIntent` (non-fatal on failure)

## Key Behaviors

### Fail-Closed Security

Security steps terminate the pipeline on any denial:

```java
// AccessControlStep - fail-closed pattern
if (!response.isAccessGranted()) {
    return context.terminate(OrchestrationResult.builder()
        .type(OrchestrationResultType.ACCESS_DENIED)
        .success(false)
        .message(ACCESS_DENIED_MESSAGE)
        .build());
}
```

### Early Termination

Any step can terminate the pipeline by calling `context.terminate(result)`:

```java
// Terminates pipeline and returns result immediately
return context.terminate(OrchestrationResult.builder()
    .type(OrchestrationResultType.SECURITY_BLOCKED)
    .success(false)
    .build());
```

### Anonymous User Handling

- Anonymous actions are blocked at `IntentHandlingStep`
- Information queries allowed with session tracking
- Security/AC metadata include authentication flag, sessionId, IP, UA

## Example Usage

### Authenticated User

```java
OrchestrationContext ctx = OrchestrationContext.builder()
    .userId("user-123")
    .sessionId("sess-abc")           // optional
    .ipAddress("203.0.113.10")
    .userAgent("Mozilla/5.0")
    .locale(Locale.US)
    .metadata(Map.of("tier", "gold"))
    .build();

OrchestrationResult result = orchestrator.orchestrate("Cancel my plan", ctx);
```

### Anonymous User (Information-Only)

```java
OrchestrationContext anon = OrchestrationContext.forSession("sess-xyz");
OrchestrationResult info = orchestrator.orchestrate("Show refund policy", anon);
```

### Programmatic Pipeline Access

```java
// Get pipeline steps
List<PipelineStep> steps = pipeline.getSteps();
steps.forEach(step -> log.info("Step: {} (order={})", 
    step.getStepName(), step.getOrder()));
```

## Extension Points

### Adding Custom Pipeline Steps

Implement `PipelineStep` and register as a Spring `@Component`:

```java
@Component
public class CustomValidationStep implements PipelineStep {
    
    private static final String STEP_NAME = "CustomValidation";
    private static final int STEP_ORDER = 45;  // Between compliance and intent
    
    @Override
    public String getStepName() { return STEP_NAME; }
    
    @Override
    public int getOrder() { return STEP_ORDER; }
    
    @Override
    public PipelineContext process(PipelineContext context) {
        // Custom validation logic
        if (!isValid(context)) {
            return context.terminate(OrchestrationResult.builder()
                .type(OrchestrationResultType.ERROR)
                .message("Validation failed")
                .build());
        }
        return context;  // Continue to next step
    }
}
```

### Adding Action Handlers

Register via `@AIAction` (greenfield):

```java
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;

@AIAction(
    name = "cancel_subscription",
    description = "Cancel an active subscription",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class CancelSubscriptionAction {

    @ActionExecute
    public ActionResult execute(@Param(required = true) String subscriptionId, ActionContext ctx) {
        // Implementation
        return ActionResult.builder().success(true).message("Cancelled.").build();
    }
}
```

### Behavior Context Provider

Implement `BehaviorContextProvider` SPI to enrich prompts:

```java
@Component
public class CustomBehaviorProvider implements BehaviorContextProvider {
    @Override
    public Optional<BehaviorContext> getContext(String userId) {
        // Return user behavior context
    }
}
```

### Configuration Properties

```yaml
ai:
  infrastructure:
    smart-suggestions:
      enabled: true
      min-confidence: 0.7
      primary-confidence: 0.85
      retrieval-limit: 5
      retrieval-threshold: 0.7
    
    pii-detection:
      enabled: true
      detection-direction: INPUT_OUTPUT  # INPUT_ONLY, OUTPUT_ONLY, INPUT_OUTPUT
```

## Testing

### Unit Testing Steps

```java
@Test
void shouldTerminateOnSecurityBlock() {
    when(securityService.analyzeRequest(any()))
        .thenReturn(AISecurityResponse.builder()
            .shouldBlock(true)
            .build());
    
    PipelineContext result = securityStep.process(context);
    
    assertThat(result.isShouldTerminate()).isTrue();
    assertThat(result.getIntentResult().getType())
        .isEqualTo(OrchestrationResultType.SECURITY_BLOCKED);
}
```

### Integration Testing

```java
@Test
void shouldExecuteFullPipeline() {
    OrchestrationContext ctx = OrchestrationContext.forUser("test-user");
    OrchestrationResult result = orchestrator.orchestrate("What is your refund policy?", ctx);
    
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
}
```

### Test Context Helper

```java
// Use forTest() for isolated unit tests
OrchestrationContext testCtx = OrchestrationContext.forTest();
```

## Deprecated APIs

The following signatures are deprecated and will be removed:

| Deprecated | Replacement |
|------------|-------------|
| `orchestrate(String, String)` | `orchestrate(String, OrchestrationContext)` |
| `IntentQueryExtractor.extract(String, String)` | `extract(String, OrchestrationContext)` |
| `SystemContextBuilder.buildContext(String)` | Use context-aware variant |
| `EnrichedPromptBuilder.buildSystemPrompt(String)` | Use context-aware variant |

## Migration from Legacy Orchestrator

If you were directly injecting services into `RAGOrchestrator`:

**Before (Legacy):**
```java
// Old approach - directly using services
@Autowired RAGOrchestrator orchestrator;
// Orchestrator internally managed 12 dependencies
```

**After (Pipeline):**
```java
// New approach - pipeline-based
@Autowired RAGOrchestrator orchestrator;
// Orchestrator now delegates to Pipeline with composable steps
// Same public API, cleaner internals
```

The public API remains unchanged. Internal refactoring provides:
- Better testability (mock individual steps)
- Easier extension (add custom steps)
- Clearer separation of concerns
- Improved observability (step-level metrics)
