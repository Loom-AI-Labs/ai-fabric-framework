package ai.fabric.intent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.intent.orchestration.control.OrchestrationTurnGuard;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RAGOrchestratorControlGuardTest {

    @Test
    void verifiesLeaseBeforePipelineAndBeforeReturningResult() {
        Pipeline pipeline = mock(Pipeline.class);
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-1")
            .orchestrationTurnGuard(guard)
            .build();
        OrchestrationResult expected = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("ok")
            .build();
        when(pipeline.execute("hello", context)).thenReturn(expected);

        OrchestrationResult result = new RAGOrchestrator(pipeline).orchestrate("hello", context);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(guard, pipeline);
        order.verify(guard).verifyBeforeOrchestration();
        order.verify(pipeline).execute("hello", context);
        order.verify(guard).verifyBeforeResultCommit(expected);
    }

    @Test
    void preservesStructuredReadOnlyConversationPolicy() {
        Pipeline pipeline = mock(Pipeline.class);
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-1")
            .orchestrationTurnGuard(guard)
            .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "hello",
            context,
            null,
            ConversationPersistencePolicy.READ_ONLY
        );
        OrchestrationResult expected = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("ok")
            .build();
        when(pipeline.execute(request)).thenReturn(expected);

        OrchestrationResult result = new RAGOrchestrator(pipeline).orchestrate(request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(guard, pipeline);
        order.verify(guard).verifyBeforeOrchestration();
        order.verify(pipeline).execute(request);
        order.verify(guard).verifyBeforeResultCommit(expected);
    }
}
