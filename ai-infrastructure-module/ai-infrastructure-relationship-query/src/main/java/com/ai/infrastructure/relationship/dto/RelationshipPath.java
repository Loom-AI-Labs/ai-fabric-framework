package com.ai.infrastructure.relationship.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single hop (or the full chain for one traversal) between entities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelationshipPath {

    @JsonProperty("fromEntityType")
    private String fromEntityType;

    @JsonProperty("relationshipType")
    private String relationshipType;

    @JsonProperty("toEntityType")
    private String toEntityType;

    @Builder.Default
    @JsonProperty("direction")
    private RelationshipDirection direction = RelationshipDirection.FORWARD;

    /**
     * Optional alias the LLM can send to ensure deterministic join names (e.g. {@code documentAuthor}).
     */
    @JsonProperty("alias")
    private String alias;

    /**
     * Whether this hop is optional (LEFT JOIN) or required (INNER JOIN).
     */
    @Builder.Default
    @JsonProperty("optional")
    private boolean optional = false;

    /**
     * Additional constraints scoped to this hop (e.g. {@code user.status = 'active'}).
     */
    @Builder.Default
    @JsonProperty("conditions")
    private List<FilterCondition> conditions = new ArrayList<>();
}
