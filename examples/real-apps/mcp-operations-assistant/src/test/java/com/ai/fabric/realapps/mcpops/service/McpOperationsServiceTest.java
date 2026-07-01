package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.connector.McpActionExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpOperationsServiceTest {

    @Test
    void readOnlyToolExecutesAndSanitizesHiddenContext() {
        McpOperationsService service = new McpOperationsService(new LocalOperationsMcpActionExecutor());

        McpOperationsService.ToolExecutionResult result = service.execute(new McpOperationsService.ToolExecutionRequest(
            "service_health",
            Map.of("service", "checkout"),
            false
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.confirmationRequired()).isFalse();
        assertThat(result.data()).containsEntry("status", "HEALTHY");
        assertThat(result.data()).doesNotContainKey("_hiddenConnectorTrace");
    }

    @Test
    void writeToolRequiresConfirmationBeforeExecution() {
        McpActionExecutor executor = mock(McpActionExecutor.class);
        when(executor.isAvailable()).thenReturn(true);
        McpOperationsService service = new McpOperationsService(executor);

        McpOperationsService.ToolExecutionResult gated = service.execute(new McpOperationsService.ToolExecutionRequest(
            "deployment_rollback",
            Map.of("service", "checkout"),
            false
        ));

        assertThat(gated.success()).isFalse();
        assertThat(gated.confirmationRequired()).isTrue();
    }

    @Test
    void confirmedWriteToolDelegatesToMcpExecutor() {
        McpActionExecutor executor = mock(McpActionExecutor.class);
        when(executor.isAvailable()).thenReturn(true);
        when(executor.execute(eq("deployment_rollback"), eq(ActionAccessMode.READ_WRITE), any(), any(), any()))
            .thenReturn(ActionResult.builder()
                .success(true)
                .message("ok")
                .data(ActionResultContracts.object(Map.of("rollbackRequested", true)))
                .build());
        McpOperationsService service = new McpOperationsService(executor);

        McpOperationsService.ToolExecutionResult result = service.execute(new McpOperationsService.ToolExecutionRequest(
            "deployment_rollback",
            Map.of("service", "checkout"),
            true
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("rollbackRequested", true);
    }

    @Test
    void unknownToolIsStructuredFailure() {
        McpOperationsService service = new McpOperationsService(new LocalOperationsMcpActionExecutor());

        McpOperationsService.ToolExecutionResult result = service.execute(new McpOperationsService.ToolExecutionRequest(
            "missing",
            Map.of(),
            true
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_TOOL");
    }
}
