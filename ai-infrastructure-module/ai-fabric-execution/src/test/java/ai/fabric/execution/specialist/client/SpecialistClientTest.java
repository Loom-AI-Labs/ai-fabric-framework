package ai.fabric.execution.specialist.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SpecialistClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void defaultInteractiveExecutionForwardsTheTypedInvocation() {
        SpecialistId specialistId = SpecialistId.of("support", "1");
        SpecialistClient<String, String> client = mock(
            SpecialistClient.class,
            CALLS_REAL_METHODS
        );
        AIInteractiveExecutionGateway gateway = mock(
            AIInteractiveExecutionGateway.class
        );
        AIExecutionResult<String> expected = mock(AIExecutionResult.class);
        TrustedExecutionContext context = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "deployment-1",
            Set.of("specialist:support@1"),
            null,
            Instant.parse("2026-07-30T00:00:00Z")
        );
        ConversationBinding binding = new ConversationBinding(
            "user-1",
            "conversation-1"
        );
        SpecialistInvocation<String> invocation = new SpecialistInvocation<>(
            "Help me",
            context,
            binding,
            Instant.parse("2026-07-30T00:00:30Z"),
            "turn-1"
        );
        when(client.specialistId()).thenReturn(specialistId);
        doReturn(expected).when(gateway).execute(any());

        AIExecutionResult<String> actual = client.executeInteractive(
            invocation,
            gateway
        );

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<AIExecutionRequest<String>> request =
            ArgumentCaptor.forClass(AIExecutionRequest.class);
        verify(gateway).execute(request.capture());
        assertThat(request.getValue()).isEqualTo(
            new AIExecutionRequest<>(
                specialistId,
                "Help me",
                context,
                binding,
                Instant.parse("2026-07-30T00:00:30Z"),
                "turn-1"
            )
        );
    }
}
