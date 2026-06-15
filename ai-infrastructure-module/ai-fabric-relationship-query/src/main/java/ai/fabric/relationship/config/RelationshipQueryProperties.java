package ai.fabric.relationship.config;

import ai.fabric.relationship.model.QueryMode;
import ai.fabric.relationship.model.ReturnMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for {@code ai.infrastructure.relationship.*} namespace.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.infrastructure.relationship")
public class RelationshipQueryProperties {

    /**
     * Enables the relationship-aware query module. Auto-detected unless explicitly disabled.
     */
    private boolean enabled = true;

    /**
     * Enable integration with the RAG orchestrator via the registered {@code relationship_query} AI action.
     */
    private boolean enableOrchestratorIntegration = true;

    /**
     * Enable ENHANCED mode (vector reranking) for relationship queries.
     * 
     * <p>When enabled, the LLM can activate ENHANCED mode when it determines semantic search
     * would improve relevance (based on query complexity and ambiguity).</p>
     * 
     * <p>When disabled, only STANDALONE mode (pure relational queries) is used.</p>
     * 
     * <p>Default: true (LLM decides when to use vector reranking)</p>
     */
    private boolean enableVectorSearch = true;

    /**
     * Fallback to metadata traversal when JPA relationships are not available.
     */
    private boolean fallbackToMetadata = true;

    /**
     * Fallback to semantic ranking when relationship traversal produces insufficient recall.
     */
    private boolean fallbackToVectorSearch = true;

    /**
     * Last-resort fallback that returns a best-effort response when all other strategies fail.
     */
    private boolean fallbackToSimpleSearch = true;

    /**
     * Enables basic validation (entity/relationship guards) before executing JPQL.
     */
    private boolean enableQueryValidation = true;

    /**
     * Enables plan/result caching.
     */
    private boolean enableQueryCaching = true;

    /**
     * TTL for cached plans/results (seconds). Configured explicitly to match implementation plan.
     */
    @Positive
    private long queryCacheTtlSeconds = 3600;

    @Min(1)
    @Max(5)
    private int maxTraversalDepth = 3;

    @Min(0)
    @Max(1)
    private double defaultSimilarityThreshold = 0.7;

    @NotNull
    private ReturnMode defaultReturnMode = ReturnMode.IDS;

    /**
     * Default mode when callers do not override (LLM auto-detection will flip to ENHANCED only when needed).
     * 
     * @deprecated No longer used. Mode is determined by LLM's needsSemanticSearch flag.
     *             Use {@code enable-vector-search} to control if ENHANCED mode is available.
     */
    @Deprecated
    @NotNull
    private QueryMode defaultQueryMode = QueryMode.STANDALONE;

    @NestedConfigurationProperty
    private final LlmProperties llm = new LlmProperties();

    @NestedConfigurationProperty
    private final SchemaProperties schema = new SchemaProperties();

    @NestedConfigurationProperty
    private final PlannerProperties planner = new PlannerProperties();
    @NestedConfigurationProperty
    private final CacheProperties cache = new CacheProperties();
    @NestedConfigurationProperty
    private final MetricsProperties metrics = new MetricsProperties();

    @Getter
    public static class PlannerProperties {
        private boolean logPlans = false;

        @Min(0)
        @Max(1)
        private double minConfidenceToExecute = 0.55;

        /**
         * When true, planner errors surface immediately instead of returning a degraded fallback plan.
         */
        private boolean failOnParseError = false;
        /**
         * Number of additional attempts when the LLM returns an invalid plan.
         */
        @Min(0)
        private int maxRetries = 0;

        public void setLogPlans(boolean logPlans) {
            this.logPlans = logPlans;
        }

        public void setMinConfidenceToExecute(double minConfidenceToExecute) {
            if (minConfidenceToExecute < 0) {
                this.minConfidenceToExecute = 0;
            } else if (minConfidenceToExecute > 1) {
                this.minConfidenceToExecute = 1;
            } else {
                this.minConfidenceToExecute = minConfidenceToExecute;
            }
        }

        public boolean isFailOnParseError() {
            return failOnParseError;
        }

