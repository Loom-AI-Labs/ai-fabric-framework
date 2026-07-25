package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operational indexing policy. Null values mean no explicit override.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIEntityIndexingPolicy {

    private Boolean enabled;
    private Integer maxCharacters;
}
