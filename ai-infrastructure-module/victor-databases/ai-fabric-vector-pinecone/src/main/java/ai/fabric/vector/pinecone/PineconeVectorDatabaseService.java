package ai.fabric.vector.pinecone;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.exception.AIServiceException;
import ai.fabric.util.MetadataJsonSerializer;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.configs.PineconeConfig;
import io.pinecone.configs.PineconeConnection;
import io.pinecone.proto.DescribeIndexStatsResponse;
import io.pinecone.proto.FetchResponse;
import io.pinecone.proto.ListItem;
import io.pinecone.proto.ListResponse;
import io.pinecone.proto.NamespaceSummary;
import io.pinecone.proto.SparseValues;
import io.pinecone.proto.UpsertRequest;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import jakarta.annotation.PreDestroy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Pinecone Vector Database Service
 * 
 * This service provides vector database operations using Pinecone for production
 * environments where high-scale vector search is required.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
public class PineconeVectorDatabaseService implements VectorDatabaseService, AutoCloseable {

    private static final String DEFAULT_NAMESPACE = "default";
    private static final String EMBEDDING_BASE64_FIELD = "embeddingBase64";
    private static final String EMBEDDING_DIM_FIELD = "embeddingDim";
    private static final Set<String> RESERVED_METADATA_FIELDS =
        Set.of("entityType", "entityId", "content", EMBEDDING_BASE64_FIELD, EMBEDDING_DIM_FIELD);

    private final AIProviderConfig.PineconeConfig config;
    private final VectorDatabaseConfig vectorDatabaseConfig;
    private final String indexName;
    private final String namespacePrefix;
    private final PineconeConnection connection;
    private final Index index;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Boolean sparseIndex;

    public PineconeVectorDatabaseService(AIProviderConfig providerConfig) {
        this(providerConfig, null, Index::new);
    }

    public PineconeVectorDatabaseService(AIProviderConfig providerConfig, VectorDatabaseConfig vectorDatabaseConfig) {
        this(providerConfig, vectorDatabaseConfig, Index::new);
    }

    public PineconeVectorDatabaseService(AIProviderConfig providerConfig, PineconeIndexFactory indexFactory) {
        this(providerConfig, null, indexFactory);
    }

    public PineconeVectorDatabaseService(AIProviderConfig providerConfig,
                                         VectorDatabaseConfig vectorDatabaseConfig,
                                         PineconeIndexFactory indexFactory) {
        this.config = Objects.requireNonNull(providerConfig.getPinecone(), "Pinecone configuration must be present");
        this.vectorDatabaseConfig = vectorDatabaseConfig != null ? vectorDatabaseConfig : new VectorDatabaseConfig();
        this.indexName = resolveIndexName(this.config);
        this.namespacePrefix = normalizeNamespacePrefix(this.config.getNamespacePrefix());
        this.connection = buildConnection(this.config, this.indexName);
        this.index = Objects.requireNonNull(indexFactory, "Pinecone indexFactory must be provided")
            .create(connection, indexName);
        log.info("Pinecone client configured for index '{}'", indexName);
    }

    PineconeVectorDatabaseService(AIProviderConfig providerConfig, PineconeConnection connection, Index index) {
        this.config = Objects.requireNonNull(providerConfig.getPinecone(), "Pinecone configuration must be present");
        this.vectorDatabaseConfig = new VectorDatabaseConfig();
        this.indexName = resolveIndexName(this.config);
        this.namespacePrefix = normalizeNamespacePrefix(this.config.getNamespacePrefix());
        this.connection = Objects.requireNonNull(connection, "Pinecone connection must be provided");
        this.index = Objects.requireNonNull(index, "Pinecone index must be provided");
        log.info("Pinecone client configured for index '{}'", indexName);
    }

    @Override
    public boolean supportsVectorScan() {
        return true;
    }

    @Override
    public boolean supportsMetadataFiltering() {
        return false;
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("sharedStorage", !namespacePrefix.isBlank());
        diagnostics.put("scopeType", namespacePrefix.isBlank() ? "INDEX" : "NAMESPACE_PREFIX");
        diagnostics.put("rootResourceLabel", "Index");
        diagnostics.put("rootResourceValue", indexName);
        diagnostics.put("scopePrefix", namespacePrefix);
        if (!namespacePrefix.isBlank()) {
            diagnostics.put("scopePattern", namespacePrefix + "__<entity-type>");
        }
        if (StringUtils.hasText(config.getApiHost())) {
            diagnostics.put("apiHost", config.getApiHost().trim());
        }
        return diagnostics;
    }

