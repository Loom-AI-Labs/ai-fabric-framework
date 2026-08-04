package com.ai.fabric.realapps.mcpops.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.ai.fabric.realapps.mcpops.specialist.McpOperationsSpecialists;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("smoke")
class McpOperationsServiceTest {

    @Autowired
    private McpDemoSessionService sessions;

    @Autowired
    private McpOperationsService operations;

    @Autowired
    private McpActionExecutor executor;

    @Autowired
    private AIActionRegistry actions;

    @Autowired
    private SpecialistRegistry specialists;

    @Test
    void loadsExactCatalogAndManifestContracts() {
        assertThat(actions.findMetadata(McpOperationsService.STATUS_ACTION))
            .isPresent();
        assertThat(actions.findMetadata(McpOperationsService.INCIDENTS_ACTION))
            .isPresent();
        assertThat(actions.findMetadata(McpOperationsService.RESTART_ACTION))
            .get()
            .extracting(metadata -> metadata.getAccessMode())
            .isEqualTo(ActionAccessMode.WRITE_ONLY);
        assertThat(specialists.findRegistered(McpOperationsSpecialists.OPERATIONS))
            .isPresent();
        assertThat(actions.findMetadata(McpOperationsService.RESTART_ACTION))
            .get()
            .satisfies(metadata -> assertThat(
                metadata.getParameterSchemas()
                    .get("expectedRevision")
                    .getResolveFrom()
            )
                .containsEntry("source", "READ_ACTION")
                .containsEntry("actionName", McpOperationsService.STATUS_ACTION)
                .containsEntry("resultPath", "revision"));
    }

    @Test
    void isolatesStateAuditsRemoteShapeAndFailsClosedOnWrongServer() {
        McpDemoSessionService.SessionView session = sessions.create();

        McpOperationsService.SandboxState state = operations.state(
            session.sessionId()
        );

        assertThat(state.selectedService()).isEqualTo("checkout");
        assertThat(state.status())
            .containsEntry("status", "DEGRADED")
            .containsEntry("revision", 1);
        assertThat(state.incidents()).hasSize(1);
        assertThat(state.timeline())
            .extracting(McpInvocationAuditService.AuditView::serverRef)
            .containsOnly(McpOperationsService.SERVER_REF);
        assertThat(state.timeline())
            .extracting(McpInvocationAuditService.AuditView::serviceName)
            .containsOnly("checkout");

        McpOperationsService.BindingCanary canary = operations.bindingCanary(
            session.sessionId()
        );
        assertThat(canary.passed()).isTrue();
        assertThat(canary.errorCode()).isEqualTo("MCP_TOOL_NOT_AVAILABLE");
        assertThat(canary.writeDelta()).isZero();
    }

    @Test
    void localSmokeExecutorEnforcesExactServerAndOptimisticRevision() {
        String sandboxId = "mcp-demo-test";
        ActionContext context = new ActionContext(
            OrchestrationContext.builder()
                .requestId("request-1")
                .userId(sandboxId)
                .build(),
            null
        );
        Map<String, Object> params = Map.of(
            "sandboxId", sandboxId,
            "serviceName", "checkout",
            "expectedRevision", 1
        );

        ActionResult restarted = executor.execute(
            McpOperationsService.RESTART_ACTION,
            ActionAccessMode.WRITE_ONLY,
            params,
            context,
            config(
                McpOperationsService.SERVER_REF,
                McpOperationsService.RESTART_ACTION
            )
        );
        ActionResult staleReplay = executor.execute(
            McpOperationsService.RESTART_ACTION,
            ActionAccessMode.WRITE_ONLY,
            params,
            context,
            config(
                McpOperationsService.SERVER_REF,
                McpOperationsService.RESTART_ACTION
            )
        );

        assertThat(restarted.isSuccess()).isTrue();
        assertThat(restarted.getData().toMap())
            .containsEntry("restarted", true)
            .containsEntry("revision", 2);
        assertThat(staleReplay.isSuccess()).isFalse();
        assertThat(staleReplay.getErrorCode()).isEqualTo("REVISION_CONFLICT");
    }

    private Map<String, Object> config(String serverRef, String toolName) {
        return Map.of(
            "adapterType", "mcp-tool",
            "execution", Map.of(
                "mcp", Map.of(
                    "serverRef", serverRef,
                    "toolName", toolName,
                    "argumentTemplate", Map.of()
                )
            )
        );
    }
}
