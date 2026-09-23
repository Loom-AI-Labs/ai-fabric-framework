package com.ai.fabric.realapps.mcpops.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.ActionConnectorProtocol;
import ai.fabric.intent.action.connector.McpActionExecutor;
import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpConnectorDispatchControllerTest {

    @Test
    void adaptsLogicalParametersAndProvesBothSafetyGates() {
        RecordingExecutor executor = new RecordingExecutor(ActionResult.builder()
            .success(true)
            .message("Restart completed")
            .data(ActionPayload.object(Map.of(
                "restarted", true,
                "revision", 2,
                "_hiddenConnectorTrace", "must-not-cross-boundary"
            )))
            .build());
        McpConnectorDispatchController controller =
            new McpConnectorDispatchController(executor);

        Map<String, Object> response = controller.execute(request(
            McpOperationsService.SERVER_REF,
            McpOperationsService.RESTART_ACTION,
            List.of("request.revision")
        ));

        assertThat(response)
            .containsEntry(ActionConnectorProtocol.KEY_SUCCESS, true)
            .containsEntry(ActionConnectorProtocol.KEY_MESSAGE, "Restart completed");
        assertThat(map(response.get(ActionConnectorProtocol.KEY_DATA)))
            .containsEntry("dispatchMode", "CONNECTOR")
            .containsEntry("logicalGate", "expectedRevision")
            .containsEntry("renderedGate", "request.revision")
            .doesNotContainKey("_hiddenConnectorTrace");
        assertThat(executor.calls).isEqualTo(1);
        assertThat(executor.params)
            .containsEntry("sandboxId", "mcp-demo-session")
            .containsEntry("serviceName", "checkout")
            .containsEntry("expectedRevision", 1);
        assertThat(executor.accessMode).isEqualTo(ActionAccessMode.WRITE_ONLY);
        assertThat(map(map(executor.actionConfig.get("execution")).get("mcp")))
            .containsEntry("dispatchMode", "DIRECT_GATEWAY")
            .containsEntry("serverRef", McpOperationsService.SERVER_REF)
            .containsEntry("toolName", McpOperationsService.RESTART_ACTION);
    }

    @Test
    void rejectsMissingRenderedGateBeforeCallingMcp() {
        RecordingExecutor executor = new RecordingExecutor(ActionResult.builder()
            .success(true)
            .build());
        McpConnectorDispatchController controller =
            new McpConnectorDispatchController(executor);

        Map<String, Object> response = controller.execute(request(
            McpOperationsService.SERVER_REF,
            McpOperationsService.RESTART_ACTION,
            List.of()
        ));

        assertThat(response)
            .containsEntry(ActionConnectorProtocol.KEY_SUCCESS, false)
            .containsEntry(
                ActionConnectorProtocol.KEY_ERROR_CODE,
                "INVALID_CONFIGURATION"
            );
        assertThat(response.get(ActionConnectorProtocol.KEY_MESSAGE).toString())
            .contains("request.revision");
        assertThat(executor.calls).isZero();
    }

    @Test
    void rejectsAnUnapprovedServerBeforeCallingMcp() {
        RecordingExecutor executor = new RecordingExecutor(ActionResult.builder()
            .success(true)
            .build());
        McpConnectorDispatchController controller =
            new McpConnectorDispatchController(executor);

        Map<String, Object> response = controller.execute(request(
            "caller-selected-server",
            McpOperationsService.RESTART_ACTION,
            List.of("request.revision")
        ));

        assertThat(response)
            .containsEntry(ActionConnectorProtocol.KEY_SUCCESS, false)
            .containsEntry(
                ActionConnectorProtocol.KEY_ERROR_CODE,
                "INVALID_CONFIGURATION"
            );
        assertThat(executor.calls).isZero();
    }

    private Map<String, Object> request(
        String serverRef,
        String toolName,
        List<String> requiredAnyArguments
    ) {
        return Map.of(
            ActionConnectorProtocol.KEY_ACTION_ID,
            McpOperationsService.RESTART_ACTION,
            ActionConnectorProtocol.KEY_PARAMS,
            Map.of(
                "sandboxId", "mcp-demo-session",
                "serviceName", "checkout",
                "expectedRevision", 1
            ),
            ActionConnectorProtocol.KEY_TRACE,
            Map.of(
                "requestId", "request-1",
                "conversationId", "conversation-1",
                "userId", "mcp-demo-session",
                "actionConfig", Map.of(
                    "execution", Map.of(
                        "mcp", Map.of(
                            "dispatchMode", "CONNECTOR",
                            "serverRef", serverRef,
                            "toolName", toolName,
                            "requiredAnyArguments", requiredAnyArguments
                        )
                    )
                )
            )
        );
    }

    private Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static final class RecordingExecutor implements McpActionExecutor {
        private final ActionResult result;
        private int calls;
        private ActionAccessMode accessMode;
        private Map<String, Object> params;
        private Map<String, Object> actionConfig;

        private RecordingExecutor(ActionResult result) {
            this.result = result;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ActionResult execute(
            String actionId,
            ActionAccessMode accessMode,
            Map<String, Object> params,
            ActionContext context,
            Map<String, Object> actionConfig
        ) {
            this.calls++;
            this.accessMode = accessMode;
            this.params = params;
            this.actionConfig = actionConfig;
            return result;
        }
    }
}
