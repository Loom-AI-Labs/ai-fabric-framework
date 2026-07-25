package ai.fabric.dto;

import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * YAML-only context declaration or a supported annotation override.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIMetadataField {

    private String name;

    private AIContextDataType dataType;

    private String format;
    private String description;

    private Set<AIContextDestination> destinations;

    private Integer priority;

    private Boolean required;

    private Boolean sanitizePII;
}
