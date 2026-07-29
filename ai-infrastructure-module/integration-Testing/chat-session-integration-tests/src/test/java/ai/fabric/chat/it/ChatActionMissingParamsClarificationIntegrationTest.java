package ai.fabric.chat.it;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.it.actions.ConfirmableEchoActionHandler;
import ai.fabric.chat.it.actions.ConfirmableTransferActionHandler;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.util.ActionDraftMetadata;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.ActionDraftContinuationAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class ChatActionMissingParamsClarificationIntegrationTest {

    @Autowired
    private Pipeline pipeline;

    @Autowired
    private ChatSessionService chatSessionService;

    @MockitoBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockitoBean
    private ActionDraftContinuationAnalyzer actionDraftContinuationAnalyzer;

    @BeforeEach
    void reset() {
        ConfirmableEchoActionHandler.resetExecutions();
        ConfirmableTransferActionHandler.resetExecutions();
    }

    @Test
    void shouldAskForMissingRequiredParamsThenConfirmAndExecute() {
        MultiIntentResponse missingParams = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action(ConfirmableEchoActionHandler.ACTION_NAME)
                .actionParams(Map.of())
                .confidence(0.9)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        MultiIntentResponse completedParams = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action(ConfirmableEchoActionHandler.ACTION_NAME)
                .actionParams(Map.of("message", "hello"))
                .confidence(0.9)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        MultiIntentResponse confirmYes = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.CONFIRMATION_POSITIVE)
                .intent("confirm")
                .confidence(1.0)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(missingParams, confirmYes);
        when(actionDraftContinuationAnalyzer.analyze(any()))
            .thenReturn(
                ActionDraftContinuationAnalyzer.AnalysisOutcome.continued(
                    completedParams,
                    java.util.Set.of("message"),
                    1,
                    "test-model"
                )
            );

        String ownerId = "missing-params-user";
        String conversationId = "chat-" + UUID.randomUUID();
        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult first = pipeline.execute("i want to buy this", orch);
        assertThat(first).isNotNull();
        assertThat(first.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(first.getMessage()).contains("message");
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();

        ChatSession sessionAfterFirst = chatSessionService.getSession(conversationId, ownerId);
        assertThat(sessionAfterFirst.getSessionMetadata()).isNotNull();
        assertThat(sessionAfterFirst.getSessionMetadata().get(ActionDraftMetadata.METADATA_KEY_DRAFT)).isNotNull();

        OrchestrationResult second = pipeline.execute("message=hello", orch);
        assertThat(second).isNotNull();
        assertThat(second.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();

        ChatSession sessionAfterSecond = chatSessionService.getSession(conversationId, ownerId);
        assertThat(sessionAfterSecond.getSessionMetadata()).isNotNull();
        assertThat(sessionAfterSecond.getSessionMetadata().get(ActionDraftMetadata.METADATA_KEY_DRAFT)).isNull();
        assertThat(sessionAfterSecond.getSessionMetadata().get(ConfirmationStack.METADATA_KEY_STACK)).isNotNull();

        OrchestrationResult third = pipeline.execute("yes", orch);
        assertThat(third).isNotNull();
        assertThat(third.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(third.isSuccess()).isTrue();
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isEqualTo(1);

        ChatSession sessionAfterThird = chatSessionService.getSession(conversationId, ownerId);
        assertThat(sessionAfterThird.getSessionMetadata()).isNotNull();
        Object confirmationRaw = sessionAfterThird.getSessionMetadata().get(ConfirmationStack.METADATA_KEY_STACK);
        if (confirmationRaw instanceof List<?> list) {
            assertThat(list).isEmpty();
        } else {
            assertThat(confirmationRaw).isNull();
        }
    }

    @Test
    void shouldPreservePreviouslyCollectedParamsAcrossFollowUpTurns() {
        MultiIntentResponse reasonOnly = actionResponse(
            ConfirmableTransferActionHandler.ACTION_NAME,
            Map.of("reason", "duplicate charge")
        );
        MultiIntentResponse amountOnly = actionResponse(
            ConfirmableTransferActionHandler.ACTION_NAME,
            Map.of("amount", 20)
        );
        MultiIntentResponse confirmYes = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.CONFIRMATION_POSITIVE)
                .intent("confirm")
                .confidence(1.0)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();
        when(intentQueryExtractor.extract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(reasonOnly, confirmYes);
        when(actionDraftContinuationAnalyzer.analyze(any()))
            .thenReturn(
                ActionDraftContinuationAnalyzer.AnalysisOutcome.continued(
                    amountOnly,
                    java.util.Set.of("amount"),
                    1,
                    "test-model"
                )
            );

        String ownerId = "draft-continuation-user";
        String conversationId = "chat-" + UUID.randomUUID();
        OrchestrationContext orchestrationContext =
            OrchestrationContext.builder()
                .userId(ownerId)
                .conversationId(conversationId)
                .build();

        OrchestrationResult first = pipeline.execute(
            "Transfer this because it was a duplicate charge.",
            orchestrationContext
        );
        assertThat(first.getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(first.getMessage()).contains("amount");

        OrchestrationResult second = pipeline.execute(
            "The amount is 20.",
            orchestrationContext
        );
        assertThat(second.getType())
            .isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(second.getMessage())
            .contains("20")
            .contains("duplicate charge");
        assertThat(ConfirmableTransferActionHandler.getExecutionCount())
            .isZero();

        OrchestrationResult third = pipeline.execute(
            "yes",
            orchestrationContext
        );
        assertThat(third.getType())
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(ConfirmableTransferActionHandler.getExecutionCount())
            .isEqualTo(1);
        assertThat(ConfirmableTransferActionHandler.getLastAmount())
            .isEqualByComparingTo(new BigDecimal("20"));
        assertThat(ConfirmableTransferActionHandler.getLastReason())
            .isEqualTo("duplicate charge");
    }

    private MultiIntentResponse actionResponse(
        String action,
        Map<String, Object> params
    ) {
        return MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action(action)
                .actionParams(params)
                .confidence(0.9d)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();
    }
}
