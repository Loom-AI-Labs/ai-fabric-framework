package com.ai.infrastructure.vector.qdrant;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.VectorDatabaseConfig;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.VectorRecord;
import com.ai.infrastructure.dto.VectorScanPage;
import com.ai.infrastructure.dto.VectorScanRequest;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.util.MetadataJsonSerializer;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Vector database service backed by Qdrant. The official Java client is used for
 * explicit gRPC deployments; REST is used when preferGrpc=false.
 */
@Slf4j
public class QdrantVectorDatabaseService implements VectorDatabaseService, AutoCloseable {

    private static final String EMBEDDING_PAYLOAD_FIELD = "embedding";
    private static final String KNOWLEDGE_SOURCE_HANDLE_REF_FIELD = "knowledgeSourceHandleRef";
    private static final Set<String> RESERVED_PAYLOAD_FIELDS = Set.of("entityType", "entityId", "content", EMBEDDING_PAYLOAD_FIELD);
    private static final List<String> REQUIRED_KEYWORD_PAYLOAD_INDEX_FIELDS = List.of(KNOWLEDGE_SOURCE_HANDLE_REF_FIELD);

    private final AIProviderConfig.QdrantConfig config;
    private final VectorDatabaseConfig vectorDatabaseConfig;
    private final String collectionPrefix;
    private final QdrantClient qdrantClient;
    private final QdrantRestVectorDatabaseService restDelegate;
    private final ConcurrentMap<String, Boolean> collectionCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> payloadIndexCache = new ConcurrentHashMap<>();

    public QdrantVectorDatabaseService(AIProviderConfig providerConfig) {
        this(providerConfig, null);
    }

    public QdrantVectorDatabaseService(AIProviderConfig providerConfig, VectorDatabaseConfig vectorDatabaseConfig) {
        this(providerConfig, vectorDatabaseConfig, createClientIfGrpcPreferred(providerConfig));
    }

    QdrantVectorDatabaseService(AIProviderConfig providerConfig,
                                VectorDatabaseConfig vectorDatabaseConfig,
                                QdrantClient qdrantClient) {
        this.config = Objects.requireNonNull(providerConfig.getQdrant(), "Qdrant configuration must be present");
        this.vectorDatabaseConfig = vectorDatabaseConfig != null ? vectorDatabaseConfig : new VectorDatabaseConfig();
        this.collectionPrefix = normalizeCollectionPrefix(this.config.getCollectionPrefix());
        this.qdrantClient = qdrantClient;
        this.restDelegate = qdrantClient == null
            ? new QdrantRestVectorDatabaseService(providerConfig, this.vectorDatabaseConfig)
            : null;
    }

    @Override
    public boolean supportsVectorScan() {
        return true;
    }

    @Override
    public boolean supportsMetadataFiltering() {
        return true;
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        if (restDelegate != null) {
            return restDelegate.adminDiagnostics();
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("sharedStorage", !collectionPrefix.isBlank());
        diagnostics.put("scopeType", collectionPrefix.isBlank() ? "COLLECTION" : "COLLECTION_PREFIX");
        diagnostics.put("rootResourceLabel", "Endpoint");
        diagnostics.put("rootResourceValue", config.getHost());
        diagnostics.put("scopePrefix", collectionPrefix);
        if (!collectionPrefix.isBlank()) {
            diagnostics.put("scopePattern", collectionPrefix + "<entity_type>");
        }
        diagnostics.put("preferGrpc", Boolean.TRUE.equals(config.getPreferGrpc()));
        diagnostics.put("transport", "grpc");
        return diagnostics;
    }

    @Override
    public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        if (restDelegate != null) {
            return restDelegate.storeVector(entityType, entityId, content, embedding, metadata);
        }
        ensureEnabled();
        if (embedding == null || embedding.isEmpty()) {
            throw new AIServiceException("Qdrant storeVector requires a non-empty embedding vector");
        }

        String collection = collectionName(entityType);
        ensureCollection(collection, embedding.size());
        String vectorId = buildVectorId(entityType, entityId);

        List<Float> vector = toFloatList(embedding);

        Map<String, JsonWithInt.Value> payload = new LinkedHashMap<>();
        payload.put("entityType", ValueFactory.value(entityType));
        payload.put("entityId", ValueFactory.value(entityId));
        if (content != null) {
            payload.put("content", ValueFactory.value(content));
        }

        Map<String, Object> safeMetadata = metadata == null ? Collections.emptyMap() : metadata;
        String rawMetadata = MetadataJsonSerializer.serialize(stripRaw(safeMetadata));
        payload.put("raw", ValueFactory.value(rawMetadata));
        payload.put(EMBEDDING_PAYLOAD_FIELD, toValue(embedding));

        safeMetadata.forEach((key, value) -> {
            if (key == null || value == null || "raw".equals(key) || RESERVED_PAYLOAD_FIELDS.contains(key)) {
                return;
            }
            payload.put(key, toValue(value));
        });

        Points.PointStruct point = Points.PointStruct.newBuilder()
            .setId(PointIdFactory.id(parseVectorUuid(vectorId)))
            .setVectors(VectorsFactory.vectors(vector))
            .putAllPayload(payload)
            .build();

        await(qdrantClient.upsertAsync(collection, List.of(point)), "upsert point");
        return vectorId;
    }

    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        if (restDelegate != null) {
            return restDelegate.updateVector(vectorId, entityType, entityId, content, embedding, metadata);
        }
        ensureEnabled();
        if (vectorId == null || vectorId.isBlank()) {
            return false;
        }
        if (embedding == null || embedding.isEmpty()) {
            throw new AIServiceException("Qdrant updateVector requires a non-empty embedding vector");
        }

