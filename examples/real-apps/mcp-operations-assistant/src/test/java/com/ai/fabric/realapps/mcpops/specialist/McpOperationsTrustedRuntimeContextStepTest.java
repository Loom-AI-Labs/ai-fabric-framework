package com.ai.fabric.realapps.mcpops.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.ai.fabric.realapps.mcpops.service.McpDemoSessionService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpOperationsTrustedRuntimeContextStepTest {

    private final McpDemoSessionService sessions = mock(
        McpDemoSessionService.class
    );
    private final McpOperationsTrustedRuntimeContextStep step =
        new McpOperationsTrustedRuntimeContextStep(sessions);

    @Test
    void replacesCallerMetadataWithBackendSelectedService() {
        when(sessions.active("mcp-demo-1")).thenReturn(active("checkout"));
        PipelineContext input = specialistContext(
            Map.of("serviceName", "payments")
        );

        PipelineContext result = step.process(input);

        assertThat(result.getMetadata())
            .containsEntry("serviceName", "checkout")
            .containsKey("mcpOperationsRuntimeContext");
        verify(sessions).active("mcp-demo-1");
    }

    @Test
    void rejectsAConversationThatIsNotOwnedByTheActiveSession() {
        when(sessions.active("mcp-demo-1")).thenReturn(
            new McpDemoSessionService.ActiveSession(
                "mcp-demo-1",
                "different-conversation",
                "checkout",
                Instant.now().plusSeconds(300)
            )
        );

        assertThatThrownBy(() -> step.process(specialistContext(Map.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not own this conversation");
    }

    @Test
    void ignoresNonSpecialistRequests() {
        OrchestrationContext orchestration = OrchestrationContext.builder()
            .userId("mcp-demo-1")
            .conversationId("conversation-1")
            .position("operations")
            .build();
        PipelineContext input = PipelineContext.from(
            new OrchestrationRequest(
                "status",
                orchestration,
                null,
                ConversationPersistencePolicy.NEVER
            )
        );

        assertThat(step.process(input)).isSameAs(input);
        verifyNoInteractions(sessions);
    }

    private PipelineContext specialistContext(Map<String, Object> metadata) {
        OrchestrationContext orchestration = OrchestrationContext.builder()
            .userId("mcp-demo-1")
            .conversationId("conversation-1")
            .position("operations")
            .build();
        return PipelineContext.from(
            new OrchestrationRequest(
                "status",
                orchestration,
                null,
                ConversationPersistencePolicy.NEVER,
                null,
                null,
                null,
                OrchestrationRequestPurpose.SPECIALIST
            )
        ).toBuilder().metadata(metadata).build();
    }

    private McpDemoSessionService.ActiveSession active(String serviceName) {
        return new McpDemoSessionService.ActiveSession(
            "mcp-demo-1",
            "conversation-1",
            serviceName,
            Instant.now().plusSeconds(300)
        );
    }
}
