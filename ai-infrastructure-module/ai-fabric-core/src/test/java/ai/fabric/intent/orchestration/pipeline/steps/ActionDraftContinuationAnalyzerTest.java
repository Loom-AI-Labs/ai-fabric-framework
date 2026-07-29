package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

class ActionDraftContinuationAnalyzerTest {

    private AICoreService aiCoreService;
    private AIActionRegistry actionRegistry;
    private ActionDraftContinuationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        aiCoreService = mock(AICoreService.class);
        actionRegistry = mock(AIActionRegistry.class);
        when(actionRegistry.findMetadata("update_address"))
            .thenReturn(Optional.of(actionMetadata()));
        PromptBundleProperties properties = new PromptBundleProperties();
        analyzer = new ActionDraftContinuationAnalyzer(
            aiCoreService,
            new DefaultStructuredJsonCallExecutor(
                new StructuredJsonExtractor(),
                new ObjectMapper()
            ),
            actionRegistry,
            new PromptTemplateResolver(
                new ClasspathPromptTemplateStore(
                    new DefaultResourceLoader()
                ),
                properties
            ),
            new PromptRenderer()
        );
    }

    @Test
    void shouldResolveFieldOnlyReplyAfterUnrelatedHistory() {
        when(aiCoreService.generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(AIGenerationResponse.builder()
            .model("test-model")
            .content("""
                {
                  "continuesDraft": true,
                  "action": "update_address",
                  "providedParams": {"state": "Bristol"},
                  "confidence": 0.98,
                  "reason": "16 Dairy Drive"
                }
                """)
            .build());

        ActionDraftContinuationAnalyzer.AnalysisOutcome outcome =
            analyzer.analyze(context("The state or region is Bristol."));

        assertThat(outcome.continued()).isTrue();
        assertThat(outcome.response().getIntents()).hasSize(1);
        assertThat(outcome.response().getIntents().getFirst().getType())
            .isEqualTo(IntentType.ACTION);
        assertThat(
            outcome.response().getIntents().getFirst().getActionParams()
        ).containsExactly(Map.entry("state", "Bristol"));
        assertThat(outcome.suppliedParameterNames())
            .containsExactly("state");
        assertThat(outcome.diagnostics().toString())
            .doesNotContain("16 Dairy Drive");

        ArgumentCaptor<AIGenerationRequest> requestCaptor =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(
            requestCaptor.capture(),
            eq(LlmPurpose.ORCHESTRATION)
        );
        AIGenerationRequest request = requestCaptor.getValue();
        assertThat(request.getSystemPrompt())
            .contains("update_address", "state", "postalCode")
            .doesNotContain("16 Dairy Drive");
        assertThat(request.getPrompt())
            .contains("The state or region is Bristol.")
            .doesNotContain("16 Dairy Drive");
        assertThat(request.getMessages())
            .extracting(AIChatMessage::getContent)
            .contains(
                "Please review my account policies.",
                "Your account policy review is complete."
            );
    }

    @Test
    void shouldLeaveUnrelatedQuestionForNormalIntentExtraction() {
        when(aiCoreService.generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(AIGenerationResponse.builder()
            .content("""
                {
                  "continuesDraft": false,
                  "action": null,
                  "providedParams": {},
                  "confidence": 0.99,
                  "reason": "INDEPENDENT_INFORMATION_REQUEST"
                }
                """)
            .build());

        ActionDraftContinuationAnalyzer.AnalysisOutcome outcome =
            analyzer.analyze(context("What plan am I currently using?"));

        assertThat(outcome.evaluated()).isTrue();
        assertThat(outcome.continued()).isFalse();
        assertThat(outcome.response()).isNull();
        assertThat(outcome.failureType()).isNull();
    }

    @Test
    void shouldRejectUnknownOrHiddenParametersFromModelOutput() {
        when(aiCoreService.generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(AIGenerationResponse.builder()
            .content("""
                {
                  "continuesDraft": true,
                  "action": "update_address",
                  "providedParams": {
                    "state": "Bristol",
                    "subscriptionId": "invented"
                  },
                  "confidence": 0.99,
                  "reason": "SUPPLIES_FIELD"
                }
                """)
            .build());

        ActionDraftContinuationAnalyzer.AnalysisOutcome outcome =
            analyzer.analyze(context("The state is Bristol."));

        assertThat(outcome.continued()).isFalse();
        assertThat(outcome.failureType()).isEqualTo("VALIDATION_ERROR");
        assertThat(outcome.response()).isNull();
        verify(aiCoreService, times(2)).generateContent(
            any(AIGenerationRequest.class),
            eq(LlmPurpose.ORCHESTRATION)
        );
    }

    private PipelineContext context(String currentMessage) {
        return PipelineContext.from(
            currentMessage,
            OrchestrationContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .build()
        ).toBuilder()
            .historyMessages(List.of(
                AIChatMessage.user("Update my address to 16 Dairy Drive."),
                AIChatMessage.assistant(
                    "Please provide city, state, postal code, and country."
                ),
                AIChatMessage.user("Please review my account policies."),
                AIChatMessage.assistant(
                    "Your account policy review is complete."
                )
            ))
            .actionDraftContinuation(
                new ActionDraftContinuation(
                    "update_address",
                    Map.of("street", "16 Dairy Drive"),
                    List.of("city", "state", "postalCode", "country")
                )
            )
            .build();
    }

    private AIActionMetaData actionMetadata() {
        AIActionParamSchema publicString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(true)
            .build();
        AIActionParamSchema hiddenString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(false)
            .visibility("INTERNAL")
            .build();
        return AIActionMetaData.builder()
            .name("update_address")
            .parameters(Map.of(
                "street", "Street address",
                "city", "City",
                "state", "State or region",
                "postalCode", "Postal code",
                "country", "Country",
                "subscriptionId", "Application-owned subscription"
            ))
            .parameterSchemas(Map.of(
                "street", publicString,
                "city", publicString,
                "state", publicString,
                "postalCode", publicString,
                "country", publicString,
                "subscriptionId", hiddenString
            ))
            .requiredParameters(Set.of(
                "street",
                "city",
                "state",
                "postalCode",
                "country",
                "subscriptionId"
            ))
            .build();
    }
}
