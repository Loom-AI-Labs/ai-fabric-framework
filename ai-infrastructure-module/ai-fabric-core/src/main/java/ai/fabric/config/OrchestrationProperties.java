package ai.fabric.config;

import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration for orchestration behavior.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.orchestration")
public class OrchestrationProperties {

    /**
     * Optimization profile that provides default orchestration behavior.
     */
    private OrchestrationProfile profile = OrchestrationProfile.DEFAULT;

    /**
     * Explicit override for how INFORMATION intents are handled.
     *
     * <p>When unset (null), the effective value is derived from {@link #profile} and/or mode overrides.</p>
     */
    private InformationMode informationMode;

    /**
     * When true, INFORMATION intents that perform retrieval will always run LLM generation to produce a user-facing answer,
     * even if the extracted intent requested {@code requiresGeneration=false}.
     *
     * <p>Defaults to false to preserve retrieval-only/listing behavior.</p>
     */
    private boolean alwaysGenerateInformation = false;

    /**
     * When true, if a READ action returns an empty successful payload and the orchestrator falls back to RAG,
     * the final {@code INFORMATION_PROVIDED} result will include a structured {@code readProbe} metadata block
     * describing the attempted action.
     *
     * <p>Defaults to false to keep responses minimal and preserve legacy behavior.</p>
     */
    private boolean exposeReadProbeFallbackAttempt = false;

    /**
     * Controls whether user-visible action parameter provenance warnings block execution.
     *
     * <p>Hard validation failures still block in every mode: missing required values, empty or placeholder values,
     * hidden/system parameters that were not resolved from trusted context, and executable/resource-handle checks.
     * This setting only controls user-visible string values that are present but not found in current user/history
     * or pinned evidence.</p>
     *
     * <p>Defaults to {@link ActionParamProvenanceMode#WARN}: continue to confirmation/execution while surfacing
     * provenance diagnostics in result metadata.</p>
     */
    private ActionParamProvenanceMode actionParamProvenanceMode = ActionParamProvenanceMode.WARN;

    /**
     * Server-defined orchestration modes (coherent bundles) that can override profile defaults.
     *
     * <p>Clients may request a mode, but the server only accepts allowlisted modes defined here.</p>
     */
    private Map<String, ModeOverrides> modes = new LinkedHashMap<>();

    /**
     * Default orchestration mode to apply when the client does not provide a mode.
     *
     * <p>This enables "pack default mode" without requiring UI position routing in core.</p>
     *
     * <p>When unset (null/blank), no mode is applied and profile defaults are used.</p>
     */
    private String defaultMode;

    /**
     * Configuration for RAG request composition.
     */
    private RagProperties rag = new RagProperties();

    /**
     * Server-side routing from a UI position signal to an orchestration mode.
     *
     * <p>Used to allow the client to send a low-spoof-risk position signal while the server selects the mode.</p>
     *
     * <p><strong>Note:</strong> this is intended for app/web-layer routing only. Core orchestration policy resolution
     * should not depend on UI positions.</p>
     */
    private Map<String, String> positionRouting = new LinkedHashMap<>();

    /**
     * If true, unknown positions terminate the request with an error.
     *
     * <p>When false (default), unknown positions are ignored and the profile/mode fallback applies.</p>
     */
    private boolean strictPositionRouting = false;

    /**
     * If true, unknown requested modes terminate the request with an error.
     *
     * <p>When false (default), unknown modes are ignored and the profile fallback applies.</p>
     */
    private boolean strictModeRouting = false;

    /**
     * How INFORMATION intents are handled.
     */
    public enum InformationMode {
        /**
         * Current behavior: intent extraction controls requiresRetrieval/requiresGeneration and vectorSpace routing.
         */
        LLM_DRIVEN,

        /**
         * Deterministic behavior: INFORMATION always performs retrieval (RAG) and generation.
         * Missing vectorSpace triggers fan-out across all known vector spaces (when available).
         */
        DETERMINISTIC_RAG_GENERATE
    }

    /**
     * Policy for user-visible action parameter provenance checks.
     */
    public enum ActionParamProvenanceMode {
        /**
         * User-visible values without provenance block as missing required parameters.
         */
        BLOCK,

        /**
         * User-visible values without provenance are reported in metadata but do not block.
         */
        WARN,

