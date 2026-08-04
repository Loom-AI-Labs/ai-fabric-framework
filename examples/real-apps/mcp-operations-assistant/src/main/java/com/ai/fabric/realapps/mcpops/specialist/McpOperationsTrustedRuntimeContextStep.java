package com.ai.fabric.realapps.mcpops.specialist;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.ai.fabric.realapps.mcpops.service.McpDemoSessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the selected MCP service from backend-owned demo session state.
 */
@Component
public class McpOperationsTrustedRuntimeContextStep implements PipelineStep {

    static final String STEP_NAME = "McpOperationsTrustedRuntimeContext";
    static final int STEP_ORDER = 45;
    static final String SERVICE_NAME = "serviceName";
    static final String PROVENANCE = "mcpOperationsRuntimeContext";

    private final McpDemoSessionService sessions;

    public McpOperationsTrustedRuntimeContextStep(
        McpDemoSessionService sessions
    ) {
        this.sessions = sessions;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null || context.isShouldTerminate() || !applies(context)) {
            return context;
        }
        OrchestrationContext orchestration = context.getOrchestrationContext();
        McpDemoSessionService.ActiveSession session = sessions.active(
            orchestration.getUserId()
        );
        if (!session.conversationId().equals(orchestration.getConversationId())) {
            throw new IllegalStateException(
                "The active MCP session does not own this conversation."
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>(context.getMetadata());
        metadata.put(SERVICE_NAME, session.serviceName());
        metadata.put(
            PROVENANCE,
            Map.of(
                "source", "BACKEND_SESSION",
                "serviceName", session.serviceName()
            )
        );
        return context.toBuilder().metadata(Map.copyOf(metadata)).build();
    }

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    private boolean applies(PipelineContext context) {
        OrchestrationRequest request = context.getOrchestrationRequest();
        OrchestrationContext orchestration = context.getOrchestrationContext();
        return request != null
            && request.purpose() == OrchestrationRequestPurpose.SPECIALIST
            && orchestration != null
            && "operations".equals(orchestration.getPosition())
            && StringUtils.hasText(orchestration.getUserId())
            && StringUtils.hasText(orchestration.getConversationId());
    }
}