        String collection = collectionName(entityType);
        ensureCollection(collection, embedding.size());

        List<Float> vector = toFloatList(embedding);

        Map<String, JsonWithInt.Value> payload = new LinkedHashMap<>();
        payload.put("entityType", ValueFactory.value(entityType));
        payload.put("entityId", ValueFactory.value(entityId));
        if (content != null) {
            payload.put("content", ValueFactory.value(content));
        }

        Map<String, Object> safeMetadata = metadata == null ? Collections.emptyMap() : metadata;
        String rawMetadata = MetadataJsonSerializer.serialize(stripRaw(safeMetadata));
        payload.put("raw", ValueFactory.value(rawMetadata));
        payload.put(EMBEDDING_PAYLOAD_FIELD, toValue(embedding));
        safeMetadata.forEach((key, value) -> {
            if (key == null || value == null || "raw".equals(key) || RESERVED_PAYLOAD_FIELDS.contains(key)) {
                return;
            }
            payload.put(key, toValue(value));
        });

        Points.PointStruct point = Points.PointStruct.newBuilder()
            .setId(PointIdFactory.id(parseVectorUuid(vectorId)))
            .setVectors(VectorsFactory.vectors(vector))
            .putAllPayload(payload)
            .build();

        await(qdrantClient.upsertAsync(collection, List.of(point)), "update point");
        return true;
    }

    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        if (restDelegate != null) {
            return restDelegate.getVector(vectorId);
        }
        ensureEnabled();
        if (vectorId == null || vectorId.isBlank()) {
            return Optional.empty();
        }

        UUID id = parseVectorUuid(vectorId);

        for (String collection : listCandidateCollections()) {
            try {
                List<Points.RetrievedPoint> points = await(qdrantClient.retrieveAsync(
                    collection,
                    List.of(PointIdFactory.id(id)),
                    WithPayloadSelectorFactory.enable(true),
                    WithVectorsSelectorFactory.enable(true),
                    null
                ), "retrieve point");

                if (!CollectionUtils.isEmpty(points)) {
                    return Optional.of(toVectorRecord(collection, points.getFirst(), null));
                }
            } catch (AIServiceException ignored) {
                // Best-effort scan across collections.
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        if (restDelegate != null) {
            return restDelegate.getVectorByEntity(entityType, entityId);
        }
        String vectorId = buildVectorId(entityType, entityId);
        ensureEnabled();
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return Optional.empty();
        }

        List<Points.RetrievedPoint> points = await(qdrantClient.retrieveAsync(
            collection,
            List.of(PointIdFactory.id(parseVectorUuid(vectorId))),
            WithPayloadSelectorFactory.enable(true),
            WithVectorsSelectorFactory.enable(true),
            null
        ), "retrieve point by entity");

        if (CollectionUtils.isEmpty(points)) {
            return Optional.empty();
        }
        return Optional.of(toVectorRecord(collection, points.getFirst(), null));
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        if (restDelegate != null) {
            return restDelegate.search(queryVector, request);
        }
        ensureEnabled();
        if (request == null) {
            throw new AIServiceException("Qdrant search requires a request");
        }
        if (CollectionUtils.isEmpty(queryVector)) {
            throw new AIServiceException("Qdrant search requires a non-empty query vector");
        }

        String entityType = request.getEntityType();
        int limit = Optional.ofNullable(request.getLimit()).orElse(10);
        double threshold = Optional.ofNullable(request.getThreshold()).orElse(0.0);

        List<String> collections = resolveSearchCollections(entityType);
        if (collections.isEmpty()) {
            return AISearchResponse.builder()
                .query(request.getQuery())
                .results(List.of())
                .totalResults(0)
                .model("qdrant")
                .build();
        }

        List<Float> queryVectorFloat = toFloatList(queryVector);

        List<VectorRecord> allResults = new ArrayList<>();
        for (String collection : collections) {
            ensureCollection(collection, queryVector.size());
            Common.Filter filter = buildMetadataFilter(request.getMetadata());

            Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(queryVectorFloat)
                .setLimit(limit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setWithVectors(WithVectorsSelectorFactory.enable(true));

            if (filter != null) {
                searchBuilder.setFilter(filter);
            }

            List<Points.ScoredPoint> scored;
            try {
                scored = await(qdrantClient.searchAsync(searchBuilder.build()), "search points");
            } catch (AIServiceException ex) {
                if (filter == null || !isMissingPayloadIndexFailure(ex)) {
                    throw ex;
                }
                log.warn(
                    "Qdrant filtered search for collection '{}' failed because a required payload index is missing. "
                        + "Retrying without server-side metadata filtering and relying on client-side source scoping.",
                    collection
                );
                Points.SearchPoints fallbackSearch = Points.SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(queryVectorFloat)
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .setWithVectors(WithVectorsSelectorFactory.enable(true))
                    .build();
                scored = await(qdrantClient.searchAsync(fallbackSearch), "search points without metadata filter");
            }
            if (CollectionUtils.isEmpty(scored)) {
                continue;
            }

            for (Points.ScoredPoint point : scored) {
                double score = point.getScore();
                if (score < threshold) {
                    continue;
                }
                allResults.add(toVectorRecord(collection, point, score));
            }
        }

        List<Map<String, Object>> mapped = allResults.stream()
            .sorted((left, right) -> Double.compare(
                Optional.ofNullable(right.getSimilarityScore()).orElse(0.0),
                Optional.ofNullable(left.getSimilarityScore()).orElse(0.0)))
            .limit(limit)
            .map(record -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vectorId", record.getVectorId());
                row.put("id", record.getEntityId());
                row.put("entityId", record.getEntityId());
                row.put("entityType", record.getEntityType());
                row.put("vectorSpace", record.getEntityType());
                row.put("content", record.getContent());
                row.put("metadata", record.getMetadata());
                row.put("score", record.getSimilarityScore());
                row.put("similarity", record.getSimilarityScore());
                return row;
            })
            .toList();

        return AISearchResponse.builder()
            .query(request.getQuery())
            .results(mapped)
            .totalResults(mapped.size())
            .model("qdrant")
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
        if (restDelegate != null) {
            return restDelegate.removeVector(entityType, entityId);
        }
        ensureEnabled();
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return false;
        }

        String vectorId = buildVectorId(entityType, entityId);
        Optional<VectorRecord> existing = getVectorByEntity(entityType, entityId);
        if (existing.isEmpty()) {
            return false;
        }
        await(qdrantClient.deleteAsync(collection, List.of(PointIdFactory.id(parseVectorUuid(vectorId)))), "delete point");
        return true;
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        if (restDelegate != null) {
            return restDelegate.removeVectorById(vectorId);
        }
        ensureEnabled();
        if (vectorId == null || vectorId.isBlank()) {
            return false;
        }

        UUID id = parseVectorUuid(vectorId);
        boolean removed = false;
        for (String collection : listCandidateCollections()) {
            try {
                await(qdrantClient.deleteAsync(collection, List.of(PointIdFactory.id(id))), "delete point");
                removed = true;
            } catch (AIServiceException ignored) {
                // Best-effort scan across collections.
            }
        }
        return removed;
    }

    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        if (CollectionUtils.isEmpty(vectors)) {
            return Collections.emptyList();
        }

        List<String> ids = new ArrayList<>(vectors.size());
        for (VectorRecord record : vectors) {
            ids.add(storeVector(record.getEntityType(), record.getEntityId(), record.getContent(),
                record.getEmbedding(), record.getMetadata()));
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
            if (updateVector(record.getVectorId(), record.getEntityType(), record.getEntityId(), record.getContent(),
                record.getEmbedding(), record.getMetadata())) {
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
        int removed = 0;
        for (String id : vectorIds) {
            if (removeVectorById(id)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        if (restDelegate != null) {
            return restDelegate.scan(request);
        }
        ensureEnabled();
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        String entityType = request.getEntityType();
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;
        int pageSize = limit + 1;
        Common.PointId offset = decodeScrollCursor(request.getCursor());

        Points.ScrollPoints.Builder builder = Points.ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setLimit(pageSize);

        if (offset != null) {
            builder.setOffset(offset);
        }

        Common.Filter filter = buildMetadataFilter(request.getMetadataEquals());
        if (filter != null) {
            builder.setFilter(filter);
        }

        builder.setWithVectors(WithVectorsSelectorFactory.enable(request.isIncludeEmbedding()));

        List<String> excludedPayloadFields = new ArrayList<>();
        excludedPayloadFields.add(EMBEDDING_PAYLOAD_FIELD);
        if (!request.isIncludeContent()) {
            excludedPayloadFields.add("content");
        }
        if (!request.isIncludeMetadata()) {
            // Keep identifiers even when metadata is suppressed.
            excludedPayloadFields.addAll(request.getMetadataEquals() != null
                ? request.getMetadataEquals().keySet().stream().filter(Objects::nonNull).collect(Collectors.toList())
                : List.of());
            excludedPayloadFields.add("raw");
        }

        if (excludedPayloadFields.size() == 1 && EMBEDDING_PAYLOAD_FIELD.equals(excludedPayloadFields.getFirst())) {
            builder.setWithPayload(WithPayloadSelectorFactory.enable(true));
        } else {
            builder.setWithPayload(WithPayloadSelectorFactory.exclude(excludedPayloadFields));
        }

        Points.ScrollResponse response = await(qdrantClient.scrollAsync(builder.build()), "scroll points (scan)");
        if (response == null || response.getResultCount() == 0) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        boolean hasMore = response.getResultCount() > limit || response.hasNextPageOffset();
        List<Points.RetrievedPoint> points = response.getResultList();
        if (points.size() > limit) {
            points = points.subList(0, limit);
        }

        List<VectorRecord> records = points.stream()
            .map(point -> toVectorRecord(collection, point, null))
            .map(record -> applyScanProjection(record, request))
            .toList();

        String nextCursor = response.hasNextPageOffset()
            ? encodeScrollCursor(response.getNextPageOffset())
            : null;

        return VectorScanPage.builder()
            .vectors(records)
            .hasMore(hasMore && nextCursor != null)
            .nextCursor(nextCursor)
            .build();
    }

    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        if (restDelegate != null) {
            return restDelegate.getVectorsByEntityType(entityType);
        }
        ensureEnabled();
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return Collections.emptyList();
        }

        List<VectorRecord> records = new ArrayList<>();
        Common.PointId offset = null;
        int pageSize = 256;

        while (true) {
            Points.ScrollPoints.Builder builder = Points.ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setLimit(pageSize)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setWithVectors(WithVectorsSelectorFactory.enable(true));

            if (offset != null) {
                builder.setOffset(offset);
            }

            Points.ScrollResponse response = await(qdrantClient.scrollAsync(builder.build()), "scroll points");
            if (response == null || response.getResultCount() == 0) {
                break;
            }

            response.getResultList().forEach(point -> records.add(toVectorRecord(collection, point, null)));
            if (!response.hasNextPageOffset()) {
                break;
            }
            offset = response.getNextPageOffset();
        }

        return records;
    }

    @Override
    public long getVectorCountByEntityType(String entityType) {
        if (restDelegate != null) {
            return restDelegate.getVectorCountByEntityType(entityType);
        }
        ensureEnabled();
        String collection = collectionName(entityType);
        try {
            Common.Filter filter = Common.Filter.newBuilder().build();
            Long count = await(qdrantClient.countAsync(collection, filter, true), "count points");
            return count != null ? count : 0L;
        } catch (AIServiceException ex) {
            if (isCollectionNotFoundFailure(ex)) {
                return 0L;
            }
            throw ex;
        }
    }

    @Override
    public boolean vectorExists(String entityType, String entityId) {
        return getVectorByEntity(entityType, entityId).isPresent();
    }

    @Override
    public Map<String, Object> getStatistics() {
        if (restDelegate != null) {
            return restDelegate.getStatistics();
        }
        ensureEnabled();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("type", "qdrant");
        stats.put("host", config.getHost());
        stats.put("grpcPort", config.getGrpcPort());
        stats.put("collectionPrefix", collectionPrefix);
        stats.put("collections", listCandidateCollections());
        return stats;
    }

    @Override
    public long clearVectors() {
        if (restDelegate != null) {
            return restDelegate.clearVectors();
        }
        ensureEnabled();
        long removed = 0;
        for (String collection : listCandidateCollections()) {
            removed += clearCollection(collection);
        }
        return removed;
    }

    @Override
    public long clearVectorsByEntityType(String entityType) {
        if (restDelegate != null) {
            return restDelegate.clearVectorsByEntityType(entityType);
        }
        ensureEnabled();
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return 0L;
        }
        return clearCollection(collection);
    }

    private long clearCollection(String collection) {
        long before = 0L;
        try {
            Common.Filter filter = Common.Filter.newBuilder().build();
            Long count = await(qdrantClient.countAsync(collection, filter, true), "count points");
            before = count != null ? count : 0L;
        } catch (Exception ignored) {
        }

        await(qdrantClient.deleteAsync(collection, Common.Filter.newBuilder().build()), "delete all points");
        awaitCollectionCleared(collection);
        return before;
    }

    /**
     * Qdrant deletions can be eventually consistent; poll until the collection is empty so tests/CI don't race deletes.
     */
    private void awaitCollectionCleared(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return;
        }

        if (vectorDatabaseConfig != null && vectorDatabaseConfig.getOperations() != null
            && Boolean.FALSE.equals(vectorDatabaseConfig.getOperations().getAwaitClearConsistency())) {
            return;
        }

        long timeoutMs = vectorDatabaseConfig != null && vectorDatabaseConfig.getOperations() != null
            ? Math.max(1L, vectorDatabaseConfig.getOperations().getAwaitClearTimeoutMs())
            : 20_000L;
        long deadlineMs = System.currentTimeMillis() + timeoutMs;
        long sleepMs = 200;
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                if (getVectorCountByEntityType(entityType) <= 0) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            sleepMs = Math.min(1500, sleepMs * 2);
        }
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new AIServiceException("Qdrant vector provider is disabled");
        }
    }

    private static QdrantGrpcClient buildGrpcClient(AIProviderConfig.QdrantConfig config) {
        String rawHost = Optional.ofNullable(config.getHost()).orElse("localhost").trim();
        boolean tls = false;
        String host = rawHost;

        if (rawHost.startsWith("https://")) {
            tls = true;
            host = rawHost.substring("https://".length());
        } else if (rawHost.startsWith("http://")) {
            host = rawHost.substring("http://".length());
        }

        int grpcPort = Optional.ofNullable(config.getGrpcPort()).orElse(6334);
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, grpcPort, tls);

        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.withApiKey(config.getApiKey());
        }

        int timeoutSeconds = Optional.ofNullable(config.getTimeout()).orElse(30);
        if (timeoutSeconds > 0) {
            builder.withTimeout(Duration.ofSeconds(timeoutSeconds));
        }

        return builder.build();
    }

    @PreDestroy
    @Override
    public void close() {
        if (restDelegate != null) {
            restDelegate.close();
            return;
        }
        if (qdrantClient != null) {
            qdrantClient.close();
        }
    }

    private void ensureCollection(String collection, Integer vectorSize) {
        if (collectionCache.containsKey(collection)) {
            ensureRequiredPayloadIndexes(collection);
            return;
        }

        synchronized (collectionCache) {
            if (collectionCache.containsKey(collection)) {
                ensureRequiredPayloadIndexes(collection);
                return;
            }

            if (collectionExists(collection)) {
                collectionCache.put(collection, Boolean.TRUE);
                ensureRequiredPayloadIndexes(collection);
                return;
            }

            if (vectorSize == null || vectorSize <= 0) {
                throw new AIServiceException("Cannot create Qdrant collection '" + collection + "' without vector size");
            }

            io.qdrant.client.grpc.Collections.VectorParams vectorParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                .setSize(vectorSize)
                .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                .build();

            await(qdrantClient.createCollectionAsync(collection, vectorParams), "create collection");
            collectionCache.put(collection, Boolean.TRUE);
            ensureRequiredPayloadIndexes(collection);
        }
    }

    private void ensureRequiredPayloadIndexes(String collection) {
        for (String fieldName : REQUIRED_KEYWORD_PAYLOAD_INDEX_FIELDS) {
            ensureKeywordPayloadIndex(collection, fieldName);
        }
    }

    private void ensureKeywordPayloadIndex(String collection, String fieldName) {
        String cacheKey = collection + "::" + fieldName;
        if (payloadIndexCache.containsKey(cacheKey)) {
            return;
        }

        synchronized (payloadIndexCache) {
            if (payloadIndexCache.containsKey(cacheKey)) {
                return;
            }

            if (!payloadSchemaExists(collection, fieldName)) {
                await(
                    qdrantClient.createPayloadIndexAsync(
                        collection,
                        fieldName,
                        io.qdrant.client.grpc.Collections.PayloadSchemaType.Keyword,
                        null,
                        true,
                        null,
                        null
                    ),
                    "create payload index '" + fieldName + "' for collection " + collection
                );
            }

            payloadIndexCache.put(cacheKey, Boolean.TRUE);
        }
    }

    private boolean payloadSchemaExists(String collection, String fieldName) {
        io.qdrant.client.grpc.Collections.CollectionInfo info =
            await(qdrantClient.getCollectionInfoAsync(collection), "get collection info for " + collection);
        return info != null && info.containsPayloadSchema(fieldName);
    }

    private boolean collectionExists(String collection) {
        if (collectionCache.containsKey(collection)) {
            return true;
        }
        List<String> collections = await(qdrantClient.listCollectionsAsync(), "list collections");
        if (collections != null && collections.contains(collection)) {
            collectionCache.put(collection, Boolean.TRUE);
            return true;
        }
        return false;
    }

    private String buildVectorId(String entityType, String entityId) {
        String key = entityType + "::" + entityId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static QdrantClient createClientIfGrpcPreferred(AIProviderConfig providerConfig) {
        AIProviderConfig.QdrantConfig qdrantConfig =
            Objects.requireNonNull(providerConfig.getQdrant(), "Qdrant configuration must be present");
        if (!Boolean.TRUE.equals(qdrantConfig.getPreferGrpc())) {
            return null;
        }
        return new QdrantClient(buildGrpcClient(qdrantConfig));
    }

    private UUID parseVectorUuid(String vectorId) {
        try {
            return UUID.fromString(vectorId);
        } catch (IllegalArgumentException ex) {
            throw new AIServiceException("Invalid Qdrant vector ID (expected UUID): " + vectorId, ex);
        }
    }

    private List<Float> toFloatList(List<Double> values) {
        List<Float> floats = new ArrayList<>(values.size());
        for (Double value : values) {
            floats.add(value.floatValue());
        }
        return floats;
    }

    private JsonWithInt.Value toValue(Object obj) {
        if (obj == null) {
            return ValueFactory.nullValue();
        }
        if (obj instanceof String value) {
            return ValueFactory.value(value);
        }
        if (obj instanceof Boolean value) {
            return ValueFactory.value(value);
        }
        if (obj instanceof Integer || obj instanceof Long) {
            return ValueFactory.value(((Number) obj).longValue());
        }
        if (obj instanceof Float || obj instanceof Double) {
            return ValueFactory.value(((Number) obj).doubleValue());
        }
        if (obj instanceof Number number) {
            double asDouble = number.doubleValue();
            long asLong = number.longValue();
            if (Math.abs(asDouble - asLong) < 1e-9) {
                return ValueFactory.value(asLong);
            }
            return ValueFactory.value(asDouble);
        }
        if (obj instanceof List<?> list) {
            List<JsonWithInt.Value> values = list.stream()
                .map(this::toValue)
                .toList();
            return ValueFactory.list(values);
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, JsonWithInt.Value> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    converted.put(String.valueOf(key), toValue(value));
                }
            });
            return ValueFactory.value(converted);
        }
        return ValueFactory.value(obj.toString());
    }

    private Object fromValue(JsonWithInt.Value value) {
        if (value == null) {
            return null;
        }
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case STRUCT_VALUE -> {
                Map<String, Object> map = new LinkedHashMap<>();
                value.getStructValue().getFieldsMap().forEach((k, v) -> map.put(k, fromValue(v)));
                yield map;
            }
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                .map(this::fromValue)
                .toList();
            case KIND_NOT_SET -> null;
        };
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey("raw")) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("raw");
        return copy;
    }

    private VectorRecord toVectorRecord(String entityType, Points.RetrievedPoint point, Double scoreOverride) {
        String vectorId = point.hasId() && point.getId().hasUuid() ? point.getId().getUuid() : null;
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
        String resolvedEntityType = Optional.ofNullable(payload.get("entityType"))
            .filter(JsonWithInt.Value::hasStringValue)
            .map(JsonWithInt.Value::getStringValue)
            .orElse(entityType);

        String entityId = Optional.ofNullable(payload.get("entityId"))
            .filter(JsonWithInt.Value::hasStringValue)
            .map(JsonWithInt.Value::getStringValue)
            .orElse(null);

        String content = Optional.ofNullable(payload.get("content"))
            .filter(JsonWithInt.Value::hasStringValue)
            .map(JsonWithInt.Value::getStringValue)
            .orElse(null);

        List<Double> embedding = extractEmbedding(payload, point);

        Map<String, Object> metadata = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (!RESERVED_PAYLOAD_FIELDS.contains(key)) {
                metadata.put(key, fromValue(value));
            }
        });

        if (!metadata.containsKey("raw") && !metadata.isEmpty()) {
            metadata.put("raw", MetadataJsonSerializer.serialize(metadata));
        }

        return VectorRecord.builder()
            .vectorId(vectorId)
            .entityType(resolvedEntityType)
            .entityId(entityId)
            .content(content)
            .embedding(embedding)
            .metadata(metadata)
            .createdAt(readTimestamp(metadata, "_indexedCreatedAt"))
            .updatedAt(readTimestamp(metadata, "_indexedUpdatedAt"))
            .similarityScore(scoreOverride)
            .build();
    }

    private List<Double> extractEmbedding(Map<String, JsonWithInt.Value> payload, Points.RetrievedPoint point) {
        if (payload != null) {
            JsonWithInt.Value embeddingValue = payload.get(EMBEDDING_PAYLOAD_FIELD);
            List<Double> embeddingFromPayload = parseEmbeddingValue(embeddingValue);
            if (!CollectionUtils.isEmpty(embeddingFromPayload)) {
                return embeddingFromPayload;
            }
        }

        if (point.hasVectors() && point.getVectors().hasVector()) {
            List<Double> embedding = new ArrayList<>(point.getVectors().getVector().getDataCount());
            for (Float entry : point.getVectors().getVector().getDataList()) {
                embedding.add(entry.doubleValue());
            }
            return embedding;
        }

        return Collections.emptyList();
    }

    private VectorRecord applyScanProjection(VectorRecord record, VectorScanRequest request) {
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
            .embedding(request.isIncludeEmbedding() ? record.getEmbedding() : List.of())
            .metadata(request.isIncludeMetadata() ? record.getMetadata() : Map.of())
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .similarityScore(record.getSimilarityScore())
            .build();
    }

    private static String encodeScrollCursor(Common.PointId offset) {
        if (offset == null) {
            return null;
        }
        String raw;
        if (offset.hasUuid()) {
            raw = "offsetUuid:" + offset.getUuid();
        } else if (offset.hasNum()) {
            raw = "offsetNum:" + offset.getNum();
        } else {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Common.PointId decodeScrollCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (raw.startsWith("offsetUuid:")) {
                UUID uuid = UUID.fromString(raw.substring("offsetUuid:".length()));
                return PointIdFactory.id(uuid);
            }
            if (raw.startsWith("offsetNum:")) {
                long num = Long.parseLong(raw.substring("offsetNum:".length()));
                return PointIdFactory.id(num);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static LocalDateTime readTimestamp(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Double> parseEmbeddingValue(JsonWithInt.Value value) {
        if (value == null || value.getKindCase() != JsonWithInt.Value.KindCase.LIST_VALUE) {
            return null;
        }

        List<Double> embedding = new ArrayList<>(value.getListValue().getValuesCount());
        for (JsonWithInt.Value entry : value.getListValue().getValuesList()) {
            if (entry == null) {
                continue;
            }
            switch (entry.getKindCase()) {
                case DOUBLE_VALUE -> embedding.add(entry.getDoubleValue());
                case INTEGER_VALUE -> embedding.add((double) entry.getIntegerValue());
                case STRING_VALUE -> {
                    try {
                        embedding.add(Double.parseDouble(entry.getStringValue()));
                    } catch (NumberFormatException ignored) {
                        // Ignore unparsable payload values.
                    }
                }
                default -> {
                    // Ignore unsupported payload value types.
                }
            }
        }

        return embedding;
    }

    private VectorRecord toVectorRecord(String entityType, Points.ScoredPoint point, double score) {
        Points.RetrievedPoint converted = Points.RetrievedPoint.newBuilder()
            .setId(point.getId())
            .putAllPayload(point.getPayloadMap())
            .setVectors(point.getVectors())
            .build();
        return toVectorRecord(entityType, converted, score);
    }

    private Common.Filter buildMetadataFilter(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        List<Common.Condition> conditions = new ArrayList<>();
        metadata.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof Boolean bool) {
                conditions.add(io.qdrant.client.ConditionFactory.match(key, bool));
                return;
            }
            if (value instanceof Number number) {
                conditions.add(io.qdrant.client.ConditionFactory.match(key, number.longValue()));
                return;
            }
            conditions.add(io.qdrant.client.ConditionFactory.matchKeyword(key, String.valueOf(value)));
        });

        if (conditions.isEmpty()) {
            return null;
        }

        return Common.Filter.newBuilder().addAllMust(conditions).build();
    }

    private List<String> resolveSearchCollections(String entityType) {
        if (entityType != null && !entityType.isBlank()) {
            String collection = collectionName(entityType);
            return collectionExists(collection) ? List.of(collection) : List.of();
        }
        return listCandidateCollections();
    }

    private List<String> listCandidateCollections() {
        List<String> cached = new ArrayList<>(collectionCache.keySet()).stream()
            .filter(this::isScopedCollection)
            .toList();
        if (!cached.isEmpty()) {
            return cached;
        }
        try {
            List<String> collections = await(qdrantClient.listCollectionsAsync(), "list collections");
            if (collections == null) {
                return List.of();
            }
            return collections.stream()
                .filter(this::isScopedCollection)
                .toList();
        } catch (Exception ex) {
            log.debug("Unable to list Qdrant collections; falling back to cache only", ex);
            return cached;
        }
    }

    private String collectionName(String entityType) {
        return scopedCollectionName(entityType, collectionPrefix);
    }

    static String scopedCollectionName(String entityType, String collectionPrefix) {
        String normalizedPrefix = normalizeCollectionPrefix(collectionPrefix);
        if (normalizedPrefix.isBlank()) {
            return entityType;
        }
        return normalizedPrefix + entityType;
    }

    private boolean isScopedCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return false;
        }
        return collectionPrefix.isBlank() || collection.startsWith(collectionPrefix);
    }

    private static String normalizeCollectionPrefix(String collectionPrefix) {
        return collectionPrefix == null ? "" : collectionPrefix.trim();
    }

    private boolean isMissingPayloadIndexFailure(AIServiceException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Index required but not found")
            || message.contains(KNOWLEDGE_SOURCE_HANDLE_REF_FIELD);
    }

    private boolean isCollectionNotFoundFailure(AIServiceException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("collection")
            && (normalized.contains("not found")
                || normalized.contains("does not exist")
                || normalized.contains("doesn't exist"));
    }

    private <T> T await(ListenableFuture<T> future, String action) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIServiceException("Qdrant operation interrupted during " + action, e);
        } catch (Exception e) {
            throw new AIServiceException("Qdrant operation failed during " + action + ": " + e.getMessage(), e);
        }
    }
}
