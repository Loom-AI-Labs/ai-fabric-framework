package ai.fabric.relationship.dto;

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
import java.util.List;

/**
 * Represents a single hop (or the full chain for one traversal) between entities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationshipPath {

    @JsonProperty("fromEntityType")
    private String fromEntityType;

    @JsonProperty("relationshipType")
    private String relationshipType;

    @JsonProperty("toEntityType")
    private String toEntityType;

    @Builder.Default
    @JsonProperty("direction")
    @JsonSetter(nulls = Nulls.SKIP)
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
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean optional = false;

    /**
     * Additional constraints scoped to this hop (e.g. {@code user.status = 'active'}).
     */
    @Builder.Default
    @JsonProperty("conditions")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<FilterCondition> conditions = new ArrayList<>();

    public RelationshipDirection getDirection() {
        return direction != null ? direction : RelationshipDirection.FORWARD;
    }

    public boolean isOptional() {
        return Boolean.TRUE.equals(optional);
    }
}
