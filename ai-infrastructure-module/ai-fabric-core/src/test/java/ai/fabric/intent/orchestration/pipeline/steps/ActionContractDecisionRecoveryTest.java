package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.DecisionRecoveryProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.DecisionRecoveryBudget;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ActionContractDecisionRecoveryTest {
    private final AICoreService core = mock(AICoreService.class);
    private final InternalDecisionRecoveryService recovery = new InternalDecisionRecoveryService(
        new DecisionRecoveryProperties(), core, new AIServiceConfig(),
        new IntentExtractionJsonSupport(new ObjectMapper()),
        new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties()),
        new PromptRenderer());
    private final ProgressiveIntentExtractionEngine engine = mock(ProgressiveIntentExtractionEngine.class);
    private final IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);

    private PipelineContext failedExtraction(String reason, List<IntentExtractionValidator.IssueCode> issues) {
        when(engine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class), any(SemanticRecoveryPolicy.class),
            any(DecisionRecoveryBudget.class), any(DecisionRecoveryProperties.class)))
            .thenReturn(new ProgressiveIntentExtractionEngine.ExtractionOutput(null, Map.of(),
                new ProgressiveIntentExtractionEngine.ExtractionFailure(reason, "Invalid model action category.", false, issues)));
        var beans = new StaticListableBeanFactory(Map.of("engine", engine, "recovery", recovery));
        var step = new IntentExtractionStep(extractor, beans.getBeanProvider(ProgressiveIntentExtractionEngine.class));
        ReflectionTestUtils.setField(step, "decisionRecoveryProvider", beans.getBeanProvider(InternalDecisionRecoveryService.class));
        var actor = OrchestrationContext.forUser("qualification");
        actor.setConversationId("qualification-conversation");
        return step.process(PipelineContext.from("Help me find furniture options.", actor)
            .toBuilder().historyMessages(List.of(AIChatMessage.user("I need an adjustable desk."))).build());
    }

    @Test
    void exhaustedInvalidActionParametersReachOneFinalContextAnswerWithoutBecomingExecutable() {
        when(core.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("I can help you compare desk choices, but have not verified current stock.").build());
        var failed = failedExtraction("INTENT_EXTRACTION_FAILED", List.of(IntentExtractionValidator.IssueCode.ACTION_PARAMETER_CONTRACT_INVALID));
        assertThat(InternalDecisionRecoveryService.isUnresolved(failed.getEarlyTerminationResult())).isTrue();
        assertThat(failed.getEarlyTerminationResult().getData()).containsEntry("issueCodes", List.of("ACTION_PARAMETER_CONTRACT_INVALID"));
        assertThat(failed.isShouldTerminate()).isTrue();
        assertThat(failed.getIntentResponse()).isNull();

        var result = new InternalDecisionRecoveryStep(recovery).process(failed).getEarlyTerminationResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMetadata()).containsEntry("answerSource", "AVAILABLE_CONTEXT_RECOVERY");
        assertThat(result.getData()).containsEntry("requiresRetrieval", false);
        var request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getMessages()).hasSize(1);
        assertThat(request.getValue().getPrompt()).contains("Help me find furniture options.");
        verifyNoMoreInteractions(core);
        verifyNoInteractions(extractor);
    }

    @Test
    void providerFailureWithTheSameValidationIssueRemainsAHardFailure() {
        var failed = failedExtraction("INTENT_PROVIDER_FAILED", List.of(IntentExtractionValidator.IssueCode.ACTION_PARAMETER_CONTRACT_INVALID));
        assertThat(InternalDecisionRecoveryService.isUnresolved(failed.getEarlyTerminationResult())).isFalse();
        assertThat(new InternalDecisionRecoveryStep(recovery).process(failed).getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.ERROR);
        verifyNoInteractions(core);
    }

    @Test
    void unregisteredActionOrInvalidConfirmationIsNotHiddenByAParameterIssue() {
        for (var issue : List.of(IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED,
            IntentExtractionValidator.IssueCode.CONFIRMATION_WITHOUT_PENDING_ACTION)) {
            var failed = failedExtraction("INTENT_EXTRACTION_FAILED", List.of(IntentExtractionValidator.IssueCode.ACTION_PARAMETER_CONTRACT_INVALID, issue));
            assertThat(InternalDecisionRecoveryService.isUnresolved(failed.getEarlyTerminationResult())).isFalse();
        }
        verifyNoInteractions(core);
    }
}
