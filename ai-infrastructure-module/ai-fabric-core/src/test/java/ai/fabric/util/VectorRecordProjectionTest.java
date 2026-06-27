package ai.fabric.util;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorRecordProjectionTest {

    @Test
    void scanProjectionOnlySuppressesRequestedPayloadFields() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-06-19T10:00:00");
        LocalDateTime updatedAt = LocalDateTime.parse("2026-06-19T10:05:00");
        VectorRecord record = VectorRecord.builder()
            .vectorId("vector-1")
            .entityType("document")
            .entityId("doc-1")
            .content("Original content")
            .embedding(List.of(0.1, 0.2, 0.3))
            .metadata(Map.of("visibility", "public"))
            .aiAnalysis("classification")
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .vectorMetadata(Map.of("provider", "test"))
            .similarityScore(0.91d)
            .active(false)
            .version(7)
            .build();

        VectorRecord projected = VectorRecordProjection.projectForScan(record, VectorScanRequest.builder()
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(false)
            .build());

        assertThat(projected.getVectorId()).isEqualTo("vector-1");
        assertThat(projected.getEntityType()).isEqualTo("document");
        assertThat(projected.getEntityId()).isEqualTo("doc-1");
        assertThat(projected.getContent()).isNull();
        assertThat(projected.getEmbedding()).isNull();
        assertThat(projected.getMetadata()).isNull();
        assertThat(projected.getAiAnalysis()).isEqualTo("classification");
        assertThat(projected.getCreatedAt()).isEqualTo(createdAt);
        assertThat(projected.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(projected.getVectorMetadata()).containsEntry("provider", "test");
        assertThat(projected.getSimilarityScore()).isEqualTo(0.91d);
        assertThat(projected.getActive()).isFalse();
        assertThat(projected.getVersion()).isEqualTo(7);
    }

    @Test
    void scanProjectionReturnsOriginalRecordWhenAllPayloadFieldsAreIncluded() {
        VectorRecord record = VectorRecord.builder()
            .vectorId("vector-1")
            .content("Original content")
            .embedding(List.of(0.1, 0.2, 0.3))
            .metadata(Map.of("visibility", "public"))
            .build();

        VectorScanRequest request = VectorScanRequest.builder()
            .includeContent(true)
            .includeEmbedding(true)
            .includeMetadata(true)
            .build();

        assertThat(VectorRecordProjection.projectForScan(record, request)).isSameAs(record);
        assertThat(VectorRecordProjection.projectForScan(record, null)).isSameAs(record);
        assertThat(VectorRecordProjection.projectForScan(null, request)).isNull();
    }
}
