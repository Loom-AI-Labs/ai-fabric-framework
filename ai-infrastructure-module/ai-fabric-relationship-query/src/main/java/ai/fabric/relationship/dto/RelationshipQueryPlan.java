package ai.fabric.relationship.dto;

import ai.fabric.relationship.model.QueryMode;
import ai.fabric.relationship.model.ReturnMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical plan structure produced by {@code RelationshipQueryPlanner}. It mirrors the JSON payloads
 * defined throughout the semantic relational implementation documents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationshipQueryPlan {

    @JsonProperty("originalQuery")
    private String originalQuery;

    @JsonProperty("semanticQuery")
    private String semanticQuery;

    @JsonProperty("primaryEntityType")
    private String primaryEntityType;

    @Builder.Default
    @JsonProperty("candidateEntityTypes")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<String> candidateEntityTypes = new ArrayList<>();

    @Builder.Default
    @JsonProperty("relationshipPaths")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<RelationshipPath> relationshipPaths = new ArrayList<>();

    @Builder.Default
    @JsonProperty("directFilters")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, List<FilterCondition>> directFilters = new LinkedHashMap<>();

    @Builder.Default
    @JsonProperty("relationshipFilters")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, List<FilterCondition>> relationshipFilters = new LinkedHashMap<>();

    @Builder.Default
    @JsonProperty("metadataFilters")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, Object> metadataFilters = new LinkedHashMap<>();

    @JsonProperty("queryStrategy")
    private QueryStrategy queryStrategy;

    @Builder.Default
    @JsonProperty("needsSemanticSearch")
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean needsSemanticSearch = false;

    @JsonProperty("confidence")
    private Double confidenceScore;

    @JsonProperty("maxTraversalDepth")
    private Integer maxTraversalDepth;

    @JsonProperty("limit")
    private Integer limit;

    /**
     * Preferred execution mode. {@code null} implies the orchestrator should fall back to auto-detection.
     */
    @JsonProperty("preferredMode")
    private QueryMode preferredMode;

    /**
     * Whether response should contain only IDs (default) or fully materialized entities.
     */
    @JsonProperty("returnMode")
    private ReturnMode returnMode;

    /**
     * Free-form context that LLM planners may emit for logging or debugging purposes.
     */
    @Builder.Default
    @JsonProperty("context")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, Object> additionalContext = new LinkedHashMap<>();

    public boolean isNeedsSemanticSearch() {
        return Boolean.TRUE.equals(needsSemanticSearch);
    }
}
