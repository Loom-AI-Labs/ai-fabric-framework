package ai.fabric.chat.it.realapi;

import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import ai.fabric.chat.it.actions.ConfirmableEchoActionHandler;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
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
class ChatSessionMissingActionParamsRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @BeforeEach
    void resetCounters() {
        ConfirmableEchoActionHandler.resetExecutions();
    }

    @Test
    void shouldReturnClarificationRequiredWhenActionIsMissingRequiredParams() {
        String ownerId = "missing-params-realapi-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult first = orchestrator.orchestrate(
            "Use action '" + ConfirmableEchoActionHandler.ACTION_NAME + "'.",
            ctx
        );

        assertThat(first).isNotNull();
        assertThat(first.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(first.getMessage()).contains("message");
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();
    }
}
