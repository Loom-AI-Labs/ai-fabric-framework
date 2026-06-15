package ai.fabric.rag;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Vector Database Service Interface
 * 
 * This interface defines the contract for vector database operations.
 * Different implementations can be provided for various vector databases
 * like Lucene, Pinecone, Chroma, etc.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
public interface VectorDatabaseService {

    /**
     * Capability check: does this provider support paged scan operations?
     */
    default boolean supportsVectorScan() {
        return false;
    }

    /**
     * Capability check: does this provider support efficient metadata filtering during scans?
     */
    default boolean supportsMetadataFiltering() {
        return false;
    }

    /**
     * Capability check: can this provider answer per-entity-type counts efficiently enough
     * for request-path overview/routing work?
     *
     * <p>Providers that need to enumerate full collections/classes just to answer a count
     * should override this to {@code false}. The overview service will then fall back to
     * lightweight presence checks instead of expensive full scans.</p>
     */
    default boolean supportsEfficientEntityTypeCount() {
        return true;
    }

    /**
     * Provider diagnostics intended for admin/readiness surfaces.
     *
     * <p>Implementations may expose resolved provider scope details such as namespace,
     * collection prefix, tenant, or database so platform verification can compare
     * runtime state with the platform's modeled tenant-scoped handle.</p>
     */
    default Map<String, Object> adminDiagnostics() {
        return new LinkedHashMap<>();
    }
    
    /**
     * Store a vector in the database
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     * @return the vector ID assigned by the database
     */
    String storeVector(String entityType, String entityId, String content, 
                      List<Double> embedding, Map<String, Object> metadata);
    
    /**
     * Update an existing vector in the database
     * 
     * @param vectorId the vector ID to update
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     * @return true if the vector was updated, false if not found
     */
    boolean updateVector(String vectorId, String entityType, String entityId, 
                        String content, List<Double> embedding, Map<String, Object> metadata);
    
    /**
     * Get a vector by its ID
     * 
     * @param vectorId the vector ID
     * @return the vector record if found
     */
    Optional<VectorRecord> getVector(String vectorId);
    
    /**
     * Get a vector by entity type and entity ID
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @return the vector record if found
     */
    Optional<VectorRecord> getVectorByEntity(String entityType, String entityId);
    
    /**
     * Search for similar vectors
     * 
     * @param queryVector the query vector
     * @param request the search request
     * @return search results with similarity scores
     */
    AISearchResponse search(List<Double> queryVector, AISearchRequest request);

    /**
     * Perform hybrid search combining vector similarity and keyword/text search.
     *
     * <p>This is an OPTIONAL method. Providers that support native hybrid search
     * should override this method. The default implementation falls back to
     * {@link #search(List, AISearchRequest)}.</p>
     *
     * @param queryVector the query vector for semantic search
     * @param queryText the original query text for keyword search
     * @param request the search request
     * @return search results combining vector + keyword signals
     */
    default AISearchResponse hybridSearch(List<Double> queryVector, String queryText, AISearchRequest request) {
        Logger log = LoggerFactory.getLogger(this.getClass());
        log.debug("Hybrid search not supported by {} - falling back to vector search",
            this.getClass().getSimpleName());
        return search(queryVector, request);
    }

    /**
     * Perform keyword/text-only search (BM25/full-text/etc.).
     *
     * <p>This is an OPTIONAL method. Providers that support keyword search
     * should override this method.</p>
     *
     * @param queryText the original query text
     * @param request the search request
     * @return keyword search results
     * @throws UnsupportedOperationException if not supported by provider
     */
    default AISearchResponse keywordSearch(String queryText, AISearchRequest request) {
        throw new UnsupportedOperationException(
            "Keyword search not supported by " + this.getClass().getSimpleName());
    }

    /**
     * Capability check: does this provider support actual hybrid search?
     *
     * @return true if hybrid search is supported
     */
    default boolean supportsHybridSearch() {
        return false;
    }

    /**
     * Capability check: does this provider support keyword search?
     *
     * @return true if keyword search is supported
     */
    default boolean supportsKeywordSearch() {
        return false;
    }
    
