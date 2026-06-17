package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

class PostActionGenerationSupportTest {

    @Test
    void shouldResolveDisabledWhenGenerationPropertiesAreOffAndReadActionIsNotForced() {
        PostActionGenerationSupport support = newSupport(
            mock(AICoreService.class),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties()
        );

        PostActionGenerationSupport.ResolvedPostActionGeneration resolved = support.resolvePostActionGeneration(
            "search_products",
            Intent.builder().requiresGeneration(true).generationInstructions("Summarize").build(),
            AIActionMetaData.builder().accessMode(ActionAccessMode.READ).build(),
            null,
            false
        );

        assertThat(resolved.shouldGenerate()).isFalse();
        assertThat(resolved.forced()).isFalse();
    }

    @Test
    void shouldForceGenerationForGroundingEligibleReadActionsAndAppendEvidenceContract() {
        PostActionGenerationSupport support = newSupport(
            mock(AICoreService.class),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties()
        );
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("search_products")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .build();

        PostActionGenerationSupport.ResolvedPostActionGeneration resolved = support.resolvePostActionGeneration(
            "search_products",
            Intent.builder().requiresGeneration(false).build(),
            metadata,
            forceGroundingEligibleReadActionPostGenerationPolicy(),
            false
        );

        assertThat(resolved.shouldGenerate()).isTrue();
        assertThat(resolved.forced()).isTrue();
        assertThat(resolved.generationInstructions())
            .contains("Answer the user's request from the read-action result facts")
            .contains("Evidence contract:")
            .contains("Do not substitute a present fact");
        assertThat(support.isReadActionAllowedWhenActionsDisabled(false, metadata, forceGroundingEligibleReadActionPostGenerationPolicy()))
            .isTrue();
    }

    @Test
    void shouldGenerateGenericPostActionSummaryFromHandlerFacts() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Generated summary").model("test-model").build());

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);
        PostActionGenerationSupport support = newSupport(
            aiCoreService,
            new RelationshipQueryPostActionGenerationProperties(),
            properties
        );

        AIActionHandler handler = mock(AIActionHandler.class);
        ActionResult actionResult = ActionResult.builder().success(true).message("OK").build();
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "status", "updated",
            "itemCount", 2
        )));

        PostActionGenerationSupport.ResolvedPostActionGeneration request =
            new PostActionGenerationSupport.ResolvedPostActionGeneration(true, "Explain the update.", false);

        PostActionGenerationSupport.PostActionGenerationOutcome outcome = support.maybeGeneratePostActionSummary(
            "update_cart",
            handler,
            Intent.builder().action("update_cart").build(),
            actionResult,
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("update cart", OrchestrationContext.forUser("user-1")),
            Map.of("sku", "SKU-1"),
            request
        );

        assertThat(outcome).isNotNull();
        assertThat(outcome.message()).isEqualTo("Generated summary");
        assertThat(outcome.summary()).isEqualTo("Generated summary");
        assertThat(outcome.metadata())
            .containsEntry("used", true)
            .containsEntry("action", "update_cart")
            .containsEntry("model", "test-model");

        ArgumentCaptor<AIGenerationRequest> generationRequest = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(generationRequest.capture(), eq(LlmPurpose.GENERATION));
        assertThat(generationRequest.getValue().getPrompt())
            .contains("update_cart")
            .contains("Explain the update.")
            .contains("itemCount");
    }

    @Test
    void shouldReturnNullWhenRequestDoesNotRequireGeneration() {
        PostActionGenerationSupport support = newSupport(
            mock(AICoreService.class),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties()
        );

        PostActionGenerationSupport.PostActionGenerationOutcome outcome = support.maybeGeneratePostActionSummary(
            "update_cart",
            mock(AIActionHandler.class),
            Intent.builder().action("update_cart").build(),
            ActionResult.builder().success(true).message("OK").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("update cart", OrchestrationContext.forUser("user-1")),
            Map.of(),
            new PostActionGenerationSupport.ResolvedPostActionGeneration(false, null, false)
        );

        assertThat(outcome).isNull();
    }

    private PostActionGenerationSupport newSupport(AICoreService aiCoreService,
                                                   RelationshipQueryPostActionGenerationProperties relationshipProperties,
                                                   PostActionGenerationProperties properties) {
        return new PostActionGenerationSupport(
            aiCoreService,
            relationshipProperties,
            properties,
            providerOf(new ObjectMapper()),
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    private OrchestrationPolicy forceGroundingEligibleReadActionPostGenerationPolicy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "thinker",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(
                false,
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                true,
                false,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }
}
