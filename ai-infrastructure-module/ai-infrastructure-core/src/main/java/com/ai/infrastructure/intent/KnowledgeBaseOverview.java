package com.ai.infrastructure.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of the current knowledge base state exposed to the intent extraction layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeBaseOverview {

    private long totalIndexedDocuments;

    @Builder.Default
    private Map<String, Long> documentsByType = Collections.emptyMap();

    @Builder.Default
    private List<String> entityTypes = List.of();

    private LocalDateTime lastIndexUpdateTime;

    @Builder.Default
    private Map<String, Object> rawStatistics = Collections.emptyMap();

    public void setDocumentsByType(Map<String, Long> documentsByType) {
        this.documentsByType = documentsByType == null ? Collections.emptyMap() : Map.copyOf(documentsByType);
    }

    public void setEntityTypes(List<String> entityTypes) {
        this.entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
    }

    public void setRawStatistics(Map<String, Object> rawStatistics) {
        this.rawStatistics = rawStatistics == null ? Collections.emptyMap() : Map.copyOf(rawStatistics);
    }
}
