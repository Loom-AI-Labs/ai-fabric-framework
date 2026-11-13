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
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.ManagedPromptDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostActionGenerationSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {"OK", "success", "Action executed.", "MCP tool result", "Your request is complete."})
    void genericGenerationFailurePreservesHandlerMessageRegardlessOfItsWording(String message) {
        for (boolean forced : List.of(false, true)) {
            for (String failure : List.of("exception", "null", "blank")) {
                AICoreService ai = mock(AICoreService.class);
                if ("exception".equals(failure)) {
                    when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
                        .thenThrow(new IllegalStateException("private-provider-detail"));
                } else if ("blank".equals(failure)) {
                    when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
                        .thenReturn(AIGenerationResponse.builder().content("  ").build());
                }
                PostActionGenerationProperties properties = new PostActionGenerationProperties();
                properties.setEnabled(!forced);
                var support = newSupport(ai, new RelationshipQueryPostActionGenerationProperties(), properties);
                var handler = mock(AIActionHandler.class);
                var receipt = ActionResult.builder().success(true).message(message)
                    .data(ActionResultContracts.object(Map.of("receiptId", "receipt-1"))).build();
                when(handler.buildPostActionLlmFacts(eq(receipt), any())).thenReturn(Optional.of(Map.of("status", "complete", "itemCount", 2)));
                var outcome = support.maybeGeneratePostActionSummary("business_operation", handler,
                    Intent.builder().action("business_operation").build(), receipt, OrchestrationContext.forUser("owner"),
                    PipelineContext.from("request", OrchestrationContext.forUser("owner")), Map.of(),
                    new PostActionGenerationSupport.ResolvedPostActionGeneration(true, "Explain the receipt.", forced));
                assertThat(outcome.summary()).isNull();
                assertThat(outcome.message()).isEqualTo(message);
                assertThat(outcome.metadata()).containsEntry("used", false).containsEntry("errorCode", "GENERATION_FAILED")
                    .containsEntry("skippedReason", "exception".equals(failure) ? "generation_failed" : "empty_generation_response")
                    .doesNotContainKeys("error", "deterministicFallbackUsed", "deterministicFallbackSource");
                assertThat(receipt.isSuccess()).isTrue();
                assertThat(receipt.getData().toMap()).containsEntry("receiptId", "receipt-1");
                verify(ai).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
            }
        }
    }

    @Test
    void publicGenerationStatusDoesNotExposeProviderErrorsOrFacts() {
        var outcome = new PostActionGenerationSupport.PostActionGenerationOutcome(null, "Receipt saved.", Map.of(
            "used", false, "skippedReason", "generation_failed", "errorCode", "GENERATION_FAILED",
            "error", "private-provider-detail", "facts", Map.of("private", "value")));
        assertThat(PostActionGenerationSupport.publicGenerationMetadata(outcome)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "used", false, "skippedReason", "generation_failed", "errorCode", "GENERATION_FAILED"));
    }

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

    @ParameterizedTest
    @MethodSource("managedPostActionCases")
    void shouldComposeForcedReadSystemAndUserGuidanceWithoutChangingExecution(String action, Map<String, String> overlay) {
        AICoreService ai = mock(AICoreService.class);
        when(ai.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Model-authored answer. Let me know if useful.").build());
        PostActionGenerationSupport support = newSupport(ai,
            new RelationshipQueryPostActionGenerationProperties(), new PostActionGenerationProperties());
        var metadata = AIActionMetaData.builder().name(action).accessMode(ActionAccessMode.READ).groundingEligible(true).build();
        var intent = Intent.builder().action(action).requiresGeneration(false)
            .actionParams(Map.of("query", "authorized query")).build();
        var resolved = support.resolvePostActionGeneration(action, intent, metadata,
            forceGroundingEligibleReadActionPostGenerationPolicy(), false);
        var context = OrchestrationContext.forUser("user-1");
        context.setMetadata(Map.of(OrchestrationContextMetadataKeys.PROMPT_PREVIEW, overlay));
        var pipeline = PipelineContext.from("Be exploratory and suggest next steps", context);
        var facts = Map.<String, Object>of("recordName", "Record Alpha", "status", "ACTIVE");
        var result = ActionResult.builder().success(true).message("OK")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "id", "record-1", "content", "Record Alpha is ACTIVE")), "totalResults", 1))).build();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.buildPostActionLlmFacts(eq(result), any())).thenReturn(Optional.of(facts));

        var outcome = support.maybeGeneratePostActionSummary(action, handler, intent, result, context, pipeline, Map.of(), resolved);

        assertThat(resolved.shouldGenerate()).isTrue();
        assertThat(resolved.forced()).isTrue();
        assertThat(outcome.summary()).isEqualTo("Model-authored answer. Let me know if useful.");
        var captured = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(ai).generateContent(captured.capture(), eq(LlmPurpose.GENERATION));
        var request = captured.getValue();
        assertThat(request.getMaxTokens()).isEqualTo(800);
        assertThat(request.getPrompt()).contains("Broader advice or inference requires explicit managed guidance",
                "Without it, stay grounded", "Do not substitute a present fact", "Record Alpha")
            .doesNotContain("read-action facts as the only evidence");
        if (overlay.isEmpty()) {
            String originalSystem = new PromptRenderer().render(promptTemplateResolver()
                .resolve("orchestration/post-action-generation", "system").template(), Map.of());
            assertThat(request.getSystemPrompt()).isEqualTo(originalSystem).contains("Use ONLY the FACTS");
            assertThat(request.getPrompt()).contains("Use only the FACTS provided by the system.");
        } else {
            assertThat(request.getSystemPrompt()).contains(
                    overlay.getOrDefault("answerGenerationPrompt", ManagedPromptDefaults.ANSWER_GENERATION_PROMPT),
                    overlay.getOrDefault("retrievalPrompt", ManagedPromptDefaults.RETRIEVAL_PROMPT),
                    "authority for business-specific facts", "Without explicit broader guidance",
                    "separately from verified business facts", "never grants access", "required confirmation")
                .doesNotContain("Use ONLY the FACTS", "{{");
            assertThat(request.getPrompt()).contains("MANAGED RESPONSE SCOPE")
                .doesNotContain("Use only the FACTS", "Do not recommend handoffs or next steps", "Do not append generic closers");
            if (overlay.containsKey("answerGenerationPrompt")) {
                assertThat(request.getSystemPrompt()).doesNotContain(ManagedPromptDefaults.ANSWER_GENERATION_PROMPT);
                assertThat(request.getPrompt()).contains(overlay.get("answerGenerationPrompt"), overlay.get("retrievalPrompt"));
            } else {
                assertThat(request.getSystemPrompt()).contains("stop at the grounded conclusion");
            }
        }
    }

    private static Stream<Arguments> managedPostActionCases() {
        List<Map<String, String>> overlays = List.of(Map.of(), Map.of("assistantUiPrompt", "Compact presentation."),
            Map.of("answerGenerationPrompt", "ADVISORY: Give labeled general advice and useful next steps.",
                "retrievalPrompt", "Business evidence is authoritative; label general knowledge separately."),
            Map.of("answerGenerationPrompt", "EXPLORATORY: Discuss clearly labeled hypotheses and inferences.",
                "retrievalPrompt", "Distinguish unverified hypotheses from current business facts. No web research."));
        return Stream.of("read_records", "relationship_query")
            .flatMap(action -> overlays.stream().map(overlay -> Arguments.of(action, overlay)));
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
