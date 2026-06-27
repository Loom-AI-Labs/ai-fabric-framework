package ai.fabric.intent;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentQueryExtractorTest {

    @Mock
    private AICoreService aiCoreService;

    @Mock
    private EnrichedPromptBuilder enrichedPromptBuilder;

    @Mock
    private AIActionRegistry actionHandlerRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static IntentExtractionInput input(String userQuery) {
        return new IntentExtractionInput(userQuery, userQuery, List.of());
    }

    @Test
    void shouldParseJsonResponseIntoMultiIntentResponse() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "confidence": 0.92,
                  "action": "cancel_subscription",
                  "actionParams": {"reason": "too expensive"},
                  "requiresRetrieval": false
                }
              ],
              "orchestrationStrategy": null
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Cancel my subscription"), OrchestrationContext.forUser("user-123"));

        assertThat(response.getIntents()).hasSize(1);
        Intent intent = response.getIntents().getFirst();
        assertThat(intent.getIntent()).isEqualTo("cancel_subscription");
        assertThat(intent.getActionParams()).containsEntry("reason", "too expensive");
        assertThat(response.getOrchestrationStrategy()).isEqualTo("DIRECT_ACTION");
    }

    @Test
    void shouldParseWrappedJsonWithoutRepairCall() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String wrappedJson = """
            Sure, here is the structured result:
            ```json
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "refund_policy",
                  "confidence": 0.81,
                  "requiresRetrieval": true
                }
              ]
            }
            ```
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(wrappedJson).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("What is your refund policy?"), OrchestrationContext.forUser("user-123"));

        assertThat(response.getIntents()).singleElement().satisfies(intent -> {
            assertThat(intent.getType()).isEqualTo(IntentType.INFORMATION);
            assertThat(intent.getIntent()).isEqualTo("refund_policy");
        });
        verify(aiCoreService).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldReturnTraceWithProviderTimingAndModel() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "refund_policy",
                  "confidence": 0.81,
                  "requiresRetrieval": true
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder()
                .content(json)
                .processingTimeMs(132L)
                .model("gpt-5.4-nano")
                .build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        IntentQueryExtractor.ExtractionTrace trace =
            extractor.extractWithTrace(input("What is your refund policy?"), OrchestrationContext.forUser("user-123"));

        assertThat(trace).isNotNull();
        assertThat(trace.response()).isNotNull();
        assertThat(trace.providerProcessingTimeMs()).isEqualTo(132L);
        assertThat(trace.model()).isEqualTo("gpt-5.4-nano");
        assertThat(trace.processingTimeMs()).isNotNull();
    }

    @Test
    void shouldParseResponseGenerationProfileFromExtractorResponse() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "product_summary",
                  "confidence": 0.81,
                  "requiresRetrieval": true,
                  "requiresGeneration": true,
                  "responseProfile": "CONCISE"
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Tell me about Alienware m18 R2"), OrchestrationContext.forUser("user-123"));

        assertThat(response.getIntents()).singleElement().satisfies(intent ->
            assertThat(intent.getResponseProfile()).isEqualTo(ResponseGenerationProfile.CONCISE)
        );
    }

    @Test
    void shouldTolerateJsonWithCommentsAndTrailingCommas() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              // Some providers may include comments in JSON-like responses.
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "confidence": 0.92,
                  "action": "cancel_subscription",
                  "actionParams": {"reason": "too expensive"},
                  "requiresRetrieval": false,
                }
              ],
              "orchestrationStrategy": null,
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Cancel my subscription"), OrchestrationContext.forUser("user-123"));
        assertThat(response.getIntents()).hasSize(1);
        assertThat(response.getIntents().getFirst().getIntent()).isEqualTo("cancel_subscription");
    }

    @Test
    void shouldRequestJsonOnlyResponseFormatFromProvider() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "confidence": 0.92,
                  "action": "cancel_subscription",
                  "actionParams": {"reason": "too expensive"},
                  "requiresRetrieval": false
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        extractor.extract(input("Cancel my subscription"), OrchestrationContext.forUser("user-123"));

        org.mockito.ArgumentCaptor<AIGenerationRequest> captor =
            org.mockito.ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(captor.capture(), eq(LlmPurpose.ORCHESTRATION));

        AIGenerationRequest sent = captor.getValue();
        assertThat(sent.getParameters()).isNotNull();
        assertThat(sent.getParameters()).containsKey("response_format");

        Object responseFormat = sent.getParameters().get("response_format");
        assertThat(responseFormat).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormatMap = (Map<String, Object>) responseFormat;
        assertThat(responseFormatMap).containsEntry("type", "json_object");
    }

    @Test
    void shouldParseInformationIntentAndMarkRetrieval() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "refund_policy",
                  "confidence": 0.81,
                  "requiresRetrieval": true,
                  "requiresGeneration": false,
                  "optimizedQuery": "Policy documents where type = 'refund' and status = 'active'",
                  "vectorSpace": "policies"
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("What is your refund policy?"), OrchestrationContext.forUser("user-456"));

        assertThat(response.getIntents()).hasSize(1);
        Intent intent = response.getIntents().getFirst();
        assertThat(intent.getType()).isEqualTo(IntentType.INFORMATION);
        assertThat(intent.getRequiresRetrieval()).isTrue();
        assertThat(intent.getRequiresGeneration()).isFalse();
        assertThat(intent.getOptimizedQuery()).contains("Policy documents");
        assertThat(response.getOrchestrationStrategy()).isEqualTo("RETRIEVE_AND_GENERATE");
    }

    @Test
    void shouldParseOutOfScopeIntent() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "OUT_OF_SCOPE",
                  "intent": "out_of_scope",
                  "confidence": 0.55,
                  "actionParams": {"reason": "Unsupported domain"}
                }
              ],
              "orchestrationStrategy": "ADMIT_UNKNOWN"
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Build me a spaceship"), OrchestrationContext.forUser("user-789"));

        assertThat(response.getIntents()).hasSize(1);
        Intent intent = response.getIntents().getFirst();
        assertThat(intent.getType()).isEqualTo(IntentType.OUT_OF_SCOPE);
        assertThat(intent.getActionParams()).containsEntry("reason", "Unsupported domain");
        assertThat(response.getOrchestrationStrategy()).isEqualTo("ADMIT_UNKNOWN");
    }

    @Test
    void shouldTolerateOutOfScopeIntentWithoutNameFields() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "OUT_OF_SCOPE",
                  "confidence": 1.0,
                  "actionParams": {"reason": "Unrelated to available knowledge base"}
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("What analytics solutions do you offer?"),
            OrchestrationContext.forUser("user-789"));

        assertThat(response.getIntents()).hasSize(1);
        Intent intent = response.getIntents().getFirst();
        assertThat(intent.getType()).isEqualTo(IntentType.OUT_OF_SCOPE);
        assertThat(intent.getIntent()).isEqualTo("out_of_scope");
        assertThat(intent.getActionParams()).containsEntry("reason", "Unrelated to available knowledge base");
    }

    @Test
    void shouldParseNextStepRecommendation() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
	              "intents": [
                {
                  "type": "ACTION",
                  "intent": "update_payment_method",
                  "confidence": 0.77,
                  "action": "update_payment_method",
                  "nextStepRecommended": {
                    "intent": "view_billing_history",
                    "query": "Show my billing history",
                    "rationale": "Users updating payment methods often review billing history next.",
                    "confidence": 0.71
                  }
	                }
	              ]
	            }
	        """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Update my payment details"), OrchestrationContext.forUser("user-321"));

        Intent intent = response.getIntents().getFirst();
        assertThat(intent.getNextStepRecommended()).isNotNull();
        assertThat(intent.getNextStepRecommended().getIntent()).isEqualTo("view_billing_history");
        assertThat(intent.getNextStepRecommended().getConfidence()).isEqualTo(0.71);
    }

    @Test
    void shouldRejectBlankQuery() {
        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        assertThatThrownBy(() -> extractor.extract(input("   "), OrchestrationContext.forUser("user-123")))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Query cannot be blank");
    }

    @Test
    void shouldRaiseExceptionWhenJsonInvalid() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");
        // Mock initial call returning invalid JSON
        when(aiCoreService.generateContent(argThat(req ->
            req != null && "intent_extraction".equals(req.getGenerationType())), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("not-json").build());
        // Mock repair attempt also returning invalid JSON (repair fails)
        when(aiCoreService.generateContent(argThat(req ->
            req != null && "intent_extraction_repair".equals(req.getGenerationType())), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("still-not-json").build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        assertThatThrownBy(() -> extractor.extract(input("Cancel my subscription"), OrchestrationContext.forUser("user-123")))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Unable to parse intent extraction response");
    }

    @Test
    void shouldRepairInvalidJsonAndReturnValidResponse() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");
        // Mock initial call returning invalid JSON
        when(aiCoreService.generateContent(argThat(req ->
            req != null && "intent_extraction".equals(req.getGenerationType())), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("not-json").build());
        // Mock repair attempt returning valid JSON (repair succeeds)
        String repairedJson = """
	            {
	              "intents": [
	                {
	                  "type": "ACTION",
	                  "intent": "cancel_subscription",
	                  "confidence": 0.85,
	                  "action": "cancel_subscription",
	                  "requiresRetrieval": false
	                }
	              ]
	            }
	        """;
        when(aiCoreService.generateContent(argThat(req ->
            req != null && "intent_extraction_repair".equals(req.getGenerationType())), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(repairedJson).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Cancel my subscription"), OrchestrationContext.forUser("user-123"));

        assertThat(response.getIntents()).hasSize(1);
        assertThat(response.getIntents().getFirst().getIntent()).isEqualTo("cancel_subscription");
        assertThat(response.getOrchestrationStrategy()).isEqualTo("DIRECT_ACTION");
    }

    @Test
    void shouldNotInferVectorSpaceWhenMissingAndRetrievalRequired() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system-prompt");

        String json = """
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "query about products",
                  "requiresRetrieval": true
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        IntentQueryExtractor extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            objectMapper,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        MultiIntentResponse response = extractor.extract(input("Find products"), OrchestrationContext.forUser("user-123"));

        assertThat(response.getIntents()).hasSize(1);
        assertThat(response.getIntents().getFirst().getVectorSpace()).isNull();
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
