package ai.fabric.provider.springai;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.DecisionRecoveryProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.DecisionRecoveryBudget;
import ai.fabric.intent.orchestration.pipeline.steps.InternalDecisionRecoveryService;
import ai.fabric.intent.orchestration.pipeline.steps.InternalDecisionRecoveryStep;
import ai.fabric.intent.orchestration.pipeline.steps.IntentExtractionStep;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Explicit qualification: controlled routing failures, real Spring AI answer generation. */
@EnabledIfEnvironmentVariable(named = "AI_FABRIC_REAL_API_RECOVERY", matches = "true")
class DecisionRecoveryRealProviderTest {
    private final AICoreService core = mock(AICoreService.class);
    private final DecisionRecoveryProperties settings = new DecisionRecoveryProperties();
    private final InternalDecisionRecoveryService service = new InternalDecisionRecoveryService(settings, core,
        new AIServiceConfig(), new IntentExtractionJsonSupport(new ObjectMapper()),
        new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()), new PromptRenderer());

    private void useRealAnswers() {
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).as("An explicit test credential is required").isTrue();
        String model = System.getenv("OPENAI_MODEL");
        assertThat(model != null && !model.isBlank()).as("Use an explicitly qualified answer model").isTrue();
        var config = new AIProviderConfig();
        config.getOpenai().setEnabled(true);
        config.getOpenai().setApiKey(key);
        config.getOpenai().setModel(model);
        config.getOpenai().setMaxTokens(1000);
        config.getOpenai().setTimeout(60);
        var resolver = new SpringAiModelResolver(config);
        var provider = new SpringAiChatProvider("openai", resolver);
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenAnswer(call -> provider.generateContent(call.getArgument(0, AIGenerationRequest.class)));
    }

    @Test void exhaustedRoutingStillAnswersFromExplicitlyAttachedItemAndHistory() {
        useRealAnswers();
        settings.setMaxRepairRounds(2);
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder().content("{\"requiresRetrieval\":true,\"vectorSpaces\":[]}").build());
        var context = PipelineContext.from("Compare their prices and tell me the saving.", OrchestrationContext.forUser("qualification"))
            .toBuilder().historyMessages(List.of(AIChatMessage.user("Compare the Harbor desk and chair.")))
            .pinnedTargetsContext("User attached: Harbor desk priceGbp=499; Harbor chair priceGbp=249. No SKU is available.").build();
        var failure = InternalDecisionRecoveryService.unresolved("MISSING_SOURCE_SELECTION", Map.of());
        var intent = Intent.builder().type(IntentType.INFORMATION).requiresRetrieval(true).build();
        assertThat(service.repairRouting(intent, context, failure, List.of("document"), 1, true)).isFalse();
        var result = service.finalContextAnswer(context, failure);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("499", "249", "250");
        assertThat(result.getMetadata()).containsEntry("newRetrievalPerformedByFallback", false);
        service.finalContextAnswer(context, failure);
        verify(core, times(2)).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.GENERATION));
        System.out.println("RECOVERY_REAL_PROVIDER attached-comparison PASS: " + result.getMessage());
    }

    @Test void noCurrentFactsProducesAnHonestAnswerNotAnInternalDomainQuestion() {
        useRealAnswers();
        settings.setMaxRepairRounds(0);
        var context = PipelineContext.from("What is the exact current stock of Harbor desk?",
            OrchestrationContext.forUser("qualification"));
        var result = service.finalContextAnswer(context,
            InternalDecisionRecoveryService.unresolved("MISSING_SOURCE_SELECTION", Map.of()));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isNotBlank();
        assertThat(result.getMetadata()).containsEntry("answerSource", "AVAILABLE_CONTEXT_RECOVERY");
        verify(core, never()).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.GENERATION));
        System.out.println("RECOVERY_REAL_PROVIDER no-current-facts REVIEW: " + result.getMessage());
    }

    @Test void invalidActionCategoryAfterRepairsStillGetsARealContextAnswerWithoutExecution() {
        useRealAnswers();
        var engine = mock(ProgressiveIntentExtractionEngine.class);
        when(engine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class), any(SemanticRecoveryPolicy.class),
            any(DecisionRecoveryBudget.class), any(DecisionRecoveryProperties.class)))
            .thenReturn(new ProgressiveIntentExtractionEngine.ExtractionOutput(null, Map.of(),
                new ProgressiveIntentExtractionEngine.ExtractionFailure("INTENT_EXTRACTION_FAILED",
                    "Action parameter has an unsupported value: category", false,
                    List.of(IntentExtractionValidator.IssueCode.ACTION_PARAMETER_CONTRACT_INVALID))));
        var beans = new StaticListableBeanFactory(Map.of("engine", engine, "recovery", service));
        var step = new IntentExtractionStep(mock(IntentQueryExtractor.class), beans.getBeanProvider(ProgressiveIntentExtractionEngine.class));
        ReflectionTestUtils.setField(step, "decisionRecoveryProvider", beans.getBeanProvider(InternalDecisionRecoveryService.class));
        var actor = OrchestrationContext.forUser("qualification");
        actor.setConversationId("qualification-conversation");
        var failed = step.process(PipelineContext.from("Help me choose furniture for my home office.", actor)
            .toBuilder().historyMessages(List.of(AIChatMessage.user("I need an adjustable desk and an ergonomic chair."))).build());
        assertThat(InternalDecisionRecoveryService.isUnresolved(failed.getEarlyTerminationResult())).isTrue();
        assertThat(failed.getIntentResponse()).isNull();
        var answer = new InternalDecisionRecoveryStep(service).process(failed).getEarlyTerminationResult();
        assertThat(answer.isSuccess()).isTrue();
        assertThat(answer.getMetadata()).containsEntry("answerSource", "AVAILABLE_CONTEXT_RECOVERY");
        assertThat(answer.getMessage()).isNotBlank();
        verify(core, times(1)).generateContent(any(), eq(LlmPurpose.GENERATION));
        verifyNoMoreInteractions(core);
        System.out.println("RECOVERY_REAL_PROVIDER invalid-category REVIEW: " + answer.getMessage());
    }
}
