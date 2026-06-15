package ai.fabric.compliance.policy;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Value object returned from {@link ComplianceCheckProvider}.
 */
@Value
@Builder
public class ComplianceCheckResult {

    boolean compliant;
    List<String> violations;
    String details;
}

