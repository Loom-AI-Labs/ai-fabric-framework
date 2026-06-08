package com.ai.infrastructure.compliance.policy;

import com.ai.infrastructure.dto.AIComplianceRequest;

/**
 * Customer hook for compliance decisions.
 */
public interface ComplianceCheckProvider {

    ComplianceCheckResult checkCompliance(AIComplianceRequest request);
}

