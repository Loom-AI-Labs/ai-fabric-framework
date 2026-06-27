package ai.fabric.rag;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.util.VectorMetadataFilterSupport;
import ai.fabric.util.VectorRecordProjection;
import ai.fabric.vector.VectorProviderCapabilities;
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
     * Capability check: does this provider support metadata filtering for similarity/vector search.
     *
     * <p>This is intentionally narrower than lifecycle/admin scan filtering. Implementations should
     * use provider-native filters where possible and must fail closed when a requested filter cannot
     * be represented safely. A provider can support metadata filters on similarity search without
     * being able to page through all vectors with the same filter semantics.</p>
     */
    default boolean supportsSearchMetadataFiltering() {
        return supportsMetadataFiltering();
    }

    /**
     * Capability check: does this provider support metadata filtering during vector scan/admin
     * operations.
     *
     * <p>This is the capability governance catalog and admin surfaces should use when they need
     * reliable metadata-filtered scans, not just metadata-filtered similarity search. Filtering can
     * be provider-native or adapter-side after an exact paged scan, but it must preserve AI Fabric's
     * portable exact-match semantics and fail closed for unsupported filter shapes.</p>
     */
    default boolean supportsScanMetadataFiltering() {
        return supportsMetadataFiltering();
    }

    /**
     * Legacy broad metadata filtering flag.
     *
     * <p>New code should prefer {@link #supportsSearchMetadataFiltering()} and
     * {@link #supportsScanMetadataFiltering()} so providers can advertise search and admin/scan
     * support independently.</p>
     */
    default boolean supportsMetadataFiltering() {
        return false;
    }

    /**
     * Capability check: does this provider support exact vector retrieval by vector id.
     */
    default boolean supportsExactFetchById() {
        return false;
    }

    /**
     * Capability check: does this provider support clearing vectors by entity type without a
     * caller-maintained sidecar catalog.
     */
    default boolean supportsClearByEntityType() {
        return false;
    }

    /**
     * Capability check: can this provider answer per-entity-type counts efficiently enough
     * for request-path overview/routing work?
     *
     * <p>Providers that need to enumerate full collections/classes just to answer a count
     * should override this to {@code false}. The overview service will then fall back to
     * lightweight presence checks instead of expensive full scans. The default is conservative so
     * new providers must explicitly opt into exact-count request-path behavior.</p>
     */
    default boolean supportsEfficientEntityTypeCount() {
        return false;
    }

    /**
     * Stable provider name for diagnostics and release capability evidence.
     */
    default String vectorProviderName() {
        return getClass().getSimpleName();
    }

    /**
     * Native client or execution path used by this vector provider.
     */
    default String vectorNativeClient() {
        return getClass().getName();
    }

    /**
     * How similarity-search metadata filters are applied. Providers that support search metadata
     * filtering should override this with a concrete value.
     */
    default String vectorSearchFilterMode() {
        return "";
    }

    /**
     * How scan/admin metadata filters are applied. Providers that support scan metadata filtering
     * should override this with a concrete value.
     */
    default String vectorScanFilterMode() {
        return "";
    }

    /**
     * Metadata filter subset this provider can preserve without widening results.
     */
    default String vectorMetadataFilterSubset() {
        return "";
    }

    /**
     * How entity-type counts are answered for admin and overview flows.
     */
    default String vectorEntityTypeCountMode() {
        return "";
    }

    /**
     * How clear-by-entity-type is implemented for admin and lifecycle flows.
     */
    default String vectorEntityTypeClearMode() {
        return "";
    }

    /**
     * Provider consistency model relevant to lifecycle/admin verification.
     */
    default String vectorConsistencyModel() {
        return "";
    }

    /**
     * Whether records survive process restart without relying on caller memory.
     */
    default boolean vectorDurableStorage() {
        return true;
    }

    /**
     * Whether this provider is safe to select under production profiles without an explicit
     * operator acknowledgement.
     */
    default boolean vectorProductionProfileSafe() {
        return vectorDurableStorage();
    }

    /**
     * Typed provider capability descriptor for docs, readiness checks, and contract tests.
     */
    default VectorProviderCapabilities vectorCapabilities() {
        return VectorProviderCapabilities.builder()
            .providerName(vectorProviderName())
            .providerClass(getClass().getName())
            .nativeClient(vectorNativeClient())
            .vectorScan(supportsVectorScan())
            .searchMetadataFiltering(supportsSearchMetadataFiltering())
            .scanMetadataFiltering(supportsScanMetadataFiltering())
            .exactFetchById(supportsExactFetchById())
            .clearByEntityType(supportsClearByEntityType())
            .efficientEntityTypeCount(supportsEfficientEntityTypeCount())
            .hybridSearch(supportsHybridSearch())
            .keywordSearch(supportsKeywordSearch())
            .searchFilterMode(vectorSearchFilterMode())
            .scanFilterMode(vectorScanFilterMode())
            .metadataFilterSubset(vectorMetadataFilterSubset())
            .entityTypeCountMode(vectorEntityTypeCountMode())
            .entityTypeClearMode(vectorEntityTypeClearMode())
            .consistencyModel(vectorConsistencyModel())
            .durableStorage(vectorDurableStorage())
            .productionProfileSafe(vectorProductionProfileSafe())
            .build();
    }

    /**
     * Provider diagnostics intended for admin/readiness surfaces.
     *
     * <p>Implementations may expose resolved provider scope details such as namespace,
     * collection prefix, tenant, or database so platform verification can compare
     * runtime state with the platform's modeled tenant-scoped handle.</p>
     */
    default Map<String, Object> adminDiagnostics() {
        VectorProviderCapabilities capabilities = vectorCapabilities();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("provider", capabilities.providerName());
        diagnostics.put("providerClass", capabilities.providerClass());
        diagnostics.put("nativeClient", capabilities.nativeClient());
        diagnostics.put("supportsVectorScan", capabilities.vectorScan());
        diagnostics.put("supportsMetadataFiltering", supportsMetadataFiltering());
        diagnostics.put("supportsSearchMetadataFiltering", capabilities.searchMetadataFiltering());
        diagnostics.put("supportsScanMetadataFiltering", capabilities.scanMetadataFiltering());
        diagnostics.put("supportsExactFetchById", capabilities.exactFetchById());
        diagnostics.put("supportsClearByEntityType", capabilities.clearByEntityType());
        diagnostics.put("supportsEfficientEntityTypeCount", capabilities.efficientEntityTypeCount());
        diagnostics.put("supportsHybridSearch", capabilities.hybridSearch());
        diagnostics.put("supportsKeywordSearch", capabilities.keywordSearch());
        diagnostics.put("metadataFilteredSearch", capabilities.searchMetadataFiltering());
        diagnostics.put("metadataFilteredScan", capabilities.scanMetadataFiltering());
        diagnostics.put("searchFilterMode", capabilities.searchFilterMode());
        diagnostics.put("scanFilterMode", capabilities.scanFilterMode());
        diagnostics.put("metadataFilterSubset", capabilities.metadataFilterSubset());
        diagnostics.put("countMode", capabilities.entityTypeCountMode());
        diagnostics.put("clearMode", capabilities.entityTypeClearMode());
        diagnostics.put("consistencyModel", capabilities.consistencyModel());
        diagnostics.put("persistent", capabilities.durableStorage());
        diagnostics.put("productionProfileSafe", capabilities.productionProfileSafe());
        diagnostics.put("countFallbacks", Map.of());
        diagnostics.put("countFallbackReasons", Map.of());
        diagnostics.put("capabilities", capabilities.toMap());
        return diagnostics;
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
     * should override this method. Providers that do not support it return an
     * empty result set by default so callers can degrade gracefully.</p>
     *
     * @param queryText the original query text
     * @param request the search request
     * @return keyword search results
     */
    default AISearchResponse keywordSearch(String queryText, AISearchRequest request) {
        Logger log = LoggerFactory.getLogger(this.getClass());
        log.debug("Keyword search not supported by {} - returning empty result set",
            this.getClass().getSimpleName());
        String resolvedQuery = queryText != null
            ? queryText
            : (request != null ? request.getQuery() : null);
        return AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .maxScore(0.0d)
            .processingTimeMs(0L)
            .query(resolvedQuery)
            .model(this.getClass().getSimpleName())
            .build();
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
                .map(record -> VectorRecordProjection.projectForScan(record, request))
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
        return VectorMetadataFilterSupport.matchesPortableEquals(recordMetadata, filters);
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
