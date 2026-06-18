package ai.fabric.relationship.it.api;

import ai.fabric.relationship.model.ReturnMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Simple DTO representing a relationship query request coming from HTTP clients.
 */
@Data
public class RelationshipQueryRequest {

    @NotBlank
    private String query;

    private List<String> entityTypes;

    private ReturnMode returnMode = ReturnMode.FULL;

    @Min(1)
    private Integer limit = 5;
}
