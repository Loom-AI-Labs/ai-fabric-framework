package ai.fabric.intent.orchestration.information;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.action.*;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadActionAuthenticationPlanningTest {
    @ParameterizedTest
    @ValueSource(strings = {"REQUEST_AUTHENTICATION", "EXECUTE_READ_ACTIONS", "EXECUTE_READ_ACTIONS_AND_RAG"})
    void selectedProtectedReadRequestsAuthenticationWithoutResolvingOrExecutingIt(String decision) {
        var fixture = fixture(decision, "private_lookup", List.of("private_lookup", "public_guide"));
        var outcome = fixture.service.resolve(information(), OrchestrationContext.anonymous(),
            pipeline(OrchestrationContext.anonymous(), List.of("private_lookup", "public_guide")));
        assertThat(outcome.authenticationRequiredAction()).isEqualTo("private_lookup");
        assertThat(outcome.executedActions()).isEmpty();
        assertThat(outcome.useRag()).isFalse();
        verifyNoInteractions(fixture.handler);
        var capture = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(fixture.ai).generateContent(capture.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(capture.getValue().getPrompt()).contains("private_lookup", "authenticationRequired", "executableNow")
            .doesNotContain("actorId");
    }

    @Test
    void signedInReadExecutesNormallyWithTrustedIdentity() {
        var fixture = fixture("EXECUTE_READ_ACTIONS", "private_lookup", List.of("private_lookup"));
        var context = OrchestrationContext.forUser("verified-owner");
        var outcome = fixture.service.resolve(information(), context, pipeline(context, List.of("private_lookup")));
        assertThat(outcome.authenticationRequiredAction()).isNull();
        assertThat(outcome.executedActions()).hasSize(1);
        verify(fixture.handler).executeAction(eq(Map.of("actorId", "verified-owner")), any());
    }

    @Test
    void anonymousPublicReadDoesNotRequireSignIn() {
        var fixture = fixture("EXECUTE_READ_ACTIONS", "public_guide", List.of("public_guide"));
        var context = OrchestrationContext.anonymous();
        var outcome = fixture.service.resolve(information(), context, pipeline(context, List.of("public_guide")));
        assertThat(outcome.authenticationRequiredAction()).isNull();
        assertThat(outcome.executedActions()).hasSize(1);
        verify(fixture.handler).executeAction(eq(Map.of()), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"private_lookup", "hidden_lookup", "write_action"})
    void unlistedOrWriteCapabilityCannotBecomeAnAuthenticationRequest(String selected) {
        var fixture = fixture("REQUEST_AUTHENTICATION", selected, List.of("public_guide"));
        var context = OrchestrationContext.anonymous();
        var outcome = fixture.service.resolve(information(), context, pipeline(context, List.of("public_guide")));
        assertThat(outcome.authenticationRequiredAction()).isNull();
        assertThat(outcome.executedActions()).isEmpty();
        verifyNoInteractions(fixture.handler);
        var capture = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(fixture.ai).generateContent(capture.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(capture.getValue().getPrompt()).doesNotContain("private_lookup", "hidden_lookup", "write_action");
    }

    @Test
    void requestingAuthenticationForPublicReadDoesNotExecuteIt() {
        var fixture = fixture("REQUEST_AUTHENTICATION", "public_guide", List.of("public_guide"));
        var context = OrchestrationContext.anonymous();
        var outcome = fixture.service.resolve(information(), context, pipeline(context, List.of("public_guide")));
        assertThat(outcome.authenticationRequiredAction()).isNull();
        assertThat(outcome.executedActions()).isEmpty();
        verifyNoInteractions(fixture.handler);
    }

    private Fixture fixture(String decision, String selected, List<String> allowed) {
        var ai = mock(AICoreService.class);
        var registry = mock(AIActionRegistry.class);
        var handler = mock(AIActionHandler.class);
        var privateRead = AIActionMetaData.builder().name("private_lookup").description("Read the signed-in customer's current subscription status.")
            .accessMode(ActionAccessMode.READ).anonymousAllowed(false).groundingEligible(true).readActionResolutionEligible(true)
            .requiredParameters(Set.of("actorId"))
            .inputSchema(Map.of("type", "object", "properties", Map.of("actorId", Map.of("type", "string")),
                "required", List.of("actorId"), "additionalProperties", false))
            .parameterSchemas(Map.of("actorId", AIActionParamSchema.builder().askUser(false).visibility("INTERNAL")
                .resolveFrom(Map.of("source", "RUNTIME_CONTEXT", "field", "userId")).build())).build();
        var publicRead = AIActionMetaData.builder().name("public_guide").description("Read public policy guidance, not private account state.")
            .accessMode(ActionAccessMode.READ).anonymousAllowed(true).groundingEligible(true).readActionResolutionEligible(true).build();
        var write = AIActionMetaData.builder().name("write_action").accessMode(ActionAccessMode.WRITE_ONLY)
            .anonymousAllowed(false).groundingEligible(true).readActionResolutionEligible(true).build();
        when(registry.getAllMetadata()).thenReturn(List.of(privateRead, publicRead, write));
        for (var metadata : List.of(privateRead, publicRead, write)) {
            when(registry.findHandler(metadata.getName())).thenReturn(Optional.of(handler));
            when(registry.findMetadata(metadata.getName())).thenReturn(Optional.of(metadata));
        }
        when(handler.validateActionAllowed(any())).thenReturn(true);
        var result = ActionResult.builder().success(true).data(ActionPayload.object(Map.of("status", "ACTIVE"))).build();
        when(handler.executeAction(any(), any())).thenReturn(result);
        when(handler.buildPostActionLlmFacts(eq(result), any())).thenReturn(Optional.of(Map.of("status", "ACTIVE")));
        var templates = mock(PromptTemplateResolver.class);
        when(templates.resolve("orchestration/read-action-resolution", "system")).thenReturn(template("system", "Plan permitted reads."));
        when(templates.resolve("orchestration/read-action-resolution", "user")).thenReturn(template("user", "{{eligible_actions_json}}"));
        when(ai.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(AIGenerationResponse.builder()
            .content("{\"decision\":\"" + decision + "\",\"actions\":[{\"name\":\"" + selected + "\",\"params\":{}}]}").build());
        return new Fixture(new ReadActionResolutionService(ai, registry, new IntentExtractionJsonSupport(new ObjectMapper()),
            templates, new PromptRenderer(), mock(AIEntityConfigurationLoader.class)), ai, handler);
    }

    private ResolvedPromptTemplate template(String name, String text) {
        return new ResolvedPromptTemplate(new PromptTemplate(new PromptTemplateKey("orchestration/read-action-resolution", "v1", name), text), List.of("v1"));
    }

    private Intent information() {
        return Intent.builder().type(IntentType.INFORMATION).intent("current_subscription_status")
            .requiresRetrieval(false).requiresGeneration(false).requiresTargetResolution(false).needsAdvancedRAG(false).build();
    }

    private PipelineContext pipeline(OrchestrationContext context, List<String> allowed) {
        var readPolicy = new OrchestrationPolicy.ReadActionResolutionPolicy(true,
            OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE, allowed, true,
            2, 2, 3, 1, 4000, 1200, 5, OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE, true);
        var policy = new OrchestrationPolicy(OrchestrationProfile.PRODUCTION_CHAT, "executor", null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(), readPolicy, OrchestrationPolicy.RagBudgets.defaults());
        return PipelineContext.from("What is my current subscription status?", context).toBuilder().orchestrationPolicy(policy).build();
    }

    private record Fixture(ReadActionResolutionService service, AICoreService ai, AIActionHandler handler) { }
}