        public void setFailOnParseError(boolean failOnParseError) {
            this.failOnParseError = failOnParseError;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, maxRetries);
        }

    }

    @Getter
    public static class LlmProperties {
        /**
         * Preferred reasoning model. Empty value defers to {@link ai.fabric.core.AICoreService} defaults.
         */
        private String model;

        @Min(0)
        @Max(1)
        private double temperature = 0.1;

        /**
         * Max output tokens for relationship query planning calls.
         *
         * <p>Some providers (notably Gemini) can emit large JSON plans for complex filters, and lower limits may
         * cause truncation (finishReason=MAX_TOKENS), which leads to parse failures and fallback execution.</p>
         */
        @Positive
        private int maxTokens = 2000;

        @Positive
        private int maxRetries = 3;

        @Positive
        private int timeoutSeconds = 30;

        @Min(0)
        @Max(1)
        private double minConfidence = 0.6;

        public void setModel(String model) {
            this.model = (model == null || model.isBlank()) ? null : model.trim();
        }

        public void setTemperature(double temperature) {
            this.temperature = clampProbability(temperature);
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(1, maxTokens);
        }

        public void setMinConfidence(double confidence) {
            this.minConfidence = clampProbability(confidence);
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = Math.max(1, maxRetries);
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = Math.max(1, timeoutSeconds);
        }

        private double clampProbability(double value) {
            if (value < 0) {
                return 0;
            }
            if (value > 1) {
                return 1;
            }
            return value;
        }
    }

    @Getter
    public static class CacheProperties {
        private boolean enabled = true;
        /**
         * Caching JPQL result IDs can become stale when underlying data changes.
         * Keep it opt-in; plan + embedding caches provide most of the benefit.
         */
        private boolean cacheResults = false;
        @NestedConfigurationProperty
        private final RegionProperties plan = new RegionProperties(3600, 10_000);
        @NestedConfigurationProperty
        private final RegionProperties embedding = new RegionProperties(86_400, 50_000);
        @NestedConfigurationProperty
        private final RegionProperties result = new RegionProperties(1_800, 5_000);

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void setCacheResults(boolean cacheResults) {
            this.cacheResults = cacheResults;
        }
    }

    @Getter
    public static class RegionProperties {
        private long ttlSeconds;
        private int maxEntries;

        public RegionProperties() {
            this(3_600, 10_000);
        }

        public RegionProperties(long ttlSeconds, int maxEntries) {
            this.ttlSeconds = ttlSeconds;
            this.maxEntries = maxEntries;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = Math.max(1, ttlSeconds);
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = Math.max(1, maxEntries);
        }

        public long ttlMillis() {
            return ttlSeconds * 1000;
        }
    }

    @Getter
    public static class MetricsProperties {
        private boolean enabled = true;
        @Positive
        private long latencyAlertMs = 1500;
        @Positive
        private int fallbackAlertThreshold = 5;

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void setLatencyAlertMs(long latencyAlertMs) {
            this.latencyAlertMs = Math.max(1, latencyAlertMs);
        }

        public void setFallbackAlertThreshold(int fallbackAlertThreshold) {
            this.fallbackAlertThreshold = Math.max(1, fallbackAlertThreshold);
        }
    }

    @Getter
    public static class SchemaProperties {
        /**
         * Automatically introspect @AICapable entities on startup.
         */
        private boolean autoDiscover = true;

        /**
         * Rebuild cached schema on every startup.
         */
        private boolean refreshOnStartup = true;

        /**
         * Log discovered schema for troubleshooting (disabled by default to avoid noise).
         */
        private boolean logSchema = false;

        /**
         * Include field metadata (non-relationships) in the schema payload.
         */
        private boolean includeFields = true;

        public void setAutoDiscover(boolean autoDiscover) {
            this.autoDiscover = autoDiscover;
        }

        public void setRefreshOnStartup(boolean refreshOnStartup) {
            this.refreshOnStartup = refreshOnStartup;
        }

        public void setLogSchema(boolean logSchema) {
            this.logSchema = logSchema;
        }

        public void setIncludeFields(boolean includeFields) {
            this.includeFields = includeFields;
        }
    }
}
