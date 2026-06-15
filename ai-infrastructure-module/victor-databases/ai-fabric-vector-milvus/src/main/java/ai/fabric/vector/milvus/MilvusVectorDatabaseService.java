package ai.fabric.vector.milvus;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.util.MetadataJsonSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.exception.IllegalResponseException;
import io.milvus.exception.ParamException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.control.GetFlushStateParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.dml.SearchParam;
import io.milvus.grpc.DataType;
import io.milvus.response.FieldDataWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

/**
 * Milvus-backed implementation of {@link VectorDatabaseService}.
 */
@Slf4j
public class MilvusVectorDatabaseService implements VectorDatabaseService, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIELD_VECTOR_ID = "vector_id";
    private static final String FIELD_ENTITY_ID = "entity_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_VECTOR = "embedding";

    // Milvus SDK (2.4.x) uses mixed units: interval in milliseconds, timeout in seconds.
    private static final long COLLECTION_LOAD_INTERVAL_MILLIS = 500L; // max 2000ms per SDK constant
    private static final long COLLECTION_LOAD_TIMEOUT_SECONDS = 120L; // max 300s per SDK constant

    private final AIProviderConfig.MilvusConfig config;
    private final String collectionPrefix;
    private final MilvusServiceClient client;
    private final ConcurrentMap<String, Integer> collectionDimensions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> loadedCollections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> collectionLoadLocks = new ConcurrentHashMap<>();

    public MilvusVectorDatabaseService(AIProviderConfig providerConfig) {
        this.config = Objects.requireNonNull(providerConfig.getMilvus(), "Milvus configuration must be present");
        this.collectionPrefix = normalizeCollectionPrefix(this.config.getCollectionPrefix());
        if (!config.isEnabled()) {
            throw new AIServiceException("Milvus vector provider is disabled");
        }

        try {
            this.client = new MilvusServiceClient(buildConnectParam());
            log.info("Connected to Milvus at {}:{}", config.getHost(), config.getPort());
        } catch (Exception ex) {
            throw new AIServiceException("Failed to initialise Milvus client: " + ex.getMessage(), ex);
        }

        String requestedDatabase = Optional.ofNullable(config.getDatabaseName()).orElse("default");
        if (!"default".equalsIgnoreCase(requestedDatabase)) {
            log.info("Milvus SDK 2.4.x uses the active server database; requested '{}'", requestedDatabase);
        }
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
    public boolean supportsEfficientEntityTypeCount() {
        return false;
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        String databaseName = Optional.ofNullable(config.getDatabaseName()).orElse("default");
        diagnostics.put("sharedStorage", !collectionPrefix.isBlank());
        diagnostics.put("scopeType", collectionPrefix.isBlank() ? "COLLECTION" : "COLLECTION_PREFIX");
        diagnostics.put("rootResourceLabel", "Host");
        diagnostics.put("rootResourceValue", config.getHost());
        diagnostics.put("databaseName", databaseName);
        diagnostics.put("scopePrefix", collectionPrefix);
        if (!collectionPrefix.isBlank()) {
            diagnostics.put("scopePattern", databaseName + "/" + collectionPrefix + "<entity_type>");
        }
        diagnostics.put("secure", Boolean.TRUE.equals(config.getSecure()));
        return diagnostics;
    }

    private ConnectParam buildConnectParam() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
            .withHost(Optional.ofNullable(config.getHost()).orElse("localhost"))
            .withPort(Optional.ofNullable(config.getPort()).orElse(19530));

        if (Boolean.TRUE.equals(config.getSecure())) {
            builder.withSecure(true);
        }
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            builder.withAuthorization(config.getUsername(), Optional.ofNullable(config.getPassword()).orElse(""));
        }
        if (config.getTimeout() != null && config.getTimeout() > 0) {
            builder.withConnectTimeout(config.getTimeout().longValue(), TimeUnit.SECONDS);
        }
        return builder.build();
    }

    @Override
    public String storeVector(String entityType, String entityId, String content,
                              List<Double> embedding, Map<String, Object> metadata) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        if (embedding == null || embedding.isEmpty()) {
            throw new AIServiceException("Embedding vector must not be empty");
        }

        String entityTypeToken = normalizeEntityTypeToken(entityType);
        String collection = collectionName(entityTypeToken);
        ensureCollection(collection, embedding.size());

        String vectorId = buildVectorId(entityTypeToken, entityId);

        // Inserts do not require the collection to be loaded; keep writes fast and avoid blocking
        // transactional work on load operations. (Loads are done lazily on read/search paths.)
        List<UpsertParam.Field> fields = new ArrayList<>();
        fields.add(new UpsertParam.Field(FIELD_VECTOR_ID, Collections.singletonList(vectorId)));
        fields.add(new UpsertParam.Field(FIELD_ENTITY_ID, Collections.singletonList(entityId)));
        fields.add(new UpsertParam.Field(FIELD_CONTENT, Collections.singletonList(content != null ? content : "")));
        fields.add(new UpsertParam.Field(FIELD_METADATA, Collections.singletonList(MetadataJsonSerializer.serialize(stripRaw(metadata)))));
        fields.add(new UpsertParam.Field(FIELD_VECTOR, Collections.singletonList(toFloatList(embedding))));

        UpsertParam upsertParam = UpsertParam.newBuilder()
            .withCollectionName(collection)
            .withFields(fields)
            .build();

        R<MutationResult> response = client.upsert(upsertParam);
        verifySuccess(response, "upsert vector into Milvus");
        if (Boolean.TRUE.equals(config.getFlushOnWrite())) {
            flushCollection(collection);
        }
        return vectorId;
    }

    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId,
                                String content, List<Double> embedding, Map<String, Object> metadata) {
        storeVector(entityType, entityId, content, embedding, metadata);
        return true;
    }

    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        Objects.requireNonNull(vectorId, "vectorId must not be null");
        String entityTypeToken = extractEntityTypeToken(vectorId);
        String collection = collectionName(entityTypeToken);
        if (!collectionExists(collection)) {
            return Optional.empty();
        }
        ensureCollectionLoaded(collection);

        QueryParam queryParam = QueryParam.newBuilder()
            .withCollectionName(collection)
            .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
            .withIgnoreGrowing(false)
            .withExpr(String.format("%s == \"%s\"", FIELD_VECTOR_ID, vectorId))
            .addOutField(FIELD_VECTOR_ID)
            .addOutField(FIELD_ENTITY_ID)
            .addOutField(FIELD_CONTENT)
            .addOutField(FIELD_METADATA)
            .addOutField(FIELD_VECTOR)
            .build();

        R<QueryResults> response = client.query(queryParam);
        verifySuccess(response, "query vector by id");
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        if (wrapper.getRowCount() == 0) {
            return Optional.empty();
        }
        return Optional.of(toVectorRecord(collection, wrapper, 0));
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        String collection = collectionName(normalizeEntityTypeToken(entityType));
        if (!collectionExists(collection)) {
            return Optional.empty();
        }
        ensureCollectionLoaded(collection);

        QueryParam queryParam = QueryParam.newBuilder()
            .withCollectionName(collection)
            .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
            .withIgnoreGrowing(false)
            .withExpr(String.format("%s == \"%s\"", FIELD_ENTITY_ID, entityId))
            .addOutField(FIELD_VECTOR_ID)
            .addOutField(FIELD_ENTITY_ID)
            .addOutField(FIELD_CONTENT)
            .addOutField(FIELD_METADATA)
            .addOutField(FIELD_VECTOR)
            .build();

        R<QueryResults> response = client.query(queryParam);
        verifySuccess(response, "query vector by entity id");
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        if (wrapper.getRowCount() == 0) {
            return Optional.empty();
        }
        return Optional.of(toVectorRecord(collection, wrapper, 0));
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        if (request == null || request.getEntityType() == null) {
            throw new AIServiceException("Milvus search requires request.entityType to be specified");
        }
        if (queryVector == null || queryVector.isEmpty()) {
            throw new AIServiceException("Query vector must not be empty");
        }

        String requestedEntityType = request.getEntityType();
        String collection = collectionName(normalizeEntityTypeToken(requestedEntityType));
        ensureCollection(collection, queryVector.size());
        ensureCollectionLoaded(collection);

        int topK = Optional.ofNullable(request.getLimit()).orElse(10);
        double threshold = Optional.ofNullable(request.getThreshold()).orElse(0.0);
        String expr = buildScanExpr(request.getMetadata());

        SearchParam searchParam = SearchParam.newBuilder()
            .withCollectionName(collection)
            .withVectorFieldName(FIELD_VECTOR)
            .withTopK(topK)
            .withMetricType(MetricType.IP)
            .withParams("{\"nprobe\":16}")
            .withVectors(Collections.singletonList(toFloatList(queryVector)))
            .withExpr(expr)
            .withIgnoreGrowing(false)
            .addOutField(FIELD_ENTITY_ID)
            .addOutField(FIELD_CONTENT)
            .addOutField(FIELD_METADATA)
            .build();

        R<SearchResults> response = client.search(searchParam);
        verifySuccess(response, "execute search");
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);

        if (scores == null || scores.isEmpty()) {
            return AISearchResponse.builder()
                .results(Collections.emptyList())
                .totalResults(0)
                .maxScore(0.0)
                .query(request.getQuery())
                .model(collection)
                .build();
        }

        List<?> entityIds = null;
        List<?> contents = null;
        List<?> metadata = null;
        boolean useFieldData = true;
        try {
            entityIds = wrapper.getFieldData(FIELD_ENTITY_ID, 0);
            contents = wrapper.getFieldData(FIELD_CONTENT, 0);
            metadata = wrapper.getFieldData(FIELD_METADATA, 0);
        } catch (ParamException ex) {
            // Some Milvus responses omit output field data (especially when searching empty collections).
            // Fall back to fetching per-hit documents via query-by-id.
            useFieldData = false;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            SearchResultsWrapper.IDScore score = scores.get(i);
            double similarity = score.getScore();
            if (similarity < threshold) {
                continue;
            }

            String vectorId = score.getStrID();
            if (!useFieldData) {
                Optional<VectorRecord> record = getVector(vectorId);
                if (record.isEmpty()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vectorId", vectorId);
                row.put("id", record.get().getEntityId());
                row.put("entityId", record.get().getEntityId());
                row.put("entityType", requestedEntityType);
                row.put("vectorSpace", requestedEntityType);
                row.put("content", record.get().getContent());
                row.put("metadata", record.get().getMetadata() != null ? record.get().getMetadata() : Collections.emptyMap());
                row.put("score", similarity);
                row.put("similarity", similarity);
                results.add(row);
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vectorId", vectorId);
            String entityId = entityIds != null && i < entityIds.size() ? String.valueOf(entityIds.get(i)) : null;
            row.put("id", entityId);
            row.put("entityId", entityId);
            row.put("entityType", requestedEntityType);
            row.put("vectorSpace", requestedEntityType);
            row.put("content", contents != null && i < contents.size() ? Objects.toString(contents.get(i), null) : null);
            Object metadataRaw = metadata != null && i < metadata.size() ? metadata.get(i) : null;
            row.put("metadata", parseMetadata(metadataRaw));
            row.put("score", similarity);
            row.put("similarity", similarity);
            results.add(row);
        }

        return AISearchResponse.builder()
            .results(results)
            .totalResults(results.size())
            .maxScore(results.stream().map(row -> (Double) row.get("score")).max(Double::compareTo).orElse(0.0))
            .query(request.getQuery())
            .model(collection)
            .build();
    }

    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
        AISearchRequest request = AISearchRequest.builder()
            .entityType(entityType)
            .limit(limit)
            .threshold(threshold)
            .build();
        return search(queryVector, request);
    }

    @Override
    public boolean removeVector(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return false;
        }
        String collection = collectionName(normalizeEntityTypeToken(entityType));
        if (!collectionExists(collection)) {
            return false;
        }
        DeleteParam deleteParam = DeleteParam.newBuilder()
            .withCollectionName(collection)
            .withExpr(String.format("%s == \"%s\"", FIELD_ENTITY_ID, entityId))
            .build();
        try {
            R<MutationResult> response = client.delete(deleteParam);
            verifySuccess(response, "delete vector by entity id");
            return response.getData().getDeleteCnt() > 0;
        } catch (AIServiceException ex) {
            if (!isCollectionNotLoaded(ex)) {
                throw ex;
            }
            ensureCollectionLoaded(collection);
            R<MutationResult> retry = client.delete(deleteParam);
            verifySuccess(retry, "delete vector by entity id");
            return retry.getData().getDeleteCnt() > 0;
        }
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        if (vectorId == null) {
            return false;
        }
        String collection = collectionName(extractEntityTypeToken(vectorId));
        if (!collectionExists(collection)) {
            return false;
        }
        DeleteParam deleteParam = DeleteParam.newBuilder()
            .withCollectionName(collection)
            .withExpr(String.format("%s == \"%s\"", FIELD_VECTOR_ID, vectorId))
            .build();
        try {
            R<MutationResult> response = client.delete(deleteParam);
            verifySuccess(response, "delete vector by id");
            return response.getData().getDeleteCnt() > 0;
        } catch (AIServiceException ex) {
            if (!isCollectionNotLoaded(ex)) {
                throw ex;
            }
            ensureCollectionLoaded(collection);
            R<MutationResult> retry = client.delete(deleteParam);
            verifySuccess(retry, "delete vector by id");
            return retry.getData().getDeleteCnt() > 0;
        }
    }

    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        if (vectors == null || vectors.isEmpty()) {
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
        if (vectors == null || vectors.isEmpty()) {
            return 0;
        }
        batchStoreVectors(vectors);
        return vectors.size();
    }

    @Override
    public int batchRemoveVectors(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
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
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        if (entityType == null) {
            return Collections.emptyList();
        }
        String collection = collectionName(normalizeEntityTypeToken(entityType));
        if (!collectionExists(collection)) {
            return Collections.emptyList();
        }
        ensureCollectionLoaded(collection);

        QueryParam queryParam = QueryParam.newBuilder()
            .withCollectionName(collection)
            .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
            .withIgnoreGrowing(false)
            .withExpr(String.format("%s != \"\"", FIELD_VECTOR_ID))
            .addOutField(FIELD_VECTOR_ID)
            .addOutField(FIELD_ENTITY_ID)
            .addOutField(FIELD_CONTENT)
            .addOutField(FIELD_METADATA)
            .addOutField(FIELD_VECTOR)
            .build();

        R<QueryResults> response = client.query(queryParam);
        verifySuccess(response, "query all vectors by entity type");
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<VectorRecord> records = new ArrayList<>((int) wrapper.getRowCount());
        for (int i = 0; i < wrapper.getRowCount(); i++) {
            records.add(toVectorRecord(collection, wrapper, i));
        }
        return records;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        String collection = collectionName(normalizeEntityTypeToken(request.getEntityType()));
        if (!collectionExists(collection)) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }
        ensureCollectionLoaded(collection);

        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;
        long offset = decodeOffsetCursor(request.getCursor());
        long queryLimit = Math.min(Integer.MAX_VALUE, (long) limit + 1);

        String expr = buildScanExpr(request.getMetadataEquals());

        QueryParam.Builder queryBuilder = QueryParam.newBuilder()
            .withCollectionName(collection)
            .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
            .withIgnoreGrowing(false)
            .withExpr(expr)
            .withOffset(offset)
            .withLimit(queryLimit)
            .addOutField(FIELD_VECTOR_ID)
            .addOutField(FIELD_ENTITY_ID)
            .addOutField(FIELD_METADATA);

        if (request.isIncludeContent()) {
            queryBuilder.addOutField(FIELD_CONTENT);
        }
        if (request.isIncludeEmbedding()) {
            queryBuilder.addOutField(FIELD_VECTOR);
        }

        R<QueryResults> response = client.query(queryBuilder.build());
        verifySuccess(response, "scan vectors");

        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        long rowCount = wrapper.getRowCount();
        boolean hasMore = rowCount > limit;
        int returned = (int) Math.min(rowCount, limit);

        List<VectorRecord> records = new ArrayList<>(returned);
        for (int i = 0; i < returned; i++) {
            VectorRecord record = toVectorRecord(collection, wrapper, i);
            records.add(applyScanProjection(record, request));
        }

        String nextCursor = hasMore ? encodeOffsetCursor(offset + returned) : null;

        return VectorScanPage.builder()
            .vectors(records)
            .hasMore(hasMore)
            .nextCursor(nextCursor)
            .build();
    }

    @Override
    public long getVectorCountByEntityType(String entityType) {
        if (entityType == null) {
            return 0;
        }
        return getVectorsByEntityType(entityType).size();
    }

    @Override
    public boolean vectorExists(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return false;
        }

        String collection = collectionName(normalizeEntityTypeToken(entityType));
        if (!collectionExists(collection)) {
            return false;
        }

        // vectorExists() is used in tests and in higher-level services to validate indexing outcomes.
        // Ensure the collection is loaded so the subsequent query can reflect the latest write state.
        ensureCollectionLoaded(collection);
        return getVectorByEntity(entityType, entityId).isPresent();
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("configuredCollections", collectionDimensions.keySet());
        stats.put("dimensions", new HashMap<>(collectionDimensions));
        stats.put("collectionPrefix", collectionPrefix);
        stats.put("databaseName", config.getDatabaseName());
        return stats;
    }

    @Override
    public long clearVectors() {
        // Prefer dropping collections over scanning and deleting every vector. This avoids forcing
        // expensive/fragile loadCollection calls during test cleanup and keeps teardown fast.
        Set<String> collections = new HashSet<>();
        collections.addAll(collectionDimensions.keySet());
        collections.addAll(loadedCollections.keySet());
        collections.addAll(collectionLoadLocks.keySet());

        long removed = 0;
        for (String collection : collections) {
            if (!isScopedCollection(collection)) {
                continue;
            }
            removed += dropCollectionData(collection);
        }
        return removed;
    }

    @Override
    public long clearVectorsByEntityType(String entityType) {
        if (entityType == null) {
            return 0;
        }
        String collection = collectionName(normalizeEntityTypeToken(entityType));
        return dropCollectionData(collection);
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Error closing Milvus client: {}", ex.getMessage());
            }
        }
    }

    private void ensureCollection(String collection, int dimension) {
        Integer existing = collectionDimensions.get(collection);
        if (existing != null) {
            if (!existing.equals(dimension)) {
                throw new AIServiceException(String.format(
                    "Milvus collection '%s' was created with dimension %d but %d was provided",
                    collection, existing, dimension));
            }
            return;
        }
        synchronized (collectionDimensions) {
            existing = collectionDimensions.get(collection);
            if (existing != null) {
                if (!existing.equals(dimension)) {
                    throw new AIServiceException(String.format(
                        "Milvus collection '%s' was created with dimension %d but %d was provided",
                        collection, existing, dimension));
                }
                return;
            }
            createCollectionIfNeeded(collection, dimension);
            collectionDimensions.put(collection, dimension);
        }
    }

    private long dropCollectionData(String collection) {
        if (client == null || collection == null || collection.isBlank()) {
            return 0;
        }

        boolean exists;
        try {
            R<Boolean> hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
            verifySuccess(hasCollection, "check collection existence");
            exists = Boolean.TRUE.equals(hasCollection.getData());
        } catch (Exception ex) {
            // Cleanup should still attempt to drop the collection even when existence checks fail.
            log.debug("Milvus cleanup could not check collection existence for '{}': {}", collection, ex.getMessage());
            exists = true;
        }

        long removed = exists ? bestEffortRowCount(collection) : 0;

        // If Milvus believes the collection is loaded, release it first to reduce drop friction.
        if (exists && Boolean.TRUE.equals(loadedCollections.get(collection))) {
            try {
                R<?> released = client.releaseCollection(ReleaseCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .build());
                verifySuccess(released, "release collection " + collection);
            } catch (Exception ex) {
                log.debug("Milvus cleanup could not release collection '{}': {}", collection, ex.getMessage());
            }
        }

        if (exists) {
            long backoffMillis = 200L;
            Exception lastException = null;
            for (int attempt = 1; attempt <= 6; attempt++) {
                try {
                    R<?> dropped = client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(collection)
                        .build());
                    verifySuccess(dropped, "drop collection " + collection);
                    lastException = null;
                    break;
                } catch (Exception ex) {
                    lastException = ex;
                    String message = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
                    // Treat races (already dropped) as success.
                    if (message.contains("not found") || message.contains("does not exist")) {
                        lastException = null;
                        break;
                    }
                    // Milvus can transiently refuse drop while collection is still loading/unloading.
                    if (isCollectionNotLoadedMessage(message)) {
                        try {
                            Thread.sleep(backoffMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        backoffMillis = Math.min(backoffMillis * 2, 2000L);
                        continue;
                    }
                    break;
                }
            }
            if (lastException != null) {
                throw new AIServiceException("Milvus cleanup failed to drop collection " + collection + ": " + lastException.getMessage(), lastException);
            }
        }

        loadedCollections.remove(collection);
        collectionDimensions.remove(collection);
        collectionLoadLocks.remove(collection);

        return removed;
    }

    private long bestEffortRowCount(String collection) {
        try {
            R<io.milvus.grpc.GetCollectionStatisticsResponse> stats = client.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                    .withCollectionName(collection)
                    .build());
            verifySuccess(stats, "get collection statistics for " + collection);
            io.milvus.grpc.GetCollectionStatisticsResponse data = stats.getData();
            if (data == null) {
                return 0;
            }
            for (KeyValuePair kv : data.getStatsList()) {
                if ("row_count".equalsIgnoreCase(kv.getKey())) {
                    return Long.parseLong(kv.getValue());
                }
            }
        } catch (Exception ex) {
            log.debug("Milvus cleanup could not determine row count for '{}': {}", collection, ex.getMessage());
        }
        return 0;
    }

    private void createCollectionIfNeeded(String collection, int dimension) {
        R<Boolean> hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
            .withCollectionName(collection)
            .build());
        verifySuccess(hasCollection, "check collection existence");
        if (Boolean.TRUE.equals(hasCollection.getData())) {
            int existingDimension = resolveCollectionDimension(collection);
            collectionDimensions.put(collection, existingDimension);
            return;
        }

        FieldType vectorIdField = FieldType.newBuilder()
            .withName(FIELD_VECTOR_ID)
            .withDataType(DataType.VarChar)
            .withMaxLength(128)
            .withPrimaryKey(true)
            .withAutoID(false)
            .build();

        FieldType entityIdField = FieldType.newBuilder()
            .withName(FIELD_ENTITY_ID)
            .withDataType(DataType.VarChar)
            .withMaxLength(128)
            .build();

        FieldType contentField = FieldType.newBuilder()
            .withName(FIELD_CONTENT)
            .withDataType(DataType.VarChar)
            .withMaxLength(4096)
            .build();

        FieldType metadataField = FieldType.newBuilder()
            .withName(FIELD_METADATA)
            .withDataType(DataType.VarChar)
            .withMaxLength(4096)
            .build();

        FieldType vectorField = FieldType.newBuilder()
            .withName(FIELD_VECTOR)
            .withDataType(DataType.FloatVector)
            .withDimension(dimension)
            .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
            .withCollectionName(collection)
            .withDescription("AI Infrastructure vector collection")
            .withShardsNum(2)
            .addFieldType(vectorIdField)
            .addFieldType(entityIdField)
            .addFieldType(contentField)
            .addFieldType(metadataField)
            .addFieldType(vectorField)
            .build();

        verifySuccess(client.createCollection(createParam), "create collection " + collection);
        verifySuccess(client.createIndex(CreateIndexParam.newBuilder()
            .withCollectionName(collection)
            .withFieldName(FIELD_VECTOR)
            .withIndexName(collection + "_embedding_idx")
            .withMetricType(MetricType.IP)
            .withIndexType(IndexType.IVF_FLAT)
            .withExtraParam("{\"nlist\":128}")
            .build()), "create index for collection " + collection);
    }

    private int resolveCollectionDimension(String collection) {
        R<io.milvus.grpc.DescribeCollectionResponse> response = client.describeCollection(
            DescribeCollectionParam.newBuilder().withCollectionName(collection).build());
        verifySuccess(response, "describe collection " + collection);
        CollectionSchema schema = response.getData().getSchema();
        for (FieldSchema field : schema.getFieldsList()) {
            if (FIELD_VECTOR.equals(field.getName())) {
                for (KeyValuePair param : field.getTypeParamsList()) {
                    if ("dim".equals(param.getKey())) {
                        return Integer.parseInt(param.getValue());
                    }
                }
                throw new AIServiceException("Milvus embedding field is missing dimension metadata");
            }
        }
        throw new AIServiceException("Milvus collection is missing embedding field");
    }

    private void ensureCollectionLoaded(String collection) {
        if (collection == null || collection.isBlank()) {
            return;
        }
        if (Boolean.TRUE.equals(loadedCollections.get(collection))) {
            return;
        }

        Object lock = collectionLoadLocks.computeIfAbsent(collection, ignored -> new Object());
        synchronized (lock) {
            if (Boolean.TRUE.equals(loadedCollections.get(collection))) {
                return;
            }

            // Milvus' built-in sync-load path can still surface transient "collection not loaded" responses
            // very quickly. Prefer issuing an async load and polling load state ourselves up to a bounded timeout.
            try {
                R<?> response = client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withSyncLoad(false)
                    .build());
                verifySuccess(response, "load collection " + collection);
            } catch (Exception ex) {
                // If the collection is already loading/loaded or Milvus returns a transient "not loaded yet" response,
                // proceed to polling for load state. Fail fast only on obvious configuration/parameter issues.
                String message = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
                if (message.contains("invalid collection name")
                    || message.contains("does not exist")
                    || message.contains("not found")) {
                    throw new AIServiceException("Milvus operation failed for load collection " + collection + ": " + ex.getMessage(), ex);
                }
                log.debug("Milvus loadCollection request for '{}' returned: {}", collection, ex.getMessage());
            }

            io.milvus.param.collection.GetLoadStateParam stateParam =
                io.milvus.param.collection.GetLoadStateParam.newBuilder()
                    .withCollectionName(collection)
                    .build();

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COLLECTION_LOAD_TIMEOUT_SECONDS);
            String lastMessage = null;
            io.milvus.grpc.LoadState lastState = null;

            while (System.currentTimeMillis() < deadline) {
                R<io.milvus.grpc.GetLoadStateResponse> stateResponse = null;
                try {
                    stateResponse = client.getLoadState(stateParam);
                } catch (Exception ex) {
                    lastMessage = ex.getMessage();
                }

                if (stateResponse != null && stateResponse.getStatus() == R.Status.Success.getCode()) {
                    io.milvus.grpc.GetLoadStateResponse data = stateResponse.getData();
                    if (data != null) {
                        lastState = data.getState();
                        if (lastState == io.milvus.grpc.LoadState.LoadStateLoaded) {
                            loadedCollections.put(collection, Boolean.TRUE);
                            return;
                        }
                        if (lastState == io.milvus.grpc.LoadState.LoadStateNotExist) {
                            throw new AIServiceException("Milvus collection does not exist: " + collection);
                        }
                    }
                } else if (stateResponse != null) {
                    lastMessage = stateResponse.getMessage();
                    if (stateResponse.getStatus() != R.Status.Success.getCode()
                        && !isCollectionNotLoadedMessage(lastMessage)) {
                        throw new AIServiceException("Milvus operation failed for get load state " + collection + ": " + lastMessage);
                    }
                }

                try {
                    Thread.sleep(COLLECTION_LOAD_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AIServiceException("Interrupted while waiting for Milvus collection to load: " + collection);
                }
            }

            String detail = lastState != null ? lastState.name() : String.valueOf(lastMessage);
            throw new AIServiceException("Timed out waiting for Milvus collection to load: " + collection + " (" + detail + ")");
        }
    }

    private boolean isCollectionNotLoaded(Throwable ex) {
        if (ex == null) {
            return false;
        }
        return isCollectionNotLoadedMessage(ex.getMessage());
    }

    private boolean isCollectionNotLoadedMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("collection not loaded")
            || lower.contains("collection not fully loaded")
            || lower.contains("not fully loaded");
    }

    private boolean collectionExists(String collection) {
        if (collection == null || collection.isBlank()) {
            return false;
        }
        if (collectionDimensions.containsKey(collection)) {
            return true;
        }

        R<Boolean> hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
            .withCollectionName(collection)
            .build());
        verifySuccess(hasCollection, "check collection existence");
        if (Boolean.TRUE.equals(hasCollection.getData())) {
            int dimension = resolveCollectionDimension(collection);
            collectionDimensions.put(collection, dimension);
            return true;
        }
        return false;
    }

    private VectorRecord toVectorRecord(String collection, QueryResultsWrapper wrapper, int index) {
        try {
            FieldDataWrapper idWrapper = wrapper.getFieldWrapper(FIELD_VECTOR_ID);
            FieldDataWrapper entityWrapper = wrapper.getFieldWrapper(FIELD_ENTITY_ID);
            FieldDataWrapper vectorWrapper;
            try {
                vectorWrapper = wrapper.getFieldWrapper(FIELD_VECTOR);
            } catch (ParamException ex) {
                vectorWrapper = null;
            }

            FieldDataWrapper contentWrapper;
            FieldDataWrapper metadataWrapper;
            try {
                contentWrapper = wrapper.getFieldWrapper(FIELD_CONTENT);
            } catch (ParamException ex) {
                contentWrapper = null;
            }
            try {
                metadataWrapper = wrapper.getFieldWrapper(FIELD_METADATA);
            } catch (ParamException ex) {
                metadataWrapper = null;
            }

            Object vectorIdValue = idWrapper.valueByIdx(index);
            String vectorId = vectorIdValue != null ? vectorIdValue.toString() : null;
            String entityTypeToken = vectorId != null ? extractEntityTypeToken(vectorId) : collection;

            Object entityIdValue = entityWrapper.valueByIdx(index);
            String entityId = entityIdValue != null ? entityIdValue.toString() : null;

            String content = null;
            if (contentWrapper != null) {
                Object contentValue = contentWrapper.valueByIdx(index);
                if (contentValue != null) {
                    content = contentValue.toString();
                }
            }

            Object metadataRaw = metadataWrapper != null ? metadataWrapper.valueByIdx(index) : null;
            Map<String, Object> metadata = parseMetadata(metadataRaw);

            List<Double> embedding = Collections.emptyList();
            if (vectorWrapper != null) {
                Object vectorValue = vectorWrapper.valueByIdx(index);
                if (!(vectorValue instanceof List<?> floatsRaw)) {
                    throw new AIServiceException("Unexpected Milvus embedding payload type: " + vectorValue);
                }
                List<Double> parsed = new ArrayList<>(floatsRaw.size());
                for (Object entry : floatsRaw) {
                    parsed.add(((Number) entry).doubleValue());
                }
                embedding = parsed;
            }

            return VectorRecord.builder()
                .vectorId(vectorId)
                .entityType(entityTypeToken)
                .entityId(entityId)
                .content(content)
                .embedding(embedding)
                .metadata(metadata)
                .createdAt(readTimestamp(metadata, "_indexedCreatedAt"))
                .updatedAt(readTimestamp(metadata, "_indexedUpdatedAt"))
                .build();
        } catch (ParamException | IllegalResponseException ex) {
            throw new AIServiceException("Failed to parse Milvus query response", ex);
        }
    }

    private static String buildScanExpr(Map<String, Object> metadataEquals) {
        StringBuilder expr = new StringBuilder();
        expr.append(FIELD_VECTOR_ID).append(" != \"\"");

        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return expr.toString();
        }

        metadataEquals.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String needle = "\"" + key + "\":\"" + escapeLikeValue(value.toString()) + "\"";
            expr.append(" && ").append(FIELD_METADATA).append(" like \"%").append(needle).append("%\"");
        });

        return expr.toString();
    }

    private static String escapeLikeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\\\"");
    }

    private static String encodeOffsetCursor(long offset) {
        if (offset <= 0) {
            return null;
        }
        String raw = "offset:" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeOffsetCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith("offset:")) {
                return 0L;
            }
            return Long.parseLong(raw.substring("offset:".length()));
        } catch (Exception ex) {
            return 0L;
        }
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

    private void flushCollection(String collection) {
        R<io.milvus.grpc.FlushResponse> response = client.flush(FlushParam.newBuilder()
            .addCollectionName(collection)
            .build());
        verifySuccess(response, "flush collection " + collection);

        io.milvus.grpc.FlushResponse data = response.getData();
        if (data == null) {
            return;
        }
        long flushTs = data.getCollFlushTsOrDefault(collection, 0L);
        if (flushTs <= 0) {
            return;
        }

        GetFlushStateParam flushStateParam = GetFlushStateParam.newBuilder()
            .withCollectionName(collection)
            .withFlushTs(flushTs)
            .build();

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            R<io.milvus.grpc.GetFlushStateResponse> state = client.getFlushState(flushStateParam);
            verifySuccess(state, "get flush state for collection " + collection);
            io.milvus.grpc.GetFlushStateResponse stateData = state.getData();
            if (stateData != null && stateData.getFlushed()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<Float> toFloatList(List<Double> values) {
        List<Float> floats = new ArrayList<>(values.size());
        for (Double value : values) {
            floats.add(value.floatValue());
        }
        return floats;
    }

    private String metadataToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new AIServiceException("Failed to serialize metadata", ex);
        }
    }

    private Map<String, Object> parseMetadata(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        String json = value.toString();
        if (json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
            if (parsed == null || parsed.isEmpty()) {
                return Collections.emptyMap();
            }
            if (!parsed.containsKey("raw")) {
                parsed.put("raw", MetadataJsonSerializer.serialize(parsed));
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse metadata JSON: {}", json, ex);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey("raw")) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("raw");
        return copy;
    }

    private void verifySuccess(R<?> response, String action) {
        if (response == null || response.getStatus() != R.Status.Success.getCode()) {
            String message = response != null ? response.getMessage() : "unknown error";
            throw new AIServiceException("Milvus operation failed for " + action + ": " + message);
        }
    }

    private String buildVectorId(String entityTypeToken, String entityId) {
        return entityTypeToken + "::" + entityId;
    }

    private String collectionName(String entityTypeToken) {
        return toCollectionName(entityTypeToken, collectionPrefix);
    }

    static String normalizeEntityTypeToken(String entityType) {
        String token = entityType == null ? "" : entityType.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            throw new AIServiceException("Milvus entityType must not be blank");
        }
        return token;
    }

    static String toCollectionName(String entityTypeToken) {
        return toCollectionName(entityTypeToken, "");
    }

    static String toCollectionName(String entityTypeToken, String collectionPrefix) {
        String normalized = normalizeEntityTypeToken(entityTypeToken);
        String basic = normalized
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");

        if (basic.isEmpty()) {
            basic = "collection";
        }

        if (Character.isDigit(basic.charAt(0))) {
            basic = "c_" + basic;
        }

        // If we had to sanitize characters, add a short stable hash suffix to avoid collisions
        // (e.g. "test-product" vs "test_product").
        if (!basic.equals(normalized)) {
            basic = basic + "_h" + shortHash(normalized);
        }

        int maxLen = 255;
        if (basic.length() > maxLen) {
            basic = basic.substring(0, maxLen);
        }
        String prefix = normalizeCollectionPrefix(collectionPrefix);
        if (prefix.isBlank()) {
            return basic;
        }
        String combined = prefix + basic;
        if (combined.length() > maxLen) {
            combined = combined.substring(0, maxLen);
        }
        return combined;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(Objects.hashCode(value));
        }
    }

    private String extractEntityTypeToken(String vectorId) {
        int idx = vectorId.indexOf("::");
        if (idx <= 0) {
            throw new AIServiceException("Invalid Milvus vector identifier: " + vectorId);
        }
        return vectorId.substring(0, idx);
    }

    private boolean isScopedCollection(String collection) {
        return collection != null
            && !collection.isBlank()
            && (collectionPrefix.isBlank() || collection.startsWith(collectionPrefix));
    }

    private static String normalizeCollectionPrefix(String collectionPrefix) {
        return collectionPrefix == null ? "" : collectionPrefix.trim();
    }
}
