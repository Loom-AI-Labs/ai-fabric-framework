package com.ai.infrastructure.vector.memory;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.VectorRecord;
import com.ai.infrastructure.dto.VectorScanPage;
import com.ai.infrastructure.dto.VectorScanRequest;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.util.MetadataJsonSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * In-Memory Vector Database Service
 * 
 * This service provides vector database operations using an in-memory store.
 * It's designed for testing and development environments where persistence
 * is not required.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryVectorDatabaseService implements VectorDatabaseService {
    
    private final AIProviderConfig config;
    
    // In-memory vector store - using vectorId as key for efficient lookups
    private final Map<String, VectorRecord> vectorStore = new HashMap<>();

    @Override
    public boolean supportsVectorScan() {
        return true;
    }

    @Override
    public boolean supportsMetadataFiltering() {
        return true;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        return VectorDatabaseService.super.scan(request);
    }
    
    @Override
    public String storeVector(String entityType, String entityId, String content, 
                           List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Storing vector in memory for entity {} of type {}", entityId, entityType);
            
            String vectorId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();

            Map<String, Object> safeMetadata = normalizeMetadata(metadata);
            
            VectorRecord vectorRecord = VectorRecord.builder()
                .vectorId(vectorId)
                .entityType(entityType)
                .entityId(entityId)
                .content(content)
                .embedding(embedding)
                .metadata(safeMetadata)
                .aiAnalysis(null)
                .createdAt(now)
                .updatedAt(now)
                .vectorMetadata(new HashMap<>())
                .similarityScore(null)
                .active(true)
                .version(1)
                .build();
            
            vectorStore.put(vectorId, vectorRecord);
            
            log.debug("Successfully stored vector in memory for entity {} of type {} with vectorId {}", entityId, entityType, vectorId);
            return vectorId;
            
        } catch (Exception e) {
            log.error("Error storing vector in memory", e);
            throw new AIServiceException("Failed to store vector in memory", e);
        }
    }
    
    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content, 
                              List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Updating vector in memory with vectorId {}", vectorId);
            
            VectorRecord existingRecord = vectorStore.get(vectorId);
            if (existingRecord == null) {
                log.warn("Vector not found for vectorId: {}", vectorId);
                return false;
            }

            Map<String, Object> safeMetadata = normalizeMetadata(metadata);
            
            VectorRecord updatedRecord = VectorRecord.builder()
                .vectorId(existingRecord.getVectorId())
                .entityType(entityType)
                .entityId(entityId)
                .content(content)
                .embedding(embedding)
                .metadata(safeMetadata)
                .aiAnalysis(existingRecord.getAiAnalysis())
                .createdAt(existingRecord.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .vectorMetadata(existingRecord.getVectorMetadata())
                .similarityScore(existingRecord.getSimilarityScore())
                .active(existingRecord.getActive())
                .version(existingRecord.getVersion() + 1)
                .build();
            
            vectorStore.put(vectorId, updatedRecord);
            
            log.debug("Successfully updated vector in memory with vectorId {}", vectorId);
            return true;
            
        } catch (Exception e) {
            log.error("Error updating vector in memory", e);
            throw new AIServiceException("Failed to update vector in memory", e);
        }
    }
    
    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        try {
            log.debug("Getting vector from memory with vectorId {}", vectorId);
            return Optional.ofNullable(vectorStore.get(vectorId));
        } catch (Exception e) {
            log.error("Error getting vector from memory", e);
            throw new AIServiceException("Failed to get vector from memory", e);
        }
    }
    
    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        try {
            log.debug("Getting vector from memory for entity {} of type {}", entityId, entityType);
            return vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()) && entityId.equals(record.getEntityId()))
                .findFirst();
        } catch (Exception e) {
            log.error("Error getting vector from memory by entity", e);
            throw new AIServiceException("Failed to get vector from memory by entity", e);
        }
    }
    
    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        try {
            log.debug("Searching vectors in memory for query: {}", request.getQuery());
            
            long startTime = System.currentTimeMillis();
            
            // Get entities to search
            String entityType = request.getEntityType();
            List<VectorRecord> entities = vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()))
                .filter(record -> matchesMetadata(record.getMetadata(), request.getMetadata()))
                .collect(Collectors.toList());
            
            if (entities.isEmpty()) {
                log.debug("No entities found for type: {}", entityType);
                return AISearchResponse.builder()
                    .results(new ArrayList<>())
                    .totalResults(0)
                    .maxScore(0.0)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .requestId(UUID.randomUUID().toString())
                    .query(request.getQuery())
                    .model(config.resolveEmbeddingDefaults().model())
                    .build();
            }
            
            // Calculate similarity scores
            List<Map<String, Object>> scoredEntities = entities.stream()
                .map(record -> {
                    double similarity = calculateCosineSimilarity(queryVector, record.getEmbedding());
                    
                    Map<String, Object> scoredEntity = new HashMap<>();
                    scoredEntity.put("vectorId", record.getVectorId());
                    scoredEntity.put("id", record.getEntityId());
                    scoredEntity.put("entityId", record.getEntityId());
                    scoredEntity.put("entityType", record.getEntityType());
                    scoredEntity.put("vectorSpace", record.getEntityType());
                    scoredEntity.put("content", record.getContent());
                    scoredEntity.put("metadata", record.getMetadata());
                    scoredEntity.put("similarity", similarity);
                    scoredEntity.put("score", similarity);
                    return scoredEntity;
                })
                .filter(entity -> (Double) entity.get("similarity") >= request.getThreshold())
                .sorted((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")))
                .limit(request.getLimit())
                .collect(Collectors.toList());
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            log.debug("Found {} results in memory in {}ms", scoredEntities.size(), processingTime);
            
            return AISearchResponse.builder()
                .results(scoredEntities)
                .totalResults(scoredEntities.size())
                .maxScore(scoredEntities.isEmpty() ? 0.0 : (Double) scoredEntities.get(0).get("similarity"))
                .processingTimeMs(processingTime)
                .requestId(UUID.randomUUID().toString())
                .query(request.getQuery())
                .model(config.resolveEmbeddingDefaults().model())
                .build();
                
        } catch (Exception e) {
            log.error("Error searching vectors in memory", e);
            throw new AIServiceException("Failed to search vectors in memory", e);
        }
    }
    
    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
        try {
            log.debug("Searching vectors in memory for entity type: {}", entityType);
            
            long startTime = System.currentTimeMillis();
            
            List<VectorRecord> entities = vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()))
                .collect(Collectors.toList());
            
            if (entities.isEmpty()) {
                log.debug("No entities found for type: {}", entityType);
                return AISearchResponse.builder()
                    .results(new ArrayList<>())
                    .totalResults(0)
                    .maxScore(0.0)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .requestId(UUID.randomUUID().toString())
                    .query("")
                    .model(config.resolveEmbeddingDefaults().model())
                    .build();
            }
            
            // Calculate similarity scores
            List<Map<String, Object>> scoredEntities = entities.stream()
                .map(record -> {
                    double similarity = calculateCosineSimilarity(queryVector, record.getEmbedding());
                    
                    Map<String, Object> scoredEntity = new HashMap<>();
                    scoredEntity.put("vectorId", record.getVectorId());
                    scoredEntity.put("entityId", record.getEntityId());
                    scoredEntity.put("content", record.getContent());
                    scoredEntity.put("metadata", record.getMetadata());
                    scoredEntity.put("similarity", similarity);
                    scoredEntity.put("score", similarity);
                    return scoredEntity;
                })
                .filter(entity -> (Double) entity.get("similarity") >= threshold)
                .sorted((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")))
                .limit(limit)
                .collect(Collectors.toList());
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            log.debug("Found {} results in memory in {}ms", scoredEntities.size(), processingTime);
            
            return AISearchResponse.builder()
                .results(scoredEntities)
                .totalResults(scoredEntities.size())
                .maxScore(scoredEntities.isEmpty() ? 0.0 : (Double) scoredEntities.get(0).get("similarity"))
                .processingTimeMs(processingTime)
                .requestId(UUID.randomUUID().toString())
                .query("")
                .model(config.resolveEmbeddingDefaults().model())
                .build();
                
        } catch (Exception e) {
            log.error("Error searching vectors in memory by entity type", e);
            throw new AIServiceException("Failed to search vectors in memory by entity type", e);
        }
    }
    
    @Override
    public boolean removeVector(String entityType, String entityId) {
        try {
            log.debug("Removing vector from memory for entity {} of type {}", entityId, entityType);
            
            Optional<VectorRecord> recordToRemove = vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()) && entityId.equals(record.getEntityId()))
                .findFirst();
            
            if (recordToRemove.isPresent()) {
                vectorStore.remove(recordToRemove.get().getVectorId());
                log.debug("Successfully removed vector from memory for entity {} of type {}", entityId, entityType);
                return true;
            } else {
                log.warn("Vector not found for entity {} of type {}", entityId, entityType);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error removing vector from memory", e);
            throw new AIServiceException("Failed to remove vector from memory", e);
        }
    }
    
    @Override
    public boolean removeVectorById(String vectorId) {
        try {
            log.debug("Removing vector from memory with vectorId {}", vectorId);
            
            VectorRecord removed = vectorStore.remove(vectorId);
            if (removed != null) {
                log.debug("Successfully removed vector from memory with vectorId {}", vectorId);
                return true;
            } else {
                log.warn("Vector not found for vectorId {}", vectorId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error removing vector from memory by ID", e);
            throw new AIServiceException("Failed to remove vector from memory by ID", e);
        }
    }
    
    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        try {
            log.debug("Batch storing {} vectors in memory", vectors.size());
            
            List<String> vectorIds = new ArrayList<>();
            for (VectorRecord vector : vectors) {
                String vectorId = storeVector(
                    vector.getEntityType(),
                    vector.getEntityId(),
                    vector.getContent(),
                    vector.getEmbedding(),
                    vector.getMetadata()
                );
                vectorIds.add(vectorId);
            }
            
            log.debug("Successfully batch stored {} vectors in memory", vectors.size());
            return vectorIds;
            
        } catch (Exception e) {
            log.error("Error batch storing vectors in memory", e);
            throw new AIServiceException("Failed to batch store vectors in memory", e);
        }
    }
    
    @Override
    public int batchUpdateVectors(List<VectorRecord> vectors) {
        try {
            log.debug("Batch updating {} vectors in memory", vectors.size());
            
            int updatedCount = 0;
            for (VectorRecord vector : vectors) {
                if (updateVector(
                    vector.getVectorId(),
                    vector.getEntityType(),
                    vector.getEntityId(),
                    vector.getContent(),
                    vector.getEmbedding(),
                    vector.getMetadata()
                )) {
                    updatedCount++;
                }
            }
            
            log.debug("Successfully batch updated {} vectors in memory", updatedCount);
            return updatedCount;
            
        } catch (Exception e) {
            log.error("Error batch updating vectors in memory", e);
            throw new AIServiceException("Failed to batch update vectors in memory", e);
        }
    }
    
    @Override
    public int batchRemoveVectors(List<String> vectorIds) {
        try {
            log.debug("Batch removing {} vectors from memory", vectorIds.size());
            
            int removedCount = 0;
            for (String vectorId : vectorIds) {
                if (removeVectorById(vectorId)) {
                    removedCount++;
                }
            }
            
            log.debug("Successfully batch removed {} vectors from memory", removedCount);
            return removedCount;
            
        } catch (Exception e) {
            log.error("Error batch removing vectors from memory", e);
            throw new AIServiceException("Failed to batch remove vectors from memory", e);
        }
    }
    
    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        try {
            log.debug("Getting all vectors from memory for entity type {}", entityType);
            
            return vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()))
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("Error getting vectors from memory by entity type", e);
            throw new AIServiceException("Failed to get vectors from memory by entity type", e);
        }
    }
    
    @Override
    public long getVectorCountByEntityType(String entityType) {
        try {
            log.debug("Getting vector count from memory for entity type {}", entityType);
            
            return vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()))
                .count();
                
        } catch (Exception e) {
            log.error("Error getting vector count from memory by entity type", e);
            throw new AIServiceException("Failed to get vector count from memory by entity type", e);
        }
    }
    
    @Override
    public boolean vectorExists(String entityType, String entityId) {
        try {
            log.debug("Checking if vector exists in memory for entity {} of type {}", entityId, entityType);
            
            return vectorStore.values().stream()
                .anyMatch(record -> entityType.equals(record.getEntityType()) && entityId.equals(record.getEntityId()));
                
        } catch (Exception e) {
            log.error("Error checking if vector exists in memory", e);
            throw new AIServiceException("Failed to check if vector exists in memory", e);
        }
    }
    
    @Override
    public long clearVectors() {
        try {
            log.debug("Clearing all vectors from memory");
            
            int count = vectorStore.size();
            vectorStore.clear();
            
            log.debug("Successfully cleared {} vectors from memory", count);
            return count;
            
        } catch (Exception e) {
            log.error("Error clearing vectors from memory", e);
            throw new AIServiceException("Failed to clear vectors from memory", e);
        }
    }
    
    @Override
    public long clearVectorsByEntityType(String entityType) {
        try {
            log.debug("Clearing vectors from memory for entity type {}", entityType);
            
            List<String> vectorIdsToRemove = vectorStore.values().stream()
                .filter(record -> entityType.equals(record.getEntityType()))
                .map(VectorRecord::getVectorId)
                .collect(Collectors.toList());
            
            for (String vectorId : vectorIdsToRemove) {
                vectorStore.remove(vectorId);
            }
            
            log.debug("Successfully cleared {} vectors from memory for entity type {}", vectorIdsToRemove.size(), entityType);
            return vectorIdsToRemove.size();
            
        } catch (Exception e) {
            log.error("Error clearing vectors from memory by entity type", e);
            throw new AIServiceException("Failed to clear vectors from memory by entity type", e);
        }
    }
    
    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("type", "memory");
        stats.put("totalVectors", vectorStore.size());
        
        Map<String, Long> entityTypeCounts = vectorStore.values().stream()
            .collect(Collectors.groupingBy(VectorRecord::getEntityType, Collectors.counting()));
        
        stats.put("entityTypes", entityTypeCounts.keySet());
        stats.put("entityTypeCounts", entityTypeCounts);
        return stats;
    }
    
    /**
     * Calculate cosine similarity between two vectors
     */
    private double calculateCosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        if (vectorA == null || vectorB == null || vectorA.size() != vectorB.size()) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < vectorA.size(); i++) {
            double a = vectorA.get(i);
            double b = vectorB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }
        
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        Object rawValue = safeMetadata.get("raw");
        if (rawValue == null) {
            safeMetadata.put("raw", MetadataJsonSerializer.serialize(stripRaw(safeMetadata)));
        } else {
            safeMetadata.put("raw", rawValue.toString());
        }
        return safeMetadata;
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey("raw")) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("raw");
        return copy;
    }

    private boolean matchesMetadata(Map<String, Object> metadata, Map<String, Object> metadataEquals) {
        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return true;
        }
        Map<String, Object> candidate = metadata == null ? Collections.emptyMap() : metadata;
        for (Map.Entry<String, Object> entry : metadataEquals.entrySet()) {
            if (!Objects.equals(candidate.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
