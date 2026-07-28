package ai.fabric.intent.orchestration.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrchestrationRequestTest {

    @Test
    void legacyInteractiveRequestPreservesIdentifierValidation() {
        OrchestrationRequest request = OrchestrationRequest.interactive(
            "hello",
            OrchestrationContext.builder().build()
        );

        assertThatThrownBy(request::validateForExecution)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userId")
            .hasMessageContaining("sessionId");
    }

    @Test
    void trustedApplicationRequestDoesNotRequireSyntheticUserOrSession() {
        OrchestrationRequest request = new OrchestrationRequest(
            "inspect account account-1",
            OrchestrationContext.builder().build(),
            TrustedExecutionContext.application(
                "account-resolver",
                new ExecutionSubjectRef("ACCOUNT", "account-1"),
                "tenant-1",
                Set.of("account:read")
            ),
            ConversationPersistencePolicy.NEVER
        );

        assertThatCode(request::validateForExecution).doesNotThrowAnyException();
        assertThat(request.conversationPersistencePolicy())
            .isEqualTo(ConversationPersistencePolicy.NEVER);
        assertThat(request.executionSource().name()).isEqualTo("APPLICATION");
    }

    @Test
    void normalizesServerOwnedResponseInstructionsSeparatelyFromModelInput() {
        OrchestrationRequest request = new OrchestrationRequest(
            " inspect account ",
            OrchestrationContext.forUser("user-1"),
            null,
            ConversationPersistencePolicy.CONVERSATION,
            null,
            " inspect account ",
            " return JSON only "
        );

        assertThat(request.modelInput()).isEqualTo("inspect account");
        assertThat(request.conversationInput()).isEqualTo("inspect account");
        assertThat(request.responseInstructions()).isEqualTo("return JSON only");
    }

    @Test
    void rejectsBlankModelInput() {
        assertThatThrownBy(() -> OrchestrationRequest.interactive(
            " ",
            OrchestrationContext.forUser("user-1")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("modelInput must not be blank");
    }
}
