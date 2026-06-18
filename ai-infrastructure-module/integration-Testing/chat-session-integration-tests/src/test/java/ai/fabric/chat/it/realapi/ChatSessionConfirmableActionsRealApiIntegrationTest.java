package ai.fabric.chat.it.realapi;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import ai.fabric.chat.it.actions.ConfirmableEchoActionHandler;
import ai.fabric.chat.it.actions.ConfirmableUpperEchoActionHandler;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("realapi")
class ChatSessionConfirmableActionsRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    @BeforeEach
    void resetCounters() {
        ConfirmableEchoActionHandler.resetExecutions();
        ConfirmableUpperEchoActionHandler.resetExecutions();
    }

    @Test
    void shouldRequireConfirmationThenExecuteOnPositiveConfirmation() {
        String ownerId = "confirm-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult first = orchestrator.orchestrate(
            "Use action '" + ConfirmableEchoActionHandler.ACTION_NAME + "' with params: {\"message\":\"hello\"}.",
            ctx
        );

        assertThat(first).isNotNull();
        assertThat(first.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(first.isSuccess()).isFalse();
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();
        assertThat(first.getData()).isNotNull();
        assertThat(first.getData().get("action")).isEqualTo(ConfirmableEchoActionHandler.ACTION_NAME);
        assertThat(first.getData().get("confirmationRequired")).isEqualTo(true);
        assertThat(getPendingStackSize(conversationId, ownerId)).isEqualTo(1);

        OrchestrationResult confirmed = orchestrator.orchestrate("Yes, proceed.", ctx);

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(confirmed.isSuccess()).isTrue();

        Map<String, Object> data = confirmed.getData();
        assertThat(data).isNotNull();
        assertThat(data.get("action")).isEqualTo(ConfirmableEchoActionHandler.ACTION_NAME);
        assertThat(data.get("actionResult")).isInstanceOf(ActionResult.class);
        assertThat(((ActionResult) data.get("actionResult")).isSuccess()).isTrue();
        assertThat(((ActionResult) data.get("actionResult")).getData()).isNotNull();
        assertThat(((ActionResult) data.get("actionResult")).getData().toMap().get("echo")).isEqualTo("hello");

        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isEqualTo(1);
        assertThat(getPendingStackSize(conversationId, ownerId)).isZero();
    }

    @Test
    void shouldCancelPendingActionOnNegativeConfirmation() {
        String ownerId = "cancel-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult first = orchestrator.orchestrate(
            "Use action '" + ConfirmableEchoActionHandler.ACTION_NAME + "' with params: {\"message\":\"hello\"}.",
            ctx
        );

        assertThat(first).isNotNull();
        assertThat(first.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(first.isSuccess()).isFalse();
        assertThat(getPendingStackSize(conversationId, ownerId)).isEqualTo(1);

        OrchestrationResult cancelled = orchestrator.orchestrate("No, cancel.", ctx);

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(cancelled.isSuccess()).isTrue();
        assertThat(cancelled.getMessage()).isNotBlank();

        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();
        assertThat(getPendingStackSize(conversationId, ownerId)).isZero();
    }

    @Test
    void shouldConfirmAndExecuteActionsInLifoOrderWhenMultiplePending() {
        String ownerId = "stack-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult a = orchestrator.orchestrate(
            "Use action '" + ConfirmableEchoActionHandler.ACTION_NAME + "' with params: {\"message\":\"one\"}.",
            ctx
        );
        assertThat(a).isNotNull();
        assertThat(a.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(getPendingStackSize(conversationId, ownerId)).isEqualTo(1);

        OrchestrationResult b = orchestrator.orchestrate(
            "Use action '" + ConfirmableUpperEchoActionHandler.ACTION_NAME + "' with params: {\"message\":\"two\"}.",
            ctx
        );
        assertThat(b).isNotNull();
        assertThat(b.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(getPendingStackSize(conversationId, ownerId)).isEqualTo(2);

        OrchestrationResult confirmTop = orchestrator.orchestrate("Yes.", ctx);
        assertThat(confirmTop).isNotNull();
        assertThat(confirmTop.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(confirmTop.isSuccess()).isTrue();
        assertThat(confirmTop.getData()).isNotNull();
        assertThat(confirmTop.getData().get("action")).isEqualTo(ConfirmableUpperEchoActionHandler.ACTION_NAME);
        assertThat(getPendingStackSize(conversationId, ownerId)).isEqualTo(1);

        OrchestrationResult confirmNext = orchestrator.orchestrate("Yes.", ctx);
        assertThat(confirmNext).isNotNull();
        assertThat(confirmNext.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(confirmNext.isSuccess()).isTrue();
        assertThat(confirmNext.getData()).isNotNull();
        assertThat(confirmNext.getData().get("action")).isEqualTo(ConfirmableEchoActionHandler.ACTION_NAME);
        assertThat(getPendingStackSize(conversationId, ownerId)).isZero();

        assertThat(ConfirmableUpperEchoActionHandler.getExecutionCount()).isEqualTo(1);
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isEqualTo(1);

        List<String> executions = ConfirmableEchoActionHandler.getExecutionOrder();
        assertThat(executions).containsExactly(
            ConfirmableUpperEchoActionHandler.ACTION_NAME,
            ConfirmableEchoActionHandler.ACTION_NAME
        );
    }

    private int getPendingStackSize(String conversationId, String ownerId) {
        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session).isNotNull();
        Map<String, Object> metadata = session.getSessionMetadata();
        if (metadata == null) {
            return 0;
        }
        Object raw = metadata.get(ConfirmationStack.METADATA_KEY_STACK);
        if (raw instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }
}
