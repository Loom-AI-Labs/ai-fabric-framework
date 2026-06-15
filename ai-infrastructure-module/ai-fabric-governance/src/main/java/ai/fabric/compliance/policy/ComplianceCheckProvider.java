package ai.fabric.compliance.policy;

import ai.fabric.dto.AIComplianceRequest;

/**
 * Customer hook for compliance decisions.
 */
public interface ComplianceCheckProvider {

    ComplianceCheckResult checkCompliance(AIComplianceRequest request);
}

