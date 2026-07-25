package ai.fabric.dto;

import ai.fabric.indexing.api.AIProcessOperation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Optional analysis policy kept separate from vector lifecycle work.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIEntityAnalysisPolicy {

    private Boolean enabled;

    @Builder.Default
    private Set<AIProcessOperation> after = new LinkedHashSet<>();
}
