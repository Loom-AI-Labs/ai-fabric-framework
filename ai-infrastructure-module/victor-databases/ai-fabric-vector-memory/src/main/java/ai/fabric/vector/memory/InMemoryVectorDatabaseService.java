package ai.fabric.vector.memory;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.util.MetadataJsonSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    
    private final Map<String, VectorRecord> vectorStore = new ConcurrentHashMap<>();

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
                .embedding(copyEmbedding(embedding))
                .metadata(safeMetadata)
                .aiAnalysis(null)
                .createdAt(now)
                .updatedAt(now)
                .vectorMetadata(new LinkedHashMap<>())
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
                .embedding(copyEmbedding(embedding))
                .metadata(safeMetadata)
                .aiAnalysis(existingRecord.getAiAnalysis())
                .createdAt(existingRecord.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .vectorMetadata(copyMap(existingRecord.getVectorMetadata()))
                .similarityScore(existingRecord.getSimilarityScore())
                .active(existingRecord.getActive())
                .version(nextVersion(existingRecord.getVersion()))
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
            return Optional.ofNullable(vectorStore.get(vectorId)).map(this::copyRecord);
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
                .filter(record -> matchesEntity(record, entityType, entityId))
                .sorted(recordOrder())
                .findFirst()
                .map(this::copyRecord);
        } catch (Exception e) {
            log.error("Error getting vector from memory by entity", e);
            throw new AIServiceException("Failed to get vector from memory by entity", e);
        }
    }
    
    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        try {
            Objects.requireNonNull(request, "request must not be null");
            log.debug("Searching vectors in memory for query: {}", request.getQuery());
            
            long startTime = System.currentTimeMillis();

            List<Map<String, Object>> scoredEntities = scoreEntities(
                queryVector,
                request.getEntityType(),
                request.getMetadata(),
                normalizeLimit(request.getLimit()),
                normalizeThreshold(request.getThreshold()),
                true
            );
            long processingTime = System.currentTimeMillis() - startTime;
            log.debug("Found {} results in memory in {}ms", scoredEntities.size(), processingTime);
            return buildSearchResponse(scoredEntities, request.getQuery(), processingTime);
                
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
            List<Map<String, Object>> scoredEntities = scoreEntities(
                queryVector,
                entityType,
                null,
                normalizeLimit(limit),
                threshold,
                false
            );
            long processingTime = System.currentTimeMillis() - startTime;
            log.debug("Found {} results in memory in {}ms", scoredEntities.size(), processingTime);
            return buildSearchResponse(scoredEntities, "", processingTime);
                
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
                .filter(record -> matchesEntity(record, entityType, entityId))
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
                .filter(record -> Objects.equals(entityType, record.getEntityType()))
                .sorted(recordOrder())
                .map(this::copyRecord)
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
                .filter(record -> Objects.equals(entityType, record.getEntityType()))
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
                .anyMatch(record -> matchesEntity(record, entityType, entityId));
                
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
                .filter(record -> Objects.equals(entityType, record.getEntityType()))
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("type", "memory");
        stats.put("totalVectors", vectorStore.size());
        
        Map<String, Long> entityTypeCounts = vectorStore.values().stream()
            .collect(Collectors.groupingBy(
                VectorRecord::getEntityType,
                LinkedHashMap::new,
                Collectors.counting()
            ));
        
        stats.put("entityTypes", new ArrayList<>(entityTypeCounts.keySet()));
        stats.put("entityTypeCounts", entityTypeCounts);
        return stats;
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("provider", "memory");
        diagnostics.put("persistent", false);
        diagnostics.put("sharedStorage", false);
        diagnostics.put("supportsVectorScan", supportsVectorScan());
        diagnostics.put("supportsMetadataFiltering", supportsMetadataFiltering());
        diagnostics.put("totalVectors", vectorStore.size());
        return diagnostics;
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

    private List<Map<String, Object>> scoreEntities(List<Double> queryVector,
                                                    String entityType,
                                                    Map<String, Object> metadataFilter,
                                                    int limit,
                                                    double threshold,
                                                    boolean includeVectorSpace) {
        return vectorStore.values().stream()
            .filter(record -> Objects.equals(entityType, record.getEntityType()))
            .filter(record -> matchesMetadata(record.getMetadata(), metadataFilter))
            .filter(record -> hasComparableDimensions(queryVector, record.getEmbedding()))
            .map(record -> scoredEntity(record, calculateCosineSimilarity(queryVector, record.getEmbedding()), includeVectorSpace))
            .filter(entity -> (Double) entity.get("similarity") >= threshold)
            .sorted(searchResultOrder())
            .limit(limit)
            .collect(Collectors.toList());
    }

    private Map<String, Object> scoredEntity(VectorRecord record, double similarity, boolean includeVectorSpace) {
        Map<String, Object> scoredEntity = new LinkedHashMap<>();
        scoredEntity.put("vectorId", record.getVectorId());
        if (includeVectorSpace) {
            scoredEntity.put("id", record.getEntityId());
        }
        scoredEntity.put("entityId", record.getEntityId());
        scoredEntity.put("entityType", record.getEntityType());
        if (includeVectorSpace) {
            scoredEntity.put("vectorSpace", record.getEntityType());
        }
        scoredEntity.put("content", record.getContent());
        scoredEntity.put("metadata", copyMap(record.getMetadata()));
        scoredEntity.put("similarity", similarity);
        scoredEntity.put("score", similarity);
        return scoredEntity;
    }

    private AISearchResponse buildSearchResponse(List<Map<String, Object>> results, String query, long processingTime) {
        return AISearchResponse.builder()
            .results(results)
            .totalResults(results.size())
            .maxScore(results.isEmpty() ? 0.0 : (Double) results.get(0).get("similarity"))
            .processingTimeMs(processingTime)
            .requestId(UUID.randomUUID().toString())
            .query(query)
            .model(config.resolveEmbeddingDefaults().model())
            .build();
    }

    private boolean hasComparableDimensions(List<Double> queryVector, List<Double> recordEmbedding) {
        return queryVector != null
            && recordEmbedding != null
            && !queryVector.isEmpty()
            && queryVector.size() == recordEmbedding.size();
    }

    private int normalizeLimit(Integer limit) {
        return limit != null && limit > 0 ? limit : 10;
    }

    private double normalizeThreshold(Double threshold) {
        return threshold != null ? threshold : 0.7;
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
            if (!metadataValueMatches(candidate.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean metadataValueMatches(Object actual, Object expected) {
        if (expected == null) {
            return actual == null;
        }
        return actual != null && String.valueOf(expected).equals(String.valueOf(actual));
    }

    private boolean matchesEntity(VectorRecord record, String entityType, String entityId) {
        return Objects.equals(entityType, record.getEntityType())
            && Objects.equals(entityId, record.getEntityId());
    }

    private int nextVersion(Integer currentVersion) {
        return currentVersion == null ? 2 : currentVersion + 1;
    }

    private List<Double> copyEmbedding(List<Double> embedding) {
        return embedding == null ? null : new ArrayList<>(embedding);
    }

    private Map<String, Object> copyMap(Map<String, Object> map) {
        return map == null ? null : new LinkedHashMap<>(map);
    }

    private VectorRecord copyRecord(VectorRecord record) {
        return VectorRecord.builder()
            .vectorId(record.getVectorId())
            .entityType(record.getEntityType())
            .entityId(record.getEntityId())
            .content(record.getContent())
            .embedding(copyEmbedding(record.getEmbedding()))
            .metadata(copyMap(record.getMetadata()))
            .aiAnalysis(record.getAiAnalysis())
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .vectorMetadata(copyMap(record.getVectorMetadata()))
            .similarityScore(record.getSimilarityScore())
            .active(record.getActive())
            .version(record.getVersion())
            .build();
    }

    private Comparator<VectorRecord> recordOrder() {
        return Comparator
            .comparing((VectorRecord record) -> record.getUpdatedAt() != null ? record.getUpdatedAt() : record.getCreatedAt(),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(record -> record.getVectorId() != null ? record.getVectorId() : "");
    }

    private Comparator<Map<String, Object>> searchResultOrder() {
        return Comparator
            .<Map<String, Object>, Double>comparing(result -> (Double) result.get("similarity"), Comparator.reverseOrder())
            .thenComparing(result -> Objects.toString(result.get("entityId"), ""))
            .thenComparing(result -> Objects.toString(result.get("vectorId"), ""));
    }
}
