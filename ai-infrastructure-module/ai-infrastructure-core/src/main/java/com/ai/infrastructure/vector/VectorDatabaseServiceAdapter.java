package com.ai.infrastructure.vector;

import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.VectorRecord;
import com.ai.infrastructure.rag.VectorDatabaseService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that exposes a {@link VectorDatabaseService} as the legacy {@link VectorDatabase} interface.
 */
public class VectorDatabaseServiceAdapter implements VectorDatabase {

    private final VectorDatabaseService delegate;

    public VectorDatabaseServiceAdapter(VectorDatabaseService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void storeVector(String entityType, String entityId, String content,
                            List<Double> embedding, Map<String, Object> metadata) {
        delegate.storeVector(entityType, entityId, content, embedding, metadata);
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        return delegate.search(queryVector, request);
    }

    @Override
    public void removeVector(String entityType, String entityId) {
        delegate.removeVector(entityType, entityId);
    }

    @Override
    public void updateVector(String entityType, String entityId, String content,
                             List<Double> embedding, Map<String, Object> metadata) {
        String vectorId = delegate.getVectorByEntity(entityType, entityId)
            .map(VectorRecord::getVectorId)
            .orElse(null);
        delegate.updateVector(vectorId, entityType, entityId, content, embedding, metadata);
    }

    @Override
    public Map<String, Object> getVector(String entityType, String entityId) {
        return delegate.getVectorByEntity(entityType, entityId)
            .map(VectorDatabaseServiceAdapter::toMap)
            .orElse(Map.of());
    }

    @Override
    public boolean vectorExists(String entityType, String entityId) {
        return delegate.vectorExists(entityType, entityId);
    }

    @Override
    public List<Map<String, Object>> getAllVectors(String entityType) {
        return delegate.getVectorsByEntityType(entityType).stream()
            .map(VectorDatabaseServiceAdapter::toMap)
            .toList();
    }

    @Override
    public void clearVectors(String entityType) {
        delegate.clearVectorsByEntityType(entityType);
    }

    @Override
    public void clearAllVectors() {
        delegate.clearVectors();
    }

    @Override
    public Map<String, Object> getStatistics() {
        return delegate.getStatistics();
    }

    @Override
    public boolean isHealthy() {
        Map<String, Object> stats = delegate.getStatistics();
        Object status = stats.get("status");
        if (status instanceof String str) {
            return !"error".equalsIgnoreCase(str);
        }
        return true;
    }

    @Override
    public Map<String, Object> getInfo() {
        return delegate.getStatistics();
    }

    @Override
    public void batchStoreVectors(List<VectorData> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        List<VectorRecord> records = new ArrayList<>(vectors.size());
        for (VectorData vector : vectors) {
            records.add(VectorRecord.builder()
                .entityType(vector.getEntityType())
                .entityId(vector.getEntityId())
                .content(vector.getContent())
                .embedding(vector.getEmbedding())
                .metadata(vector.getMetadata())
                .build());
        }
        delegate.batchStoreVectors(records);
    }

    @Override
    public List<AISearchResponse> batchSearch(List<VectorSearchQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        List<AISearchResponse> responses = new ArrayList<>(queries.size());
        for (VectorSearchQuery query : queries) {
            responses.add(delegate.search(query.getQueryVector(), query.getRequest()));
        }
        return responses;
    }

    private static Map<String, Object> toMap(VectorRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("vectorId", record.getVectorId());
        map.put("entityType", record.getEntityType());
        map.put("entityId", record.getEntityId());
        map.put("content", record.getContent());
        map.put("embedding", record.getEmbedding());
        if (record.getMetadata() != null) {
            map.put("metadata", record.getMetadata());
        }
        if (record.getVectorMetadata() != null) {
            map.put("vectorMetadata", record.getVectorMetadata());
        }
        if (record.getSimilarityScore() != null) {
            map.put("similarity", record.getSimilarityScore());
        }
        map.put("active", record.getActive());
        map.put("version", record.getVersion());
        map.put("createdAt", record.getCreatedAt());
        map.put("updatedAt", record.getUpdatedAt());
        return map;
    }
}