    @Override
    public String storeVector(String entityType, String entityId, String content, 
                           List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        String vectorId = buildVectorId(entityType, entityId);
        upsertVector(vectorId, namespace(entityType), entityType, entityId, content, embedding, metadata);
        return vectorId;
    }
    
    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content, 
                              List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        if (!StringUtils.hasText(vectorId)) {
            return false;
        }
        String namespace = extractNamespace(vectorId);
        upsertVector(vectorId, namespace, entityType, entityId, content, embedding, metadata);
        return true;
    }
    
    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        ensureEnabled();
        if (!StringUtils.hasText(vectorId)) {
            return Optional.empty();
        }

        String namespace = extractNamespace(vectorId);
        FetchResponse response;
        try {
            response = withPineconeRetry("fetch", () -> index.fetch(List.of(vectorId), namespace));
        } catch (Exception ex) {
            // Missing namespaces are common for fresh indexes and should be treated as "not found".
            if (isNamespaceNotFound(ex)) {
                return Optional.empty();
            }
            throw new AIServiceException("Pinecone fetch failed", ex);
        }
        if (response == null || !response.containsVectors(vectorId)) {
            return Optional.empty();
        }
        return Optional.of(toVectorRecord(response.getVectorsOrThrow(vectorId), namespace));
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        String vectorId = buildVectorId(entityType, entityId);
        return getVector(vectorId);
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        ensureEnabled();
        if (CollectionUtils.isEmpty(queryVector)) {
            throw new AIServiceException("Query vector is required for Pinecone search");
        }
        if (request == null) {
            throw new AIServiceException("Pinecone search requires a request");
        }

        long start = System.currentTimeMillis();

        String namespace = namespace(request.getEntityType());
        int topK = resolveSearchLimit(request);
        double threshold = resolveSearchThreshold(request);

        Struct filter = toFilterStruct(request.getMetadata()).orElse(null);

        QueryResponseWithUnsignedIndices response;
        boolean preferSparse = isSparseIndex();
        try {
            if (preferSparse) {
                response = querySparse(topK, queryVector, namespace, filter);
            } else {
                response = index.queryByVector(topK, toFloatList(queryVector), namespace, filter, false, true);
            }
        } catch (Exception ex) {
            if (isNamespaceNotFound(ex)) {
                return emptySearchResponse(request, namespace, start);
            }
            if (!preferSparse && shouldRetryAsSparse(ex)) {
                sparseIndex = true;
                response = querySparse(topK, queryVector, namespace, filter);
            } else {
                throw new AIServiceException("Pinecone search failed", ex);
            }
        }

        List<ScoredVectorWithUnsignedIndices> matches = response != null ? response.getMatchesList() : List.of();

        List<Map<String, Object>> results = new ArrayList<>();
        double maxScore = 0.0;

        for (ScoredVectorWithUnsignedIndices match : matches) {
            double score = match.getScore();
            if (score < threshold) {
                continue;
            }
            maxScore = Math.max(maxScore, score);

            Map<String, Object> metadata = fromStruct(match.getMetadata());
            String content = metadata != null ? Objects.toString(metadata.get("content"), null) : null;
            String entityId = metadata != null ? Objects.toString(metadata.get("entityId"), null) : null;
            String entityType = metadata != null ? Objects.toString(metadata.get("entityType"), request.getEntityType()) : request.getEntityType();
            if (!StringUtils.hasText(entityId)) {
                entityId = extractEntityId(match.getId());
            }
            if (!StringUtils.hasText(entityType)) {
                entityType = extractNamespace(match.getId());
            }

            Map<String, Object> customMetadata = new LinkedHashMap<>();
            if (metadata != null) {
                metadata.forEach((key, value) -> {
                    if (!RESERVED_METADATA_FIELDS.contains(key)) {
                        customMetadata.put(key, value);
                    }
                });
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vectorId", match.getId());
            row.put("id", entityId);
            row.put("entityId", entityId);
            row.put("entityType", entityType);
            row.put("vectorSpace", entityType);
            row.put("content", content);
            row.put("metadata", customMetadata);
            row.put("score", score);
            row.put("similarity", score);
            results.add(row);
        }

        return AISearchResponse.builder()
            .results(results)
            .totalResults(results.size())
            .maxScore(maxScore)
            .processingTimeMs(System.currentTimeMillis() - start)
            .requestId(UUID.randomUUID().toString())
            .query(request.getQuery())
            .model(namespace)
            .build();
    }

    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
        AISearchRequest request = AISearchRequest.builder()
            .query("")
            .entityType(entityType)
            .limit(limit)
            .threshold(threshold)
            .build();
        return search(queryVector, request);
    }

    @Override
    public boolean removeVector(String entityType, String entityId) {
        return removeVectorById(buildVectorId(entityType, entityId));
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        ensureEnabled();
        if (!StringUtils.hasText(vectorId)) {
            return false;
        }

        String namespace = extractNamespace(vectorId);
        try {
            index.deleteByIds(List.of(vectorId), namespace);
        } catch (Exception ex) {
            // Deletion should be idempotent: a missing namespace/vector is effectively "already deleted".
            if (!isNamespaceNotFound(ex)) {
                throw new AIServiceException("Pinecone delete failed", ex);
            }
        }
        return true;
    }

    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        if (CollectionUtils.isEmpty(vectors)) {
            return List.of();
        }

        List<String> ids = new ArrayList<>(vectors.size());
        for (VectorRecord record : vectors) {
            ids.add(storeVector(record.getEntityType(), record.getEntityId(), record.getContent(), record.getEmbedding(), record.getMetadata()));
        }
        return ids;
    }

    @Override
    public int batchUpdateVectors(List<VectorRecord> vectors) {
        if (CollectionUtils.isEmpty(vectors)) {
            return 0;
        }
        int updated = 0;
        for (VectorRecord record : vectors) {
            if (record.getVectorId() == null) {
                continue;
            }
            if (updateVector(record.getVectorId(), record.getEntityType(), record.getEntityId(), record.getContent(), record.getEmbedding(), record.getMetadata())) {
                updated++;
            }
        }
        return updated;
    }

    @Override
    public int batchRemoveVectors(List<String> vectorIds) {
        if (CollectionUtils.isEmpty(vectorIds)) {
            return 0;
        }

        Map<String, List<String>> grouped = vectorIds.stream().filter(StringUtils::hasText).collect(Collectors.groupingBy(this::extractNamespace));
        int removed = 0;
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            try {
                index.deleteByIds(entry.getValue(), entry.getKey());
            } catch (Exception ex) {
                if (!isNamespaceNotFound(ex)) {
                    throw new AIServiceException("Pinecone batch delete failed", ex);
                }
            }
            removed += entry.getValue().size();
        }
        return removed;
    }
    
    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        ensureEnabled();
        String namespace = namespace(entityType);

        List<String> ids = new ArrayList<>();
        String token = null;
        int pageSize = 100;

        do {
            ListResponse response;
            try {
                response = token == null
                    ? index.list(namespace, pageSize)
                    : index.list(namespace, pageSize, token);
            } catch (Exception ex) {
                if (isNamespaceNotFound(ex)) {
                    return List.of();
                }
                throw new AIServiceException("Pinecone list failed", ex);
            }

            if (response == null) {
                break;
            }

            for (ListItem item : response.getVectorsList()) {
                if (StringUtils.hasText(item.getId())) {
                    ids.add(item.getId());
                }
            }

            token = response.hasPagination() ? response.getPagination().getNext() : null;
        } while (StringUtils.hasText(token));

        if (ids.isEmpty()) {
            return List.of();
        }

        List<VectorRecord> records = new ArrayList<>(ids.size());
        for (List<String> chunk : chunk(ids, 100)) {
            FetchResponse fetch;
            try {
                fetch = index.fetch(chunk, namespace);
            } catch (Exception ex) {
                if (isNamespaceNotFound(ex)) {
                    return List.of();
                }
                throw new AIServiceException("Pinecone fetch failed", ex);
            }
            if (fetch == null) {
                continue;
            }
            fetch.getVectorsMap().values().forEach(vector -> records.add(toVectorRecord(vector, namespace)));
        }

        return records;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        ensureEnabled();
        if (request == null || !StringUtils.hasText(request.getEntityType())) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        String namespace = namespace(request.getEntityType());
        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;

        String token = decodeListTokenCursor(request.getCursor());
        final List<VectorRecord> matched = new ArrayList<>(limit + 1);

        while (matched.size() < limit + 1) {
            ListResponse response;
            try {
                int pageSize = Math.min(500, Math.max(1, limit));
                response = token == null
                    ? index.list(namespace, pageSize)
                    : index.list(namespace, pageSize, token);
            } catch (Exception ex) {
                if (isNamespaceNotFound(ex)) {
                    return VectorScanPage.builder()
                        .vectors(List.of())
                        .hasMore(false)
                        .nextCursor(null)
                        .build();
                }
                throw new AIServiceException("Pinecone list failed", ex);
            }

            if (response == null) {
                break;
            }

            List<String> ids = response.getVectorsList().stream()
                .map(ListItem::getId)
                .filter(StringUtils::hasText)
                .limit(1000)
                .toList();

            if (!ids.isEmpty()) {
                for (List<String> chunk : chunk(ids, 100)) {
                    FetchResponse fetch;
                    try {
                        fetch = index.fetch(chunk, namespace);
                    } catch (Exception ex) {
                        if (isNamespaceNotFound(ex)) {
                            return VectorScanPage.builder()
                                .vectors(List.of())
                                .hasMore(false)
                                .nextCursor(null)
                                .build();
                        }
                        throw new AIServiceException("Pinecone fetch failed", ex);
                    }
                    if (fetch == null) {
                        continue;
                    }
                    fetch.getVectorsMap().values().forEach(vector -> {
                        VectorRecord record = toVectorRecord(vector, namespace);
                        if (matchesMetadata(record, request.getMetadataEquals())) {
                            matched.add(applyScanProjection(record, request));
                        }
                    });
                }
            }

            token = response.hasPagination() ? response.getPagination().getNext() : null;
            if (!StringUtils.hasText(token)) {
                token = null;
                break;
            }
        }

        boolean hasMore = matched.size() > limit || token != null;
        List<VectorRecord> pageVectors = matched.size() > limit ? matched.subList(0, limit) : matched;

        return VectorScanPage.builder()
            .vectors(pageVectors)
            .hasMore(hasMore && token != null)
            .nextCursor(token != null ? encodeListTokenCursor(token) : null)
            .build();
    }

    private AISearchResponse emptySearchResponse(AISearchRequest request, String namespace, long startMs) {
        return AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .maxScore(0.0)
            .processingTimeMs(System.currentTimeMillis() - startMs)
            .requestId(UUID.randomUUID().toString())
            .query(request != null ? request.getQuery() : null)
            .model(namespace)
            .build();
    }

    private int resolveSearchLimit(AISearchRequest request) {
        Integer requestedLimit = request != null ? request.getLimit() : null;
        if (requestedLimit == null || requestedLimit <= 0) {
            return 10;
        }
        return Math.min(requestedLimit, 100);
    }

    private double resolveSearchThreshold(AISearchRequest request) {
        Double requestedThreshold = request != null ? request.getThreshold() : null;
        if (requestedThreshold == null || !Double.isFinite(requestedThreshold)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, requestedThreshold));
    }
    
    @Override
    public long getVectorCountByEntityType(String entityType) {
        ensureEnabled();
        String namespace = namespace(entityType);
        DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);
        if (stats == null) {
            return 0L;
        }
        NamespaceSummary summary = stats.getNamespacesOrDefault(namespace, NamespaceSummary.getDefaultInstance());
        return summary.getVectorCount();
    }
    
    @Override
    public boolean vectorExists(String entityType, String entityId) {
        return getVectorByEntity(entityType, entityId).isPresent();
    }
    
    @Override
    public long clearVectors() {
        ensureEnabled();
        DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);
        if (stats == null) {
            return 0L;
        }

        long cleared = 0;
        for (Map.Entry<String, NamespaceSummary> entry : stats.getNamespacesMap().entrySet()) {
            String namespace = entry.getKey();
            int count = entry.getValue() != null ? entry.getValue().getVectorCount() : 0;
            cleared += count;
            try {
                clearNamespace(namespace);
            } catch (Exception ex) {
                // Pinecone can return NOT_FOUND when a namespace doesn't exist (race/empty namespace).
                // Clearing is best-effort; ignore "namespace not found" and continue.
                if (!isNamespaceNotFound(ex)) {
                    throw new AIServiceException("Failed to clear all vectors", ex);
                }
            }
        }
        return cleared;
    }
    
    @Override
    public long clearVectorsByEntityType(String entityType) {
        ensureEnabled();
        String namespace = namespace(entityType);
        long count = 0L;
        try {
            DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);
            if (stats != null) {
                NamespaceSummary summary = stats.getNamespacesOrDefault(namespace, NamespaceSummary.getDefaultInstance());
                count = summary.getVectorCount();
            }
        } catch (Exception ex) {
            // best-effort; still attempt deletion
        }

        try {
            clearNamespace(namespace);
        } catch (Exception ex) {
            if (!isNamespaceNotFound(ex)) {
                throw new AIServiceException("Failed to clear vectors for entity type", ex);
            }
        }
        return count;
    }

    /**
     * Clear a namespace deterministically when configured to await clear consistency.
     *
     * <p>Pinecone's {@code deleteAll(namespace)} is asynchronous and can race with subsequent upserts in the
     * same namespace. When deterministic clears are required (tests/CI, admin automation), prefer a
     * synchronous delete-by-IDs loop so we do not leave a background delete task that can remove
     * newly written vectors.</p>
     */
    private void clearNamespace(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            return;
        }

        if (shouldAwaitClearConsistency()) {
            clearNamespaceByListing(namespace);
        } else {
            withPineconeRetryVoid("deleteAll", () -> index.deleteAll(namespace));
        }

        awaitNamespaceCleared(namespace);
    }

    private void clearNamespaceByListing(String namespace) {
        long deadlineMs = System.currentTimeMillis() + resolveAwaitClearTimeoutMs();
        long sleepMs = 250;

        while (System.currentTimeMillis() < deadlineMs) {
            ListResponse listed;
            try {
                listed = withPineconeRetry("list", () -> index.list(namespace, 100));
            } catch (Exception ex) {
                if (isNamespaceNotFound(ex)) {
                    return;
                }
                throw ex;
            }

            if (listed == null || listed.getVectorsCount() == 0) {
                return;
            }

            List<String> ids = listed.getVectorsList().stream()
                .map(ListItem::getId)
                .filter(StringUtils::hasText)
                .toList();

            if (ids.isEmpty()) {
                return;
            }

            try {
                withPineconeRetryVoid("deleteByIds", () -> index.deleteByIds(ids, namespace));
            } catch (Exception ex) {
                if (!isNamespaceNotFound(ex)) {
                    throw ex;
                }
            }

            sleepQuietly(sleepMs);
            sleepMs = Math.min(2000, sleepMs * 2);
        }

        log.warn("Timed out waiting for Pinecone namespace '{}' to clear via list+delete", namespace);
    }

    /**
     * Pinecone namespace deletions are asynchronous; poll until the namespace is empty so tests/CI don't race deletes.
     */
    private void awaitNamespaceCleared(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            return;
        }

        if (!shouldAwaitClearConsistency()) {
            return;
        }

        long deadlineMs = System.currentTimeMillis() + resolveAwaitClearTimeoutMs();
        long sleepMs = 250;

        while (System.currentTimeMillis() < deadlineMs) {
            try {
                // Stats can lag behind deleteAll() for a while. Prefer listing, which reflects read visibility.
                ListResponse listed = withPineconeRetry("list", () -> index.list(namespace, 1));
                if (listed == null || listed.getVectorsCount() == 0) {
                    return;
                }
            } catch (Exception ex) {
                if (isNamespaceNotFound(ex)) {
                    return;
                }
                // best-effort polling
            }

            sleepQuietly(sleepMs);
            sleepMs = Math.min(2000, sleepMs * 2);
        }

        log.warn("Timed out waiting for Pinecone namespace '{}' to clear", namespace);
    }

    private boolean shouldAwaitClearConsistency() {
        return vectorDatabaseConfig == null
            || vectorDatabaseConfig.getOperations() == null
            || !Boolean.FALSE.equals(vectorDatabaseConfig.getOperations().getAwaitClearConsistency());
    }

    private long resolveAwaitClearTimeoutMs() {
        if (vectorDatabaseConfig == null || vectorDatabaseConfig.getOperations() == null) {
            return 30_000L;
        }
        Long configured = vectorDatabaseConfig.getOperations().getAwaitClearTimeoutMs();
        if (configured == null) {
            return 30_000L;
        }
        return Math.max(1L, configured);
    }
    
    @Override
    public Map<String, Object> getStatistics() {
        ensureEnabled();
        DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "pinecone");
        response.put("indexName", indexName);
        response.put("host", config.getApiHost());
        response.put("namespacePrefix", namespacePrefix);
        response.put("dimension", stats != null ? stats.getDimension() : null);
        response.put("totalVectorCount", stats != null ? stats.getTotalVectorCount() : null);
        response.put("namespaces", stats != null ? stats.getNamespacesMap().keySet() : Set.of());
        return response;
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new AIServiceException("Pinecone vector provider is disabled");
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new AIServiceException("Pinecone apiKey must be configured");
        }
        if (!StringUtils.hasText(indexName)) {
            throw new AIServiceException("Pinecone indexName must be configured");
        }
    }

    private PineconeConnection buildConnection(AIProviderConfig.PineconeConfig config, String indexName) {
        if (config == null || !config.isEnabled()) {
            throw new AIServiceException("Pinecone vector provider is disabled");
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new AIServiceException("Pinecone apiKey must be configured");
        }
        if (!StringUtils.hasText(indexName)) {
            throw new AIServiceException("Pinecone indexName must be configured");
        }

        String host = resolveIndexHost(config);
        PineconeConfig pineconeConfig = new PineconeConfig(config.getApiKey());
        pineconeConfig.setHost(host);
        return new PineconeConnection(pineconeConfig);
    }

    private String resolveIndexName(AIProviderConfig.PineconeConfig config) {
        if (config == null) {
            throw new AIServiceException("Pinecone configuration must be present");
        }
        if (StringUtils.hasText(config.getIndexName())) {
            return config.getIndexName().trim();
        }
        if (StringUtils.hasText(config.getApiHost())) {
            String host = config.getApiHost().trim();
            try {
                URI uri = host.startsWith("http://") || host.startsWith("https://")
                    ? URI.create(host)
                    : URI.create("https://" + host);
                String hostname = uri.getHost();
                if (!StringUtils.hasText(hostname)) {
                    hostname = uri.getAuthority();
                }
                if (!StringUtils.hasText(hostname)) {
                    throw new AIServiceException("Unable to derive Pinecone indexName from apiHost");
                }
                int dot = hostname.indexOf('.');
                return dot > 0 ? hostname.substring(0, dot) : hostname;
            } catch (IllegalArgumentException ex) {
                throw new AIServiceException("Unable to derive Pinecone indexName from apiHost", ex);
            }
        }
        throw new AIServiceException("Pinecone indexName must be configured");
    }

    private String resolveIndexHost(AIProviderConfig.PineconeConfig config) {
        if (StringUtils.hasText(config.getApiHost())) {
            return config.getApiHost().trim();
        }
        if (StringUtils.hasText(config.getProjectId())) {
            return String.format("https://%s-%s.svc.%s.pinecone.io",
                config.getIndexName(), config.getProjectId(), config.getEnvironment());
        }
        return String.format("https://%s.svc.%s.pinecone.io",
            config.getIndexName(), config.getEnvironment());
    }

    private void upsertVector(String vectorId,
                              String namespace,
                              String entityType,
                              String entityId,
                              String content,
                              List<Double> embedding,
                              Map<String, Object> metadata) {
        if (!StringUtils.hasText(vectorId)) {
            throw new AIServiceException("Pinecone vectorId is required");
        }
        if (CollectionUtils.isEmpty(embedding)) {
            throw new AIServiceException("Pinecone upsert requires a non-empty embedding vector");
        }

        Struct metadataStruct = buildMetadataStruct(entityType, entityId, content, embedding, metadata);

        boolean preferSparse = isSparseIndex();
        try {
            withPineconeRetryVoid("upsert", () -> {
                if (preferSparse) {
                    upsertSparse(vectorId, namespace, embedding, metadataStruct, true);
                } else {
                    upsertDense(vectorId, namespace, embedding, metadataStruct);
                }
            });
        } catch (Exception ex) {
            if (!preferSparse && shouldRetryAsSparse(ex)) {
                sparseIndex = true;
                withPineconeRetryVoid("upsertSparse", () -> upsertSparse(vectorId, namespace, embedding, metadataStruct, true));
                return;
            }
            throw new AIServiceException("Failed to upsert vector into Pinecone", ex);
        }
    }

    private void withPineconeRetryVoid(String operation, Runnable runnable) {
        withPineconeRetry(operation, () -> {
            runnable.run();
            return Boolean.TRUE;
        });
    }

    private <T> T withPineconeRetry(String operation, Supplier<T> supplier) {
        int maxAttempts = 6;
        long backoffMs = 250;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (Exception ex) {
                if (attempt >= maxAttempts || !isRetryablePineconeException(ex)) {
                    throw ex;
                }
                long sleepMs = backoffMs + ThreadLocalRandom.current().nextLong(0, 200);
                log.warn(
                    "Pinecone {} failed due to rate/availability (attempt {}/{}). Retrying after {}ms. Cause: {}",
                    operation,
                    attempt,
                    maxAttempts,
                    sleepMs,
                    ex.getMessage()
                );
                sleepQuietly(sleepMs);
                backoffMs = Math.min(5000, backoffMs * 2);
            }
        }

        // unreachable
        return supplier.get();
    }

    private boolean isRetryablePineconeException(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof StatusRuntimeException statusEx) {
                Status status = statusEx.getStatus();
                Status.Code code = status != null ? status.getCode() : null;
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.DEADLINE_EXCEEDED) {
                    return true;
                }
            }

            String message = cursor.getMessage();
            if (StringUtils.hasText(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("too many requests") || lower.contains("rate limit") || lower.contains("throttl")) {
                    return true;
                }
            }

            cursor = cursor.getCause();
        }

        return false;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void upsertDense(String vectorId, String namespace, List<Double> embedding, Struct metadataStruct) {
        List<Float> values = toFloatList(embedding);
        index.upsert(vectorId, values, null, null, metadataStruct, namespace);
    }

    private void upsertSparse(String vectorId,
                              String namespace,
                              List<Double> embedding,
                              Struct metadataStruct,
                              boolean sparseOnly) {
        List<Long> sparseIndices = new ArrayList<>(embedding.size());
        List<Float> sparseValues = new ArrayList<>(embedding.size());
        for (int i = 0; i < embedding.size(); i++) {
            sparseIndices.add((long) i);
            Double value = embedding.get(i);
            sparseValues.add(value != null ? value.floatValue() : 0.0f);
        }

        if (sparseOnly) {
            List<Integer> protoIndices = sparseIndices.stream()
                .map(Long::intValue)
                .collect(Collectors.toList());

            io.pinecone.proto.Vector vector = io.pinecone.proto.Vector.newBuilder()
                .setId(vectorId)
                .setMetadata(metadataStruct)
                .setSparseValues(
                    SparseValues.newBuilder()
                        .addAllIndices(protoIndices)
                        .addAllValues(sparseValues)
                        .build()
                )
                .build();

            UpsertRequest request = UpsertRequest.newBuilder()
                .setNamespace(namespace)
                .addVectors(vector)
                .build();

            connection.getBlockingStub().upsert(request);
            return;
        }

        List<Float> denseValues = toFloatList(embedding);
        index.upsert(vectorId, denseValues, sparseIndices, sparseValues, metadataStruct, namespace);
    }

    private boolean isSparseIndex() {
        Boolean cached = sparseIndex;
        if (cached != null) {
            return cached;
        }

        try {
            DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);
            cached = stats != null && stats.getDimension() == 0;
        } catch (Exception ex) {
            cached = false;
        }

        sparseIndex = cached;
        return cached;
    }

    private boolean shouldRetryAsSparse(Exception ex) {
        try {
            DescribeIndexStatsResponse stats = withPineconeRetry("describeIndexStats", index::describeIndexStats);
            if (stats != null && stats.getDimension() == 0) {
                return true;
            }
        } catch (Exception ignored) {
            // ignore
        }

        String message = ex != null ? ex.getMessage() : null;
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("sparse");
    }

    private boolean isNamespaceNotFound(Throwable ex) {
        if (ex == null) {
            return false;
        }

        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof StatusRuntimeException statusEx) {
                Status status = statusEx.getStatus();
                if (status != null && status.getCode() == Status.Code.NOT_FOUND) {
                    return true;
                }
            }

            String message = cursor.getMessage();
            if (StringUtils.hasText(message) && message.toLowerCase(Locale.ROOT).contains("namespace not found")) {
                return true;
            }

            cursor = cursor.getCause();
        }

        return false;
    }

    private QueryResponseWithUnsignedIndices querySparse(int topK, List<Double> queryVector, String namespace, Struct filter) {
        List<Long> indices = new ArrayList<>(queryVector.size());
        List<Float> values = new ArrayList<>(queryVector.size());
        for (int i = 0; i < queryVector.size(); i++) {
            indices.add((long) i);
            values.add(queryVector.get(i).floatValue());
        }
        return index.query(topK, null, indices, values, null, namespace, filter, false, true);
    }

    private String extractEntityId(String vectorId) {
        return extractEntityIdOptional(vectorId).orElse(null);
    }

    private Optional<String> extractEntityIdOptional(String vectorId) {
        if (!StringUtils.hasText(vectorId)) {
            return Optional.empty();
        }
        int idx = vectorId.indexOf("::");
        if (idx < 0) {
            return Optional.of(vectorId);
        }
        return Optional.of(vectorId.substring(idx + 2));
    }

    private Struct buildMetadataStruct(String entityType,
                                      String entityId,
                                      String content,
                                      List<Double> embedding,
                                      Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;

        Map<String, Value> fields = new LinkedHashMap<>();
        fields.put("entityType", Value.newBuilder().setStringValue(entityType).build());
        fields.put("entityId", Value.newBuilder().setStringValue(entityId).build());
        if (content != null) {
            fields.put("content", Value.newBuilder().setStringValue(content).build());
        }

        fields.put(EMBEDDING_DIM_FIELD, Value.newBuilder().setNumberValue(embedding.size()).build());
        fields.put(EMBEDDING_BASE64_FIELD, Value.newBuilder().setStringValue(encodeEmbeddingBase64(embedding)).build());

        String raw = MetadataJsonSerializer.serialize(stripRaw(safeMetadata));
        fields.put("raw", Value.newBuilder().setStringValue(raw).build());

        safeMetadata.forEach((key, value) -> {
            if (!"raw".equals(key)) {
                fields.put(key, toValue(value));
            }
        });

        return Struct.newBuilder().putAllFields(fields).build();
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey("raw")) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("raw");
        return copy;
    }

    private Value toValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (obj instanceof String value) {
            return Value.newBuilder().setStringValue(value).build();
        }
        if (obj instanceof Boolean value) {
            return Value.newBuilder().setBoolValue(value).build();
        }
        if (obj instanceof Number number) {
            return Value.newBuilder().setNumberValue(number.doubleValue()).build();
        }
        if (obj instanceof List<?> list) {
            List<Value> values = list.stream().map(this::toValue).toList();
            return Value.newBuilder()
                .setListValue(ListValue.newBuilder().addAllValues(values))
                .build();
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, Value> nested = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    nested.put(String.valueOf(key), toValue(value));
                }
            });
            return Value.newBuilder()
                .setStructValue(Struct.newBuilder().putAllFields(nested))
                .build();
        }
        return Value.newBuilder().setStringValue(obj.toString()).build();
    }

    private Struct toStruct(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Struct.getDefaultInstance();
        }
        Map<String, Value> fields = new LinkedHashMap<>();
        values.forEach((key, value) -> fields.put(key, toValue(value)));
        return Struct.newBuilder().putAllFields(fields).build();
    }

    /**
     * Build a Pinecone metadata filter struct.
     *
     * Pinecone expects filter values to be "operator objects" (e.g. {"field": {"$eq": "x"}}).
     * Lists/collections must be represented via operators such as "$in", not raw arrays.
     * Null/blank values are skipped to avoid INVALID_ARGUMENT errors.
     */
    private Optional<Struct> toFilterStruct(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Value> fields = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (!StringUtils.hasText(key)) {
                return;
            }
            toFilterCondition(value).ifPresent(condition -> fields.put(key, condition));
        });

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Struct.newBuilder().putAllFields(fields).build());
    }

    private Optional<Value> toFilterCondition(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String str && !StringUtils.hasText(str)) {
            return Optional.empty();
        }

        // If caller already provided a Pinecone-style operator map, keep it (but skip nulls).
        if (value instanceof Map<?, ?> map) {
            Struct struct = toFilterOperatorStruct(map);
            if (struct == null || struct.getFieldsCount() == 0) {
                return Optional.empty();
            }
            return Optional.of(Value.newBuilder().setStructValue(struct).build());
        }

        // Collections/lists must use operators like "$in" instead of a raw ListValue.
        if (value instanceof Iterable<?> iterable) {
            List<Value> items = new ArrayList<>();
            for (Object item : iterable) {
                toFilterScalarValue(item).ifPresent(items::add);
            }
            if (items.isEmpty()) {
                return Optional.empty();
            }
            Struct condition = Struct.newBuilder()
                .putFields("$in", Value.newBuilder()
                    .setListValue(ListValue.newBuilder().addAllValues(items))
                    .build())
                .build();
            return Optional.of(Value.newBuilder().setStructValue(condition).build());
        }

        Optional<Value> scalar = toFilterScalarValue(value);
        if (scalar.isEmpty()) {
            return Optional.empty();
        }
        Struct condition = Struct.newBuilder()
            .putFields("$eq", scalar.get())
            .build();
        return Optional.of(Value.newBuilder().setStructValue(condition).build());
    }

    private Struct toFilterOperatorStruct(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return Struct.getDefaultInstance();
        }

        Map<String, Value> fields = new LinkedHashMap<>();
        map.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawValue == null) {
                return;
            }
            String key = String.valueOf(rawKey);
            if (!StringUtils.hasText(key)) {
                return;
            }

            // For "$in", ensure we emit a ListValue of scalars and skip nulls/blanks.
            if ("$in".equals(key) && rawValue instanceof Iterable<?> iterable) {
                List<Value> items = new ArrayList<>();
                for (Object item : iterable) {
                    toFilterScalarValue(item).ifPresent(items::add);
                }
                if (!items.isEmpty()) {
                    fields.put(key, Value.newBuilder()
                        .setListValue(ListValue.newBuilder().addAllValues(items))
                        .build());
                }
                return;
            }

            // Default: allow scalar or nested structures, but avoid embedding nulls.
            toFilterScalarValue(rawValue).ifPresent(scalar -> fields.put(key, scalar));
        });

        return Struct.newBuilder().putAllFields(fields).build();
    }

    private Optional<Value> toFilterScalarValue(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String str) {
            return StringUtils.hasText(str)
                ? Optional.of(Value.newBuilder().setStringValue(str).build())
                : Optional.empty();
        }
        if (value instanceof Boolean bool) {
            return Optional.of(Value.newBuilder().setBoolValue(bool).build());
        }
        if (value instanceof Number number) {
            return Optional.of(Value.newBuilder().setNumberValue(number.doubleValue()).build());
        }
        // As a fallback, treat as a keyword string value.
        String text = String.valueOf(value);
        return StringUtils.hasText(text)
            ? Optional.of(Value.newBuilder().setStringValue(text).build())
            : Optional.empty();
    }

    private Optional<Object> fromValue(Value value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> Optional.empty();
            case STRING_VALUE -> Optional.of(value.getStringValue());
            case BOOL_VALUE -> Optional.of(value.getBoolValue());
            case NUMBER_VALUE -> Optional.of(value.getNumberValue());
            case STRUCT_VALUE -> Optional.of(fromStruct(value.getStructValue()));
            case LIST_VALUE -> Optional.of(value.getListValue().getValuesList().stream()
                .map(item -> fromValue(item).orElse(null))
                .toList());
        };
    }

    private Map<String, Object> fromStruct(Struct struct) {
        if (struct == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((k, v) -> map.put(k, fromValue(v).orElse(null)));
        if (!map.containsKey("raw") && !map.isEmpty()) {
            map.put("raw", MetadataJsonSerializer.serialize(map));
        }
        return map;
    }

    private VectorRecord toVectorRecord(io.pinecone.proto.Vector vector, String namespace) {
        Map<String, Object> allMetadata = vector.hasMetadata() ? fromStruct(vector.getMetadata()) : Collections.emptyMap();

        String entityType = Objects.toString(allMetadata.getOrDefault("entityType", namespace), namespace);
        String entityId = Objects.toString(allMetadata.get("entityId"), null);
        String content = Objects.toString(allMetadata.get("content"), null);

        Map<String, Object> metadata = new LinkedHashMap<>();
        allMetadata.forEach((key, value) -> {
            if (!RESERVED_METADATA_FIELDS.contains(key)) {
                metadata.put(key, value);
            }
        });

        List<Double> embedding = extractEmbedding(vector, allMetadata);

        LocalDateTime createdAt = readTimestamp(metadata, "_indexedCreatedAt").orElse(null);
        LocalDateTime updatedAt = readTimestamp(metadata, "_indexedUpdatedAt").orElse(null);

        return VectorRecord.builder()
            .vectorId(vector.getId())
            .entityType(entityType)
            .entityId(entityId)
            .content(content)
            .embedding(embedding)
            .metadata(metadata)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();
    }

    private static Optional<LocalDateTime> readTimestamp(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return Optional.empty();
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(raw.toString()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static boolean matchesMetadata(VectorRecord record, Map<String, Object> metadataEquals) {
        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return true;
        }
        if (record == null || record.getMetadata() == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : metadataEquals.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object expected = entry.getValue();
            Object actual = record.getMetadata().get(entry.getKey());
            if (!Objects.equals(String.valueOf(expected), String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    private static String encodeListTokenCursor(String token) {
        return encodeListTokenCursorOptional(token).orElse(null);
    }

    private static Optional<String> encodeListTokenCursorOptional(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String raw = "token:" + token;
        return Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String decodeListTokenCursor(String cursor) {
        return decodeListTokenCursorOptional(cursor).orElse(null);
    }

    private static Optional<String> decodeListTokenCursorOptional(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            if (!raw.startsWith("token:")) {
                return Optional.empty();
            }
            String token = raw.substring("token:".length());
            return StringUtils.hasText(token) ? Optional.of(token) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static VectorRecord applyScanProjection(VectorRecord record, VectorScanRequest request) {
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
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .similarityScore(record.getSimilarityScore())
            .build();
    }

    private List<Double> extractEmbedding(io.pinecone.proto.Vector vector, Map<String, Object> allMetadata) {
        Object encoded = allMetadata != null ? allMetadata.get(EMBEDDING_BASE64_FIELD) : null;
        if (encoded instanceof String base64 && StringUtils.hasText(base64)) {
            List<Double> decoded = decodeEmbeddingBase64(base64);
            if (!decoded.isEmpty()) {
                return decoded;
            }
        }

        if (vector != null && !vector.getValuesList().isEmpty()) {
            return vector.getValuesList().stream()
                .map(Float::doubleValue)
                .toList();
        }

        if (vector != null && vector.hasSparseValues()) {
            int dimension = resolveEmbeddingDimension(allMetadata);
            if (dimension > 0) {
                double[] embedding = new double[dimension];
                List<Integer> indices = vector.getSparseValues().getIndicesList();
                List<Float> values = vector.getSparseValues().getValuesList();
                for (int i = 0; i < indices.size() && i < values.size(); i++) {
                    int idx = indices.get(i);
                    if (idx >= 0 && idx < embedding.length) {
                        embedding[idx] = values.get(i);
                    }
                }
                List<Double> result = new ArrayList<>(embedding.length);
                for (double value : embedding) {
                    result.add(value);
                }
                return result;
            }
        }

        return List.of();
    }

    private int resolveEmbeddingDimension(Map<String, Object> allMetadata) {
        if (allMetadata == null) {
            return -1;
        }
        Object dim = allMetadata.get(EMBEDDING_DIM_FIELD);
        if (dim instanceof Number number) {
            return number.intValue();
        }
        if (dim instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String encodeEmbeddingBase64(List<Double> embedding) {
        if (CollectionUtils.isEmpty(embedding)) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.allocate(embedding.size() * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Double value : embedding) {
            buffer.putDouble(value != null ? value : 0.0d);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private List<Double> decodeEmbeddingBase64(String base64) {
        if (!StringUtils.hasText(base64)) {
            return List.of();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0) {
                return List.of();
            }

            if (bytes.length % Double.BYTES == 0) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int count = bytes.length / Double.BYTES;
                List<Double> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(buffer.getDouble());
                }
                return values;
            }

            if (bytes.length % Float.BYTES == 0) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int count = bytes.length / Float.BYTES;
                List<Double> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add((double) buffer.getFloat());
                }
                return values;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return List.of();
    }

    private List<Float> toFloatList(List<Double> embedding) {
        List<Float> floats = new ArrayList<>(embedding.size());
        for (Double value : embedding) {
            floats.add(value.floatValue());
        }
        return floats;
    }

    private String buildVectorId(String entityType, String entityId) {
        return namespace(entityType) + "::" + entityId;
    }

    static String scopedNamespace(String entityType, String namespacePrefix) {
        String base = StringUtils.hasText(entityType) ? entityType.trim() : DEFAULT_NAMESPACE;
        String normalizedPrefix = normalizeNamespacePrefix(namespacePrefix);
        if (!StringUtils.hasText(normalizedPrefix)) {
            return base;
        }
        if (normalizedPrefix.endsWith("__")) {
            return normalizedPrefix + base;
        }
        return normalizedPrefix + "__" + base;
    }

    private String namespace(String entityType) {
        return scopedNamespace(entityType, namespacePrefix);
    }

    private String extractNamespace(String vectorId) {
        if (!StringUtils.hasText(vectorId)) {
            return DEFAULT_NAMESPACE;
        }
        int idx = vectorId.indexOf("::");
        if (idx <= 0) {
            return DEFAULT_NAMESPACE;
        }
        return vectorId.substring(0, idx);
    }

    private static String normalizeNamespacePrefix(String namespacePrefix) {
        return namespacePrefix == null ? "" : namespacePrefix.trim();
    }

    private List<List<String>> chunk(List<String> ids, int chunkSize) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (chunkSize <= 0 || ids.size() <= chunkSize) {
            return List.of(ids);
        }
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += chunkSize) {
            chunks.add(ids.subList(i, Math.min(i + chunkSize, ids.size())));
        }
        return chunks;
    }

    @PreDestroy
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                index.close();
            } catch (Exception ex) {
                log.debug("Failed to close Pinecone index connection", ex);
            }
        }
    }
}