        /**
         * User-visible provenance checks are skipped. Hard validation still applies.
         */
        OFF
    }

    /**
     * Mode-level overrides for orchestration behavior.
     */
    @Data
    public static class ModeOverrides {
        /**
         * Whether ACTION intents may be executed in this mode.
         *
         * <p>When unset (null), ACTION handling remains enabled (backwards-compatible default).</p>
         */
        private Boolean actionsEnabled;

        /**
         * Whether retrieval (RAG) may be performed in this mode.
         *
         * <p>When unset (null), retrieval remains enabled (backwards-compatible default).</p>
         */
        private Boolean retrievalEnabled;

        /**
         * Whether broad retrieval expansion beyond pinned targets is permitted in this mode.
         *
         * <p>This is intended for explicit deep modes (e.g., navigator_deep).</p>
         */
        private Boolean deepRetrievalEnabled;

        /**
         * When true, if the request contains resolved targets (active attachments and/or stored pinned targets),
         * the orchestrator may force {@code requiresRetrieval=true} for INFORMATION intents even if the LLM
         * extracted {@code requiresRetrieval=false}.
         *
         * <p>This is intended for deep modes where follow-ups like "negative reviews?" or "return policy?"
         * must retrieve cross-space data even when the user does not restate the targets.</p>
         *
         * <p>Defaults to false.</p>
         */
        private Boolean forceRetrievalWhenTargetsPresent;

        /**
         * When true, stored pinned targets (seeded from session metadata / working set) may be used as evidence
         * for {@link #forceRetrievalWhenTargetsPresent}.
         *
         * <p>Defaults to false. Active request attachments remain the primary signal.</p>
         */
        private Boolean forceRetrievalConsiderStoredTargets;

        /**
         * When true, deterministic modes may skip retrieval when pinned targets already cover the requested
         * vectorSpace(s) (minimize RAG cost).
         *
         * <p>Defaults to true. Deep modes should typically disable this to allow cross-space grounding.</p>
         */
        private Boolean minimizeRagWhenPinnedTargetsCoverRequest;

        /**
         * Whether smart suggestions should run in this mode.
         *
         * <p>When unset (null), suggestions remain enabled (backwards-compatible default).</p>
         */
        private Boolean suggestionsEnabled;

        /**
         * Whether the LLM should prefer ACTION intents when both actions and retrieval are possible.
         *
         * <p>This is a prompting hint only; the pipeline still enforces capabilities and constraints deterministically.</p>
         */
        private Boolean actionsPreferred;

        /**
         * Whether the knowledge base overview section should be injected into the intent extraction prompt.
         *
         * <p>This can be disabled for action-focused modes to reduce retrieval "wandering" across unrelated vector spaces.</p>
         */
        private Boolean knowledgeBaseOverviewEnabled;

        /**
         * When true, retrieval requires a non-empty vector space allowlist in this mode (fail-closed).
         *
         * <p>This is useful for action-focused modes that allow limited retrieval (e.g., "policy" only).</p>
         */
        private Boolean retrievalAllowlistRequired;

        /**
         * When true, the LLM must explicitly set {@code vectorSpace} for retrieval in this mode.
         *
         * <p>When false, the server may default to the configured allowlist (when present) or the KB overview.</p>
         */
        private Boolean vectorSpaceSelectionRequired;

        /**
         * Optional mode override for information orchestration behavior.
         */
        private InformationMode informationMode;

        /**
         * Optional mode override to force Advanced RAG on/off.
         *
         * <p>When true, Advanced RAG is enabled for the request (when a provider is present).
         * When false, Advanced RAG is disabled even if the LLM suggests it.</p>
         */
        private Boolean useAdvancedRag;

        /**
         * Optional mode override for whether pinned-target hints may be appended to the embedding query.
         *
         * <p>When unset, the global {@code ai.orchestration.rag.target-hint.enabled} value applies.</p>
         */
        private Boolean ragTargetHintEnabled;

        /**
         * Optional mode override for exposing READ probe fallback attempts.
         *
         * <p>When true, if a READ action returns an empty successful payload and the orchestrator falls back to RAG,
         * the final {@code INFORMATION_PROVIDED} result will include a structured {@code readProbe} metadata block
         * describing the attempted action.</p>
         *
         * <p>When unset (null), the global {@link OrchestrationProperties#exposeReadProbeFallbackAttempt} value applies.</p>
         */
        private Boolean exposeReadProbeFallbackAttempt;

