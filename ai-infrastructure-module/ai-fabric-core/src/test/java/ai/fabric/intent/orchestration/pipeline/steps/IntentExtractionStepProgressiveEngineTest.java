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
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
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
    void specialistRequestTerminatesOnVisibleProviderFailure() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine =
            mock(ProgressiveIntentExtractionEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider =
            mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);

        MultiIntentResponse fallback = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder()
                    .type(IntentType.OUT_OF_SCOPE)
                    .intent("out_of_scope")
                    .build()
            ))
            .metadata(Map.of("fallback", true))
            .build();
        when(engine.extract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(
            new ProgressiveIntentExtractionEngine.ExtractionOutput(
                fallback,
                Map.of("extractionPath", "fallback"),
                new ProgressiveIntentExtractionEngine.ExtractionFailure(
                    "INTENT_PROVIDER_FAILED",
                    "The configured AI provider could not complete intent analysis.",
                    true
                )
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
            java.util.Set.of("specialist:account-resolver-read@1"),
            "correlation-1",
            Instant.parse("2026-07-28T10:00:00Z")
        );
        PipelineContext context = PipelineContext.from(
            new OrchestrationRequest(
                "Inspect the account",
                OrchestrationContext.builder().build(),
                trusted,
                ConversationPersistencePolicy.NEVER,
                null,
                null,
                null,
                OrchestrationRequestPurpose.SPECIALIST
            )
        );

        PipelineContext updated =
            new IntentExtractionStep(extractor, provider).process(context);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getErrorCode())
            .isEqualTo("INTENT_PROVIDER_FAILED");
        assertThat(updated.getEarlyTerminationResult().getMessage())
            .isEqualTo(
                "The configured AI provider could not complete intent analysis."
            );
        assertThat(updated.getMetadata())
            .containsEntry(
                "extractionDiagnostics",
                Map.of("extractionPath", "fallback")
            );
        assertThat(updated.getMetadata().toString())
            .doesNotContain("API key", "provider call failed");
    }

    @Test
    void generalRequestRetainsFallbackCompatibility() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine =
            mock(ProgressiveIntentExtractionEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider =
            mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);

        MultiIntentResponse fallback = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder()
                    .type(IntentType.OUT_OF_SCOPE)
                    .intent("out_of_scope")
                    .build()
            ))
            .metadata(Map.of("fallback", true))
            .build();
        when(engine.extract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(
            new ProgressiveIntentExtractionEngine.ExtractionOutput(
                fallback,
                Map.of("extractionPath", "fallback"),
                new ProgressiveIntentExtractionEngine.ExtractionFailure(
                    "INTENT_PROVIDER_FAILED",
                    "The configured AI provider could not complete intent analysis.",
                    true
                )
            )
        );

        PipelineContext updated = new IntentExtractionStep(
            extractor,
            provider
        ).process(
            PipelineContext.from(
                "q",
                OrchestrationContext.forUser("user")
            )
        );

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse()).isSameAs(fallback);
    }

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
    void shouldUseResolvedActionDraftIntentWithoutGeneralExtraction() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine =
            mock(ProgressiveIntentExtractionEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider =
            mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("update_address")
                .actionParams(Map.of("state", "Bristol"))
                .build()))
            .build();
        PipelineContext context = PipelineContext.from(
            "The state is Bristol.",
            OrchestrationContext.forUser("user-1")
        ).toBuilder()
            .actionDraftIntentResponse(response)
            .build();

        PipelineContext updated =
            new IntentExtractionStep(extractor, provider).process(context);

        assertThat(updated.getIntentResponse()).isSameAs(response);
        assertThat(updated.getMetadata()).containsEntry(
            "extractionDiagnostics",
            Map.of(
                "extractionPath", "action_draft_continuation",
                "extractionAttempts", 1,
                "llmCalls", 1
            )
        );
        verify(engine, never()).extract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        );
        verify(extractor, never()).extractWithTrace(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        );
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
