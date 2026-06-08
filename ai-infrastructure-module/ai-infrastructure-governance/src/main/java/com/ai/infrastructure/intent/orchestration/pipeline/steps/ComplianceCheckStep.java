package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.compliance.AIComplianceService;
import com.ai.infrastructure.dto.AIComplianceRequest;
import com.ai.infrastructure.dto.AIComplianceResponse;
import com.ai.infrastructure.intent.orchestration.OrchestrationAuthContextResolver;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline step that validates request compliance with policies and regulations.
 *
 * <p><strong>Order:</strong> 40 (after PII detection)</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ComplianceCheckStep implements PipelineStep {

    private static final String STEP_NAME = "ComplianceCheck";
    private static final int STEP_ORDER = 40;
    private static final String ERROR_MSG_COMPLIANCE_FAILED = "Request failed compliance validation.";

    private final AIComplianceService complianceService;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Checking compliance for request {}", context.getRequestId());

        AIComplianceRequest complianceRequest = AIComplianceRequest.builder()
            .requestId(context.getRequestId())
            .authContext(OrchestrationAuthContextResolver.from(context.getOrchestrationContext()))
            .content(context.getEffectiveQuery())
            .timestamp(context.getRequestTimestamp())
            .build();

        AIComplianceResponse complianceResponse = complianceService.checkCompliance(complianceRequest);

        if (Boolean.FALSE.equals(complianceResponse.getOverallCompliant())) {
            log.warn("Compliance check failed for request {} - user: {}",
                context.getRequestId(), context.getIdentifier());
            return context.terminate(OrchestrationResult.error(ERROR_MSG_COMPLIANCE_FAILED));
        }

        log.debug("Compliance check passed for request {}", context.getRequestId());
        return context;
    }
}
