package ai.fabric.governance.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexCatalogScanRequest {
    private String entityType;
    private Map<String, Object> metadataEquals;
    @Builder.Default
    private Integer limit = 200;
    private String cursor;
}

