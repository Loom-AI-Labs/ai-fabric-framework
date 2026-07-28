package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentExtractionStepProgressiveEngineTest {

    @Test
    void shouldUseProgressiveEngineWhenAvailableAndAttachDiagnostics() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine = mock(ProgressiveIntentExtractionEngine.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            new ProgressiveIntentExtractionEngine.ExtractionOutput(response, Map.of("extractionPath", "compound"));

        when(engine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(output);

        IntentExtractionStep step = new IntentExtractionStep(extractor, provider);
        PipelineContext ctx = PipelineContext.from("q", OrchestrationContext.forUser("user"));

        PipelineContext updated = step.process(ctx);

        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().hasIntents()).isTrue();
        assertThat(updated.getMetadata()).containsKey("extractionDiagnostics");
        verify(extractor, never()).extract(any(IntentExtractionInput.class), any(OrchestrationContext.class));
    }

    @Test
    void shouldAttachSinglePassDiagnosticsWhenProgressiveEngineUnavailable() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        when(extractor.extractWithTrace(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(new IntentQueryExtractor.ExtractionTrace(response, 184L, 151L, "gpt-5.4-nano"));

        IntentExtractionStep step = new IntentExtractionStep(extractor, provider);
        PipelineContext ctx = PipelineContext.from("q", OrchestrationContext.forUser("user"));

        PipelineContext updated = step.process(ctx);

        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().hasIntents()).isTrue();
        assertThat(updated.getMetadata())
            .containsEntry("extractionDiagnostics", Map.of(
                "extractionPath", "single_pass",
                "extractionAttempts", 1,
                "llmCalls", 1,
                "processingTimeMs", 184L,
                "providerProcessingTimeMs", 151L,
                "model", "gpt-5.4-nano",
                "attempts", List.of(Map.of(
                    "strategy", "single_pass",
                    "success", true,
                    "llmCalls", 1,
                    "processingTimeMs", 184L,
                    "providerProcessingTimeMs", 151L,
                    "model", "gpt-5.4-nano"
                ))
            ));
        verify(extractor, never()).extract(any(IntentExtractionInput.class), any(OrchestrationContext.class));
    }

    @Test
    void suppliesServerResolvedIdentityForApplicationIntentExtraction() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine =
            mock(ProgressiveIntentExtractionEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider =
            mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder()
                    .type(IntentType.INFORMATION)
                    .intent("account_readiness")
                    .build()
            ))
            .build();
        when(engine.extract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(
            new ProgressiveIntentExtractionEngine.ExtractionOutput(
                response,
                Map.of()
            )
        );
        TrustedExecutionContext trusted = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "account-service",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "deployment-1",
            java.util.Set.of("specialist:account-resolver@1"),
            "correlation-1",
            Instant.parse("2026-07-28T10:00:00Z")
        );
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Inspect the account",
            OrchestrationContext.builder().build(),
            trusted,
            ConversationPersistencePolicy.NEVER
        ));

        new IntentExtractionStep(extractor, provider).process(context);

        ArgumentCaptor<IntentExtractionInput> input =
            ArgumentCaptor.forClass(IntentExtractionInput.class);
        verify(engine).extract(input.capture(), any(OrchestrationContext.class));
        assertThat(input.getValue().resolvedAuthContext().getSubjectId())
            .isEqualTo("account-42");
        assertThat(input.getValue().resolvedAuthContext().getCallerType())
            .isEqualTo("SERVICE");
    }
}
