package ai.fabric.dto;

import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * YAML-only searchable-field declaration or a supported annotation override.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AISearchableField {

    private String name;

    private Set<AISearchDestination> destinations;

    private AISearchPreprocessing preprocessing;

    private Integer maxLength;

    private Integer priority;

    private Boolean required;
}
