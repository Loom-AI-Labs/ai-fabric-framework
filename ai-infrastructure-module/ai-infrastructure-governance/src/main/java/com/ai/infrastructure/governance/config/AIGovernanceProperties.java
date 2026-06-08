package com.ai.infrastructure.governance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.governance")
public class AIGovernanceProperties {

    /**
     * Enables governance features (catalog, deletion discovery, retention, compliance).
     */
    private boolean enabled = false;

    private CatalogProperties catalog = new CatalogProperties();
    private DeletionProperties deletion = new DeletionProperties();
    private PrivacyProperties privacy = new PrivacyProperties();
    private RetentionProperties retention = new RetentionProperties();
    private PiiProperties pii = new PiiProperties();
    private ComplianceProperties compliance = new ComplianceProperties();
    private ContentFilterProperties contentFilter = new ContentFilterProperties();

    @Data
    public static class CatalogProperties {

        public enum Mode {
            AUTO,
            VECTOR,
            SQL,
            DISABLED
        }

        /**
         * AUTO prefers vector-native scanning/filtering when available, otherwise SQL catalog when possible.
         */
        private Mode mode = Mode.AUTO;

        /**
         * Metadata keys used to discover user-linked data for deletion and apply governance policies.
         */
        private List<String> requiredMetadataKeys = List.of("userId", "ownerId", "createdBy", "dataClassification");
    }

    @Data
    public static class DeletionProperties {

        /**
         * Enables GDPR/CCPA deletion orchestration beans.
         */
        private boolean enabled = false;
    }

    @Data
    public static class PrivacyProperties {

        /**
         * Enables privacy/compliance service beans.
         */
        private boolean enabled = false;
    }

    @Data
    public static class RetentionProperties {

        /**
         * Enables retention cleanup scheduling (delete indexed data based on stable timestamps).
         */
        private boolean enabled = false;

        /**
         * Cron expression for retention cleanup.
         */
        private String cron = "0 30 3 * * *";

        /**
         * Entity types to scan for retention cleanup. If empty, keys from {@link #retentionDays} are used.
         */
        private List<String> entityTypes = List.of();

        /**
         * Default retention-days per entity type when no {@code RetentionPolicyProvider} is supplied.
         *
         * <p>Keys are entity types (lower-case recommended). A special key {@code default} may be used.</p>
         */
        private Map<String, Integer> retentionDays = new LinkedHashMap<>();

        /**
         * Page size for catalog scans.
         */
        private Integer scanLimit = 200;
    }

    @Data
    public static class PiiProperties {

        /**
         * Enables governance-level PII coordination (validation, future audit/alerts/enforcement).
         *
         * <p>This does not automatically enable the detector. Detection remains controlled by
         * {@code ai.pii-detection.*}.</p>
         */
        private boolean enabled = false;

        /**
         * When enabled, fail fast if no {@link com.ai.infrastructure.privacy.pii.PIIDetectionService} is available.
         */
        private boolean requireDetectionService = true;

        /**
         * When enabled, fail fast if no pipeline step named {@code PIIDetection} is present.
         *
         * <p>Use this when your application relies on orchestration pipeline redaction.</p>
         */
        private boolean requirePipelineStep = false;
    }

    @Data
    public static class ComplianceProperties {

        /**
         * Enables compliance evaluation services and orchestration pipeline step.
         */
        private boolean enabled = false;
    }

    @Data
    public static class ContentFilterProperties {

        /**
         * Enables content filtering services.
         */
        private boolean enabled = false;
    }
}
