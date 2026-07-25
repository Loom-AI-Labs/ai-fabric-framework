package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Presence-aware runtime policy for an AI entity.
 *
 * <p>Annotation-backed entities do not require an entry. YAML-only entities must
 * explicitly enable indexing and declare a projection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIEntityConfig {

    private String entityType;

    private AIEntityIndexingPolicy indexing;

    private AIEntityAnalysisPolicy analysis;

    private List<AISearchableField> searchableFields;

    private List<AIMetadataField> metadataFields;
}