        /**
         * When true, a direct ACTION intent that executes a READ action marked {@code groundingEligible=true}
         * will force a post-action generation pass from the action result facts even when the extracted intent did
         * not explicitly request generation.
         *
         * <p>When unset (null), the capability defaults to false. Deep/thinker modes can enable this so live
         * read-action results are converted into user-facing answers instead of returning raw action payloads.</p>
         */
        private Boolean forceGroundingEligibleReadActionPostGeneration;

        /**
         * Optional mode override for RAG budgeting and scoping.
         */
        private RagModeOverrides rag;

        /**
         * Optional mode override for planner-driven read-action resolution.
         */
        private ReadActionResolutionModeOverrides readActionResolution;
    }

    @Data
    public static class RagModeOverrides {
        /**
         * Whether fan-out across multiple vector spaces is allowed when routing requires it.
         */
        private Boolean fanoutEnabled;

        /**
         * Max number of vector spaces to search in fan-out mode.
         */
        private Integer maxSpaces;

        /**
         * Top K documents to retrieve per space in fan-out mode.
         */
        private Integer topKPerSpace;

        /**
         * Max documents returned to the client (UI payload).
         */
        private Integer maxDocumentsReturnedToClient;

        /**
         * Max documents used to build LLM grounding context.
         */
        private Integer maxDocumentsUsedForContext;

        /**
         * Max chars of grounding context passed to the LLM.
         */
        private Integer maxContextChars;

        /**
         * Minimum similarity threshold for RAG retrieval in this mode.
         */
        private Double similarityThreshold;

        /**
         * Allowlisted vector spaces for retrieval in this mode (fail-closed).
         *
         * <p>When non-empty, retrieval may only use spaces from this list.</p>
         */
        private List<String> retrievalVectorSpacesAllowlist = new ArrayList<>();
    }

    @Data
    public static class ReadActionResolutionModeOverrides {
        private Boolean enabled;
        private ReadActionResolutionPlanningMode planningMode;
        private List<String> allowedReadActions = new ArrayList<>();
        private Boolean requireAllowlist;
        private Integer maxIterations;
        private Integer maxActionsPerIteration;
        private Integer maxTotalActions;
        private Integer maxParallelActions;
        private Integer maxPlannerContextChars;
        private Integer maxActionEvidenceCharsPerAction;
        private ReadActionResolutionRagCooperationMode ragCooperationMode;
        private Boolean requireGroundingEligible;

        public void setAllowedReadActions(List<String> allowedReadActions) {
            this.allowedReadActions = normalizeActionNames(allowedReadActions);
        }
    }

    public enum ReadActionResolutionPlanningMode {
        OFF,
        SINGLE_PASS,
        ITERATIVE
    }

    public enum ReadActionResolutionRagCooperationMode {
        NONE,
        RAG_IF_ACTIONS_INSUFFICIENT,
        PARALLEL_ACTIONS_AND_RAG
    }

    @Data
    public static class RagProperties {
        /**
         * When enabled, the orchestrator may expand the embedding query using pinned target evidence.
         */
        private TargetHintProperties targetHint = new TargetHintProperties();
    }

    @Data
    public static class TargetHintProperties {
        /**
         * Master switch for embedding query target hints.
         */
        private boolean enabled = false;

        /**
         * Max number of targets to include in the hint.
         */
        private int maxTargets = 3;

        /**
         * Max total chars for the appended hint (excluding the base query).
         */
        private int maxChars = 500;

        /**
         * Include the target vectorSpace in the hint when present.
         */
        private boolean includeVectorSpace = true;

        /**
         * Include the target id in the hint when present.
         */
        private boolean includeId = true;

        /**
         * Include a bounded snippet of target contentText when present.
         */
        private boolean includeContentText = true;

        /**
         * Max chars of contentText included per target.
         */
        private int maxContentTextCharsPerTarget = 120;

        /**
         * Allowlisted metadata keys to include in the hint.
         *
         * <p>When empty, arbitrary metadata keys are not appended (safe-by-default).</p>
         */
        private List<String> metadataKeysAllowlist = new ArrayList<>();
    }

    private static List<String> normalizeActionNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || normalized.contains(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }
}
