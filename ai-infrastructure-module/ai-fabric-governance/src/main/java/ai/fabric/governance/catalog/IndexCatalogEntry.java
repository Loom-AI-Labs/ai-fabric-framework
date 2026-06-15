package ai.fabric.governance.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexCatalogEntry {
    private String entityType;
    private String entityId;
    private String vectorId;
    private LocalDateTime indexedCreatedAt;
    private LocalDateTime indexedUpdatedAt;
    private Map<String, Object> metadata;
}

