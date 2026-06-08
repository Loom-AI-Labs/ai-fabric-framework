package com.ai.infrastructure.security.policy;

import com.ai.infrastructure.dto.AISecurityRequest;

/**
 * Infrastructure hook that allows customers to contribute additional threat detection logic.
 */
@FunctionalInterface
public interface SecurityAnalysisPolicy {

    /**
     * Perform custom security analysis for the provided request.
     *
     * @param request immutable snapshot of the security request context
     * @return analysis result describing threats, scores, and optional recommendations
     */
    SecurityAnalysisResult analyzeSecurity(AISecurityRequest request);
}
