package ai.fabric.intent.extraction;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.EnrichedPromptBuilder;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiStepIntentExtractionStrategyTest {

    @Mock
    private AICoreService aiCoreService;

    @Mock
    private AIActionRegistry actionHandlerRegistry;

    @Mock
    private IntentExtractionValidator validator;

    private IntentExtractionJsonSupport jsonSupport;
    private PromptRenderer promptRenderer;
    private PromptTemplateResolver promptTemplateResolver;

    private static IntentExtractionInput input(String userQuery) {
        return new IntentExtractionInput(userQuery, userQuery, List.of());
    }

    @BeforeEach
    void setUp() {
        jsonSupport = new IntentExtractionJsonSupport(new ObjectMapper());
        promptRenderer = new PromptRenderer();
        promptTemplateResolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    @Test
    void shouldCountActionSelectionCallEvenWhenSelectionParsingFails() {
        when(actionHandlerRegistry.getAllMetadata()).thenReturn(List.of(AIActionMetaData.builder().name("cancel_subscription").build()));
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of())
        );

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationWithAction()).build())
            .thenReturn(AIGenerationResponse.builder().content("not-json").build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(input("Cancel my subscription"), OrchestrationContext.forUser("user-1"));

        assertThat(attempt.getLlmCalls()).isEqualTo(2);
        verify(aiCoreService, times(2)).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldCountOnlyClassificationCallWhenNoActionIntentsPresent() {
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of())
        );
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationInformationOnly()).build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(input("What is your refund policy?"), OrchestrationContext.forUser("user-2"));

        assertThat(attempt.getLlmCalls()).isEqualTo(1);
        verify(aiCoreService, times(1)).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void missingExecutableIntentNameRemainsInvalidInsteadOfUsingSyntheticName() {
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content("""
                {
                  "intents": [{
                    "type": "INFORMATION",
                    "requiresRetrieval": true,
                    "requiresGeneration": true,
                    "requiresTargetResolution": false,
                    "needsAdvancedRAG": false,
                    "vectorSpace": "policies"
                  }]
                }
                """).build());
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                List.of("Missing intent name"),
                List.of()
            )
        );
        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(
            input("What is the policy?"),
            OrchestrationContext.forUser("user-missing-name")
        );

        assertThat(attempt.isSuccess()).isFalse();
        assertThat(attempt.getResponse().getIntents().getFirst().getIntent()).isNull();
    }

    @Test
    void shouldKeepTrustedRuntimeContextInSystemPromptOnly() {
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(
                true,
                IntentExtractionValidator.ErrorCategory.NONE,
                List.of(),
                List.of()
            )
        );
        when(aiCoreService.generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(
            AIGenerationResponse.builder()
                .content(classificationInformationOnly())
                .build()
        );
        MultiStepIntentExtractionStrategy strategy =
            new MultiStepIntentExtractionStrategy(
                aiCoreService,
                actionHandlerRegistry,
                jsonSupport,
                validator,
                promptRenderer,
                promptTemplateResolver
            );
        IntentExtractionInput extractionInput = new IntentExtractionInput(
            "What plan am I on?",
            "What plan am I on?",
            List.of(),
            null,
            "INCOMPLETE ACTION DRAFT CONTEXT:\n- action=update_address"
        );

        strategy.attemptExtract(
            extractionInput,
            OrchestrationContext.forUser("user-system-context")
        );

        ArgumentCaptor<AIGenerationRequest> requestCaptor =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(
            requestCaptor.capture(),
            eq(LlmPurpose.ORCHESTRATION)
        );
        AIGenerationRequest request = requestCaptor.getValue();
        assertThat(request.getSystemPrompt())
            .contains("TRUSTED RUNTIME INTENT-EXTRACTION CONTEXT")
            .contains("INCOMPLETE ACTION DRAFT CONTEXT")
            .contains("action=update_address");
        assertThat(request.getPrompt())
            .contains("What plan am I on?")
            .doesNotContain("INCOMPLETE ACTION DRAFT CONTEXT");
    }

    @Test
    void shouldGiveTheModelTypedPolicyAndRejectedObligationsForSemanticReplan() {
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(
                true,
                IntentExtractionValidator.ErrorCategory.NONE,
                List.of(),
                List.of()
            )
        );
        when(aiCoreService.generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(
            AIGenerationResponse.builder()
                .content(classificationInformationOnly())
                .build()
        );
        MultiStepIntentExtractionStrategy strategy =
            new MultiStepIntentExtractionStrategy(
                aiCoreService,
                actionHandlerRegistry,
                jsonSupport,
                validator,
                promptRenderer,
                promptTemplateResolver
            );
        IntentExtractionValidator.ValidationIssue issue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED,
                IntentExtractionValidator.Severity.ERROR,
                1,
                "action",
                "No handler registered for the model-selected action"
            );
        ExtractionAttempt previous = ExtractionAttempt.builder()
            .success(false)
            .response(MultiIntentResponse.builder()
                .intents(List.of(
                    Intent.builder()
                        .type(IntentType.ACTION)
                        .action("valid_action")
                        .obligationId("obligation-1")
                        .build(),
                    Intent.builder()
                        .type(IntentType.ACTION)
                        .action("invented_action")
                        .obligationId("obligation-2")
                        .build()
                ))
                .build())
            .validationResult(new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(issue),
                List.of(issue.message()),
                List.of()
            ))
            .build();
        SemanticRecoveryPolicy policy = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            OrchestrationProperties.UnknownActionRecovery.LLM_REPLAN_TO_INFORMATION,
            null,
            null
        );

        strategy.attemptExtract(
            input("Find the current record and explain its policy"),
            OrchestrationContext.forUser("user-recovery"),
            previous,
            policy
        );

        ArgumentCaptor<AIGenerationRequest> requestCaptor =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(
            requestCaptor.capture(),
            eq(LlmPurpose.ORCHESTRATION)
        );
        assertThat(requestCaptor.getValue().getSystemPrompt())
            .contains("BOUNDED SEMANTIC RECOVERY")
            .contains("unknownAction=LLM_REPLAN_TO_INFORMATION")
            .contains("code=ACTION_UNREGISTERED intentIndex=1")
            .contains("invented_action")
            .contains("Preserve the exact type and exact registered action ID")
            .contains("Do not replace a rejected action with an unrelated action");
    }

    @Test
    void shouldCountActionSelectionCallWhenProviderThrows() {
        when(actionHandlerRegistry.getAllMetadata()).thenReturn(List.of(AIActionMetaData.builder().name("cancel_subscription").build()));

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationWithAction()).build())
            .thenThrow(new IllegalStateException("provider down"));

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(input("Cancel my subscription"), OrchestrationContext.forUser("user-3"));

        assertThat(attempt.isSuccess()).isFalse();
        assertThat(attempt.getLlmCalls()).isEqualTo(2);
        verify(aiCoreService, times(2)).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldParseConfirmationIntentTypeWithoutExtraCalls() {
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of())
        );

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationConfirmationPositive()).build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(input("Yes, confirm"), OrchestrationContext.forUser("user-4"));

        assertThat(attempt.isSuccess()).isTrue();
        assertThat(attempt.getLlmCalls()).isEqualTo(1);
        assertThat(attempt.getResponse().getIntents()).hasSize(1);
        assertThat(attempt.getResponse().getIntents().getFirst().getType()).isEqualTo(IntentType.CONFIRMATION_POSITIVE);
        verify(aiCoreService, times(1)).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldPreserveModelAuthoredOutOfScopeResponse() {
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of())
        );
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content("""
                {
                  "intents": [{
                    "type": "OUT_OF_SCOPE",
                    "intent": "unsupported_order_operation",
                    "requiresRetrieval": false,
                    "requiresGeneration": false,
                    "requiresTargetResolution": false,
                    "directAnswer": "I can help with approved order support, but I cannot make that change here."
                  }]
                }
                """).build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(
            input("Change my order"),
            OrchestrationContext.forUser("user-out-of-scope")
        );

        assertThat(attempt.isSuccess()).isTrue();
        assertThat(attempt.getResponse().getIntents().getFirst().getType()).isEqualTo(IntentType.OUT_OF_SCOPE);
        assertThat(attempt.getResponse().getIntents().getFirst().getDirectAnswer())
            .isEqualTo("I can help with approved order support, but I cannot make that change here.");
        verify(aiCoreService).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldExposeAndPreserveOptionalPresentationParamsDuringFill() {
        AIActionMetaData action = AIActionMetaData.builder()
            .name("commerce_update_cart")
            .description("Update commerce cart")
            .parameters(Map.of(
                "cart_update_confirmation", "Presentation-only shopper-facing confirmation phrase (optional)"
            ))
            .parameterSchemas(Map.of(
                "cart_update_confirmation",
                AIActionParamSchema.builder()
                    .name("cart_update_confirmation")
                    .type(AIActionParamType.STRING)
                    .required(false)
                    .description("Presentation-only shopper-facing confirmation phrase")
                    .build()
            ))
            .requiredParameters(Set.of())
            .build();

        when(actionHandlerRegistry.getAllMetadata()).thenReturn(List.of(action));
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of())
        );
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationWithcommerceCartAction()).build())
            .thenReturn(AIGenerationResponse.builder().content("""
                {"mappings":[{"intentIndex":0,"selectedAction":"commerce_update_cart"}]}
                """).build())
            .thenReturn(AIGenerationResponse.builder().content("""
                {"mappings":[{"intentIndex":0,"actionParams":{"cart_update_confirmation":"Add 1 Selling Plans Ski Wax to your cart"}}]}
                """).build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(
            input("Add Selling Plans Ski Wax to my cart"),
            OrchestrationContext.forUser("user-5")
        );

        assertThat(attempt.isSuccess()).isTrue();
        assertThat(attempt.getResponse().getIntents()).hasSize(1);
        assertThat(attempt.getResponse().getIntents().getFirst().getActionParams())
            .containsEntry("cart_update_confirmation", "Add 1 Selling Plans Ski Wax to your cart");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService, times(3)).generateContent(requestCaptor.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(requestCaptor.getAllValues().get(2).getPrompt())
            .contains("cart_update_confirmation: string - Presentation-only shopper-facing confirmation phrase")
            .contains("Fill optional parameters when the paramsSchema/description says they improve presentation")
            .contains("For search/read actions with a valid required `query` parameter");
        assertThat(requestCaptor.getAllValues().get(0).getParameters())
            .containsEntry("response_format", Map.of("type", "json_schema"));
        assertThat(String.valueOf(requestCaptor.getAllValues().get(0).getParameters().get("output_schema")))
            .contains("requiresTargetResolution")
            .contains("obligationId");
        assertThat(requestCaptor.getAllValues().get(1).getParameters())
            .containsEntry("response_format", Map.of("type", "json_schema"));
        assertThat(String.valueOf(requestCaptor.getAllValues().get(1).getParameters().get("output_schema")))
            .contains("commerce_update_cart")
            .contains("selectedAction")
            .doesNotContain("cancel_subscription");
        assertThat(requestCaptor.getAllValues().get(2).getParameters())
            .containsEntry("response_format", Map.of("type", "json_object"));
        assertThat(String.valueOf(requestCaptor.getAllValues().get(2).getParameters().get("output_schema")))
            .contains("actionParams")
            .doesNotContain("intents");
    }

    @Test
    void unresolvedActionHintNeverBecomesAnExecutableActionIdentifier() {
        when(actionHandlerRegistry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder()
                .name("dealership_search_inventory")
                .description("Search current inventory")
                .build()
        ));
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.INCOMPLETE,
                List.of(),
                List.of("Action name is missing")
            )
        );
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content("""
                {
                  "intents": [{
                    "type": "ACTION",
                    "intent": "inventory_search",
                    "actionHint": "search for electric cars",
                    "requiresRetrieval": false,
                    "requiresGeneration": false,
                    "requiresTargetResolution": false,
                    "needsAdvancedRAG": false
                  }],
                  "metadata": {"retrievalQueryHint": null}
                }
                """).build())
            .thenReturn(AIGenerationResponse.builder().content("""
                {"mappings":[{"intentIndex":0,"selectedAction":null}]}
                """).build());

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );

        ExtractionAttempt attempt = strategy.attemptExtract(
            input("Find electric cars"),
            OrchestrationContext.forUser("user-unresolved-action")
        );

        assertThat(attempt.isSuccess()).isFalse();
        assertThat(attempt.getResponse().getIntents().getFirst().getAction()).isNull();
    }

    @Test
    void progressiveFallbackReceivesDeploymentOwnedStageGuidance() {
        when(actionHandlerRegistry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder().name("search_inventory").build()
        ));
        when(validator.validate(any())).thenReturn(
            new IntentExtractionValidator.ValidationResult(
                true,
                IntentExtractionValidator.ErrorCategory.NONE,
                List.of(),
                List.of()
            )
        );
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(classificationWithAction()).build())
            .thenReturn(AIGenerationResponse.builder().content("""
                {"mappings":[{"intentIndex":0,"selectedAction":"search_inventory"}]}
                """).build());
        EnrichedPromptBuilder enrichedPromptBuilder = org.mockito.Mockito.mock(EnrichedPromptBuilder.class);
        when(enrichedPromptBuilder.buildSemanticCatalogPrompt(any())).thenReturn("KNOWLEDGE BASE OVERVIEW:\n- document");
        when(enrichedPromptBuilder.buildIntentClassificationGuidance(any())).thenReturn("DEPLOYMENT CLASSIFICATION RULE");
        when(enrichedPromptBuilder.buildActionSelectionGuidance(any())).thenReturn("DEPLOYMENT ACTION RULE");

        MultiStepIntentExtractionStrategy strategy = new MultiStepIntentExtractionStrategy(
            aiCoreService,
            actionHandlerRegistry,
            jsonSupport,
            validator,
            promptRenderer,
            promptTemplateResolver
        );
        ReflectionTestUtils.setField(strategy, "enrichedPromptBuilder", enrichedPromptBuilder);

        strategy.attemptExtract(
            input("Search current inventory"),
            OrchestrationContext.forUser("user-stage-guidance")
        );

        ArgumentCaptor<AIGenerationRequest> requests = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService, times(2)).generateContent(requests.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(requests.getAllValues().get(0).getSystemPrompt())
            .contains("KNOWLEDGE BASE OVERVIEW")
            .contains("DEPLOYMENT CLASSIFICATION RULE")
            .doesNotContain("DEPLOYMENT ACTION RULE");
        assertThat(requests.getAllValues().get(1).getSystemPrompt())
            .contains("KNOWLEDGE BASE OVERVIEW")
            .contains("DEPLOYMENT ACTION RULE")
            .doesNotContain("DEPLOYMENT CLASSIFICATION RULE");
    }

    private String classificationWithAction() {
        return """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "actionHint": "cancel subscription",
                  "requiresRetrieval": false,
                  "requiresGeneration": false
                }
              ]
            }
            """;
    }

    private String classificationConfirmationPositive() {
        return """
            {
              "intents": [
                {
                  "type": "CONFIRMATION_POSITIVE"
                }
              ]
            }
            """;
    }

    private String classificationWithcommerceCartAction() {
        return """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "commerce_update_cart",
                  "actionHint": "add to cart",
                  "requiresRetrieval": false,
                  "requiresGeneration": false
                }
              ]
            }
            """;
    }

    private String classificationInformationOnly() {
        return """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "refund_policy",
                  "requiresRetrieval": true,
                  "requiresGeneration": false,
                  "vectorSpace": "policies"
                }
              ]
            }
            """;
    }
}
