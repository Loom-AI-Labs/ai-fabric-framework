package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationResolutionSupportTest {

    @Test
    void shouldResolvePositiveDecisionAndExposeDebugMetadata() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"decision\":\"POSITIVE\",\"confidence\":0.87}")
                .model("gpt-test")
                .build()
        );
        ConfirmationResolutionSupport support = newSupport(aiCoreService);

        ConfirmationResolutionSupport.ConfirmationResolutionOutcome outcome = support.resolve(
            "place_order",
            "Place this order?",
            "yes, go ahead",
            OrchestrationContext.forUser("user-1")
        );

        assertThat(outcome.decision()).isEqualTo(ConfirmationResolutionDecision.POSITIVE);
        assertThat(outcome.confidence()).isEqualTo(0.87d);
        assertThat(outcome.debugMetadata())
            .containsEntry("used", true)
            .containsEntry("action", "place_order")
            .containsEntry("decision", "POSITIVE")
            .containsEntry("model", "gpt-test");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.ORCHESTRATION));
        AIGenerationRequest request = requestCaptor.getValue();
        assertThat(request.getEntityType()).isEqualTo("confirmation");
        assertThat(request.getGenerationType()).isEqualTo("confirmation_resolution");
        assertThat(request.getMaxTokens()).isEqualTo(120);
        assertThat(request.getTemperature()).isEqualTo(0.0d);
        assertThat(request.getAuthContext().getSubjectId()).isEqualTo("user-1");
        assertThat(request.getPrompt()).contains("place_order").contains("yes, go ahead");
    }

    @Test
    void shouldFailClosedWhenLlmCallThrows() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenThrow(new IllegalStateException("offline"));
        ConfirmationResolutionSupport support = newSupport(aiCoreService);

        ConfirmationResolutionSupport.ConfirmationResolutionOutcome outcome = support.resolve(
            "place_order",
            "Place this order?",
            "yes",
            OrchestrationContext.forUser("user-1")
        );

        assertThat(outcome.decision()).isEqualTo(ConfirmationResolutionDecision.UNKNOWN);
        assertThat(outcome.confidence()).isZero();
        assertThat(outcome.debugMetadata()).containsEntry("error", "llm_call_failed");
    }

    private ConfirmationResolutionSupport newSupport(AICoreService aiCoreService) {
        return new ConfirmationResolutionSupport(
            aiCoreService,
            providerOf(new ObjectMapper()),
            new PromptTemplateResolver(
                new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
                new PromptBundleProperties()
            ),
            new PromptRenderer()
        );
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
