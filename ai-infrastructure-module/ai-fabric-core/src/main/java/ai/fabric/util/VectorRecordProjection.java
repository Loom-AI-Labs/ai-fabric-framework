package ai.fabric.util;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanRequest;

/**
 * Shared projection rules for vector scan responses.
 */
public final class VectorRecordProjection {

    private VectorRecordProjection() {
    }

    public static VectorRecord projectForScan(VectorRecord record, VectorScanRequest request) {
        if (record == null || request == null) {
            return record;
        }
        if (request.isIncludeContent() && request.isIncludeEmbedding() && request.isIncludeMetadata()) {
            return record;
        }
        return VectorRecord.builder()
            .vectorId(record.getVectorId())
            .entityType(record.getEntityType())
            .entityId(record.getEntityId())
            .content(request.isIncludeContent() ? record.getContent() : null)
            .embedding(request.isIncludeEmbedding() ? record.getEmbedding() : null)
            .metadata(request.isIncludeMetadata() ? record.getMetadata() : null)
            .aiAnalysis(record.getAiAnalysis())
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .vectorMetadata(record.getVectorMetadata())
            .similarityScore(record.getSimilarityScore())
            .active(record.getActive())
            .version(record.getVersion())
            .build();
    }
}
