package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.PostActionGenerationSupport.ResolvedPostActionGeneration;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreparedActionEvidenceBoundaryTest {
    private static final String RAW_CANARY = "RAW_ONLY_SECRET_CANARY";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedFactsAreExclusiveForObservationsAndGeneration(boolean forced) {
        var fixture = fixture();
        Map<String, Object> prepared = Map.of(
            "data", Map.of("products", List.of(Map.of("price", 499))),
            "truncated", true
        );
        when(fixture.handler().buildPostActionLlmFacts(any(), any())).thenReturn(Optional.of(prepared));
        when(fixture.ai().generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The price is 499.").build());

        var observation = fixture.support().buildReadActionExecutionObservation(
            "catalogue", fixture.handler(), fixture.result(), mock(ActionContext.class));
        assertThat(observation).isPresent();
        assertThat(observation.get().get("evidenceSummary").toString())
            .contains("499", "truncated").doesNotContain(RAW_CANARY, "actionResultData");

        var outcome = generate(fixture, forced);
        assertThat(outcome.summary()).isEqualTo("The price is 499.");
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(fixture.ai()).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getPrompt()).contains("499", "truncated")
            .doesNotContain(RAW_CANARY, "actionResultData");
        assertThat(fixture.result().getData().toMap()).containsEntry("apiKey", RAW_CANARY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-selection", "absent", "failure"})
    void unavailablePreparedFactsNeverFallBackToRawOutput(String mode) {
        var fixture = fixture();
        if (mode.equals("failure")) {
            when(fixture.handler().buildPostActionLlmFacts(any(), any()))
                .thenThrow(new IllegalStateException("projection unavailable"));
        } else {
            when(fixture.handler().buildPostActionLlmFacts(any(), any()))
                .thenReturn(mode.equals("absent") ? Optional.empty() : Optional.of(Map.of()));
        }
        assertThat(fixture.support().buildReadActionExecutionObservation(
            "catalogue", fixture.handler(), fixture.result(), mock(ActionContext.class))).isEmpty();

        var outcome = generate(fixture, true);
        assertThat(outcome.summary()).isNull();
        assertThat(outcome.metadata()).containsEntry("used", false);
        assertThat(fixture.result().isSuccess()).isTrue();
        assertThat(fixture.result().getData().toMap()).containsEntry("receiptId", "completed-1");
        verifyNoInteractions(fixture.ai());
    }

    private PostActionGenerationSupport.PostActionGenerationOutcome generate(Fixture fixture, boolean forced) {
        var context = OrchestrationContext.forUser("user-1");
        return fixture.support().maybeGeneratePostActionSummary(
            "catalogue", fixture.handler(), Intent.builder().type(IntentType.ACTION).action("catalogue").build(),
            fixture.result(), context, PipelineContext.from("Show the price", context), Map.of(),
            new ResolvedPostActionGeneration(true, null, forced));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        var ai = mock(AICoreService.class);
        var handler = mock(AIActionHandler.class);
        ObjectProvider<ObjectMapper> mapper = mock(ObjectProvider.class);
        when(mapper.getIfAvailable()).thenReturn(new ObjectMapper());
        var properties = new PostActionGenerationProperties();
        properties.setEnabled(true);
        var resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties());
        var support = new PostActionGenerationSupport(ai, new RelationshipQueryPostActionGenerationProperties(),
            properties, mapper, resolver, new PromptRenderer());
        var result = ActionResult.builder().success(true).message("Action executed.")
            .data(ActionResultContracts.object(Map.of(
                "apiKey", RAW_CANARY, "unselected", RAW_CANARY, "receiptId", "completed-1"))).build();
        return new Fixture(ai, handler, support, result);
    }

    private record Fixture(AICoreService ai, AIActionHandler handler,
                           PostActionGenerationSupport support, ActionResult result) { }
}