    /**
     * Search for similar vectors by entity type
     * 
     * @param queryVector the query vector
     * @param entityType the entity type to search within
     * @param limit maximum number of results
     * @param threshold minimum similarity threshold
     * @return search results with similarity scores
     */
    AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, 
                                       int limit, double threshold);
    
    /**
     * Remove a vector from the database
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @return true if the vector was removed, false if not found
     */
    boolean removeVector(String entityType, String entityId);
    
    /**
     * Remove a vector by its ID
     * 
     * @param vectorId the vector ID
     * @return true if the vector was removed, false if not found
     */
    boolean removeVectorById(String vectorId);
    
    /**
     * Batch store multiple vectors
     * 
     * @param vectors list of vector records to store
     * @return list of vector IDs assigned by the database
     */
    List<String> batchStoreVectors(List<VectorRecord> vectors);
    
    /**
     * Batch update multiple vectors
     * 
     * @param vectors list of vector records to update
     * @return number of vectors successfully updated
     */
    int batchUpdateVectors(List<VectorRecord> vectors);
    
    /**
     * Batch remove multiple vectors
     * 
     * @param vectorIds list of vector IDs to remove
     * @return number of vectors successfully removed
     */
    int batchRemoveVectors(List<String> vectorIds);
    
    /**
     * Get all vectors for a specific entity type
     * 
     * @param entityType the entity type
     * @return list of vector records
     */
    List<VectorRecord> getVectorsByEntityType(String entityType);
    
    /**
     * Get vector count by entity type
     * 
     * @param entityType the entity type
     * @return number of vectors for the entity type
     */
    long getVectorCountByEntityType(String entityType);
    
    /**
     * Check if a vector exists
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @return true if the vector exists
     */
    boolean vectorExists(String entityType, String entityId);

    /**
     * Paged scan over vectors for a given entityType, optionally filtered by metadata.
     *
     * <p>Providers may override for efficient server-side scans. The default implementation falls back to
     * {@link #getVectorsByEntityType(String)} and performs filtering + paging in-memory.</p>
     */
    default VectorScanPage scan(VectorScanRequest request) {
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .nextCursor(null)
                .hasMore(false)
                .build();
        }

        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;
        int offset = decodeOffsetCursor(request.getCursor());

        List<VectorRecord> all = getVectorsByEntityType(request.getEntityType());
        if (all == null || all.isEmpty()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .nextCursor(null)
                .hasMore(false)
                .build();
        }

        Map<String, Object> metadataEquals = request.getMetadataEquals();

        // Stable ordering so cursor paging is deterministic.
        List<VectorRecord> filtered = all.stream()
            .filter(Objects::nonNull)
            .filter(record -> metadataEquals == null || metadataEquals.isEmpty() || metadataMatches(record.getMetadata(), metadataEquals))
            .sorted(Comparator
                .comparing((VectorRecord r) -> r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.getVectorId() != null ? r.getVectorId() : "", Comparator.naturalOrder())
            )
            .collect(Collectors.toList());

        if (offset >= filtered.size()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .nextCursor(null)
                .hasMore(false)
                .build();
        }

        int end = Math.min(filtered.size(), offset + limit);
        List<VectorRecord> page = new ArrayList<>(filtered.subList(offset, end));

        // Strip heavy fields if requested.
        if (!request.isIncludeEmbedding() || !request.isIncludeContent() || !request.isIncludeMetadata()) {
            page = page.stream()
                .map(record -> VectorRecord.builder()
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
                    .build())
                .collect(Collectors.toList());
        }

        boolean hasMore = end < filtered.size();
        String nextCursor = hasMore ? encodeOffsetCursor(end) : null;

        return VectorScanPage.builder()
            .vectors(page)
            .nextCursor(nextCursor)
            .hasMore(hasMore)
            .build();
    }

    private static boolean metadataMatches(Map<String, Object> recordMetadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (recordMetadata == null || recordMetadata.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            Object actual = recordMetadata.get(key);
            if (expected == null) {
                if (actual != null) {
                    return false;
                }
            } else if (actual == null) {
                return false;
            } else if (!String.valueOf(expected).equals(String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    private static String encodeOffsetCursor(int offset) {
        String raw = "offset:" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeOffsetCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (raw.startsWith("offset:")) {
                return Integer.parseInt(raw.substring("offset:".length()));
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }
    
    /**
     * Get statistics about the vector store
     * 
     * @return map of statistics
     */
    Map<String, Object> getStatistics();
    
    /**
     * Clear all vectors from the database
     * 
     * @return number of vectors cleared
     */
    long clearVectors();
    
    /**
     * Clear all vectors for a specific entity type
     * 
     * @param entityType the entity type
     * @return number of vectors cleared
     */
    long clearVectorsByEntityType(String entityType);
}
