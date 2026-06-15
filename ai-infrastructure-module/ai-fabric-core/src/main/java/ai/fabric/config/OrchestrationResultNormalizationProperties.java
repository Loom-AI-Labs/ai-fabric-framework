package ai.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for deterministic, provider-agnostic orchestration result normalization.
 */
@Data
@ConfigurationProperties(prefix = "ai.orchestration.result-normalization")
public class OrchestrationResultNormalizationProperties {

    /**
     * When enabled, the pipeline will normalize the final {@code OrchestrationResult} based on
     * system facts (action registry, child ERRORs) rather than LLM-dependent wrapper shapes.
     */
    private boolean enabled = true;

    /**
     * When enabled, capture a minimal "last result" snapshot (type/success/errorCode plus safe diagnostics)
     * to help CI logs surface canonical error codes on failures.
     *
     * <p>Intentionally excludes message/data to avoid leaking PII or provider content.</p>
     */
    private boolean debugSnapshotEnabled = false;
}
