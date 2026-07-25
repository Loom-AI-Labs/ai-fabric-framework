package ai.fabric.vector.qdrant;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.util.MetadataJsonSerializer;
import ai.fabric.util.VectorMetadataFilterSupport;
import ai.fabric.util.VectorRecordLifecycleMetadata;
import ai.fabric.util.VectorRecordInputValidation;
import ai.fabric.util.VectorRecordProjection;
import ai.fabric.vector.VectorProviderMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Qdrant REST transport used for deployments where gRPC is not available or not preferred.
 */
@Slf4j
final class QdrantRestVectorDatabaseService implements VectorDatabaseService, AutoCloseable {

    private static final String EMBEDDING_PAYLOAD_FIELD = "embedding";
    private static final String KNOWLEDGE_SOURCE_HANDLE_REF_FIELD = "knowledgeSourceHandleRef";
    private static final Set<String> RESERVED_PAYLOAD_FIELDS = Set.of("entityType", "entityId", "content", EMBEDDING_PAYLOAD_FIELD);
    private static final List<String> REQUIRED_KEYWORD_PAYLOAD_INDEX_FIELDS = List.of(KNOWLEDGE_SOURCE_HANDLE_REF_FIELD);

    private final AIProviderConfig.QdrantConfig config;
    private final VectorDatabaseConfig vectorDatabaseConfig;
    private final String collectionPrefix;
    private final String baseUrl;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, Boolean> collectionCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> payloadIndexCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> payloadIndexCreateAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> payloadIndexCreateFailures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> payloadIndexRepairAttempts = new ConcurrentHashMap<>();
    private final Set<String> payloadIndexesSeenMissing = ConcurrentHashMap.newKeySet();

    QdrantRestVectorDatabaseService(AIProviderConfig providerConfig, VectorDatabaseConfig vectorDatabaseConfig) {
        this.config = Objects.requireNonNull(providerConfig.getQdrant(), "Qdrant configuration must be present");
        this.vectorDatabaseConfig = vectorDatabaseConfig != null ? vectorDatabaseConfig : new VectorDatabaseConfig();
        this.collectionPrefix = normalizeCollectionPrefix(this.config.getCollectionPrefix());
        this.baseUrl = normalizeBaseUrl(this.config);
        this.apiKey = Optional.ofNullable(this.config.getApiKey()).orElse("").trim();
        int timeoutSeconds = Optional.ofNullable(this.config.getTimeout()).orElse(30);
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.requestTimeout)
            .build();
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
    public boolean supportsSearchMetadataFiltering() {
        return true;
    }

    @Override
    public boolean supportsScanMetadataFiltering() {
        return true;
    }

    @Override
    public boolean supportsExactFetchById() {
        return true;
    }

    @Override
    public boolean supportsClearByEntityType() {
        return true;
    }

    @Override
    public boolean supportsEfficientEntityTypeCount() {
        return true;
    }

    @Override
    public String vectorProviderName() {
        return "qdrant";
    }

    @Override
    public String vectorNativeClient() {
        return "qdrant-rest-api";
    }

    @Override
    public String vectorSearchFilterMode() {
        return "qdrant-payload-filter-with-index-repair";
    }

    @Override
    public String vectorScanFilterMode() {
        return "qdrant-payload-filter-with-index-repair";
    }

    @Override
    public String vectorMetadataFilterSubset() {
        return "portable-scalar-exact-match";
    }

    @Override
    public String vectorEntityTypeCountMode() {
        return "qdrant-count-api";
    }

    @Override
    public String vectorEntityTypeClearMode() {
        return "qdrant-delete-collection";
    }

    @Override
    public String vectorConsistencyModel() {
        return "provider-durable-lazy-payload-index-readiness";
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        Map<String, Object> diagnostics = VectorDatabaseService.super.adminDiagnostics();
        diagnostics.put("provider", "qdrant");
        diagnostics.put("sharedStorage", !collectionPrefix.isBlank());
        diagnostics.put("scopeType", collectionPrefix.isBlank() ? "COLLECTION" : "COLLECTION_PREFIX");
        diagnostics.put("rootResourceLabel", "Endpoint");
        diagnostics.put("rootResourceValue", config.getHost());
        diagnostics.put("transportBaseUrl", baseUrl);
        diagnostics.put("scopePrefix", collectionPrefix);
        if (!collectionPrefix.isBlank()) {
            diagnostics.put("scopePattern", collectionPrefix + "<entity_type>");
        }
        diagnostics.put("preferGrpc", false);
        diagnostics.put("transport", "rest");
        diagnostics.put("metadataFilteredSearch", supportsSearchMetadataFiltering());
        diagnostics.put("metadataFilteredScan", supportsScanMetadataFiltering());
        diagnostics.put("searchFilterMode", "qdrant-payload-filter-with-index-repair");
        diagnostics.put("scanFilterMode", "qdrant-payload-filter-with-index-repair");
        diagnostics.put("failOnMissingPayloadIndex", failOnMissingPayloadIndex());
        diagnostics.put("requiredPayloadIndexFields", REQUIRED_KEYWORD_PAYLOAD_INDEX_FIELDS);
        diagnostics.put("payloadIndexReadinessSource", "lazy-cache");
        diagnostics.put("verifiedPayloadIndexes", sortedKeys(payloadIndexCache));
        diagnostics.put("payloadIndexesSeenMissing", sortedValues(payloadIndexesSeenMissing));
        diagnostics.put("payloadIndexCreateAttempts", sortedMap(payloadIndexCreateAttempts));
        diagnostics.put("payloadIndexCreateFailures", sortedMap(payloadIndexCreateFailures));
        diagnostics.put("payloadIndexRepairAttempts", sortedMap(payloadIndexRepairAttempts));
        diagnostics.put("metadataFilterFallbacks", Map.of());
        return diagnostics;
    }

    @Override
    public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        VectorRecordInputValidation.requireStoreInputs("Qdrant", entityType, entityId, embedding);

        String collection = collectionName(entityType);
        ensureCollection(collection, embedding.size());
        String vectorId = buildVectorId(entityType, entityId);

        ObjectNode point = objectMapper.createObjectNode();
        point.put("id", vectorId);
        point.set("vector", toArrayNode(embedding));
        point.set("payload", buildPayload(entityType, entityId, content, embedding, metadata));

        ObjectNode body = objectMapper.createObjectNode();
        body.set("points", objectMapper.createArrayNode().add(point));
        request("PUT", "/collections/" + pathSegment(collection) + "/points?wait=true", body, "upsert point");
        return vectorId;
    }

    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        if (!VectorRecordInputValidation.hasVectorId(vectorId)
            || !VectorRecordInputValidation.hasEntityIdentity(entityType, entityId)) {
            return false;
        }
        VectorRecordInputValidation.requireEmbedding("Qdrant", "updateVector", embedding);

        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return false;
        }
        String parsedVectorId = parseVectorUuid(vectorId).toString();
        Optional<VectorRecord> existing = retrievePoint(collection, parsedVectorId, "retrieve point before update");
        if (existing.isEmpty()) {
            return false;
        }
        ensureCollection(collection, embedding.size());

        ObjectNode point = objectMapper.createObjectNode();
        point.put("id", parsedVectorId);
        point.set("vector", toArrayNode(embedding));
        point.set("payload", buildPayload(entityType, entityId, content, embedding,
            VectorRecordLifecycleMetadata.enrichForUpdate(metadata, existing.get().getCreatedAt())));

        ObjectNode body = objectMapper.createObjectNode();
        body.set("points", objectMapper.createArrayNode().add(point));
        request("PUT", "/collections/" + pathSegment(collection) + "/points?wait=true", body, "update point");
        return true;
    }

    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        ensureEnabled();
        if (vectorId == null || vectorId.isBlank()) {
            return Optional.empty();
        }
        String id = parseVectorUuid(vectorId).toString();
        for (String collection : listCandidateCollections()) {
            try {
                Optional<VectorRecord> record = retrievePoint(collection, id, "retrieve point");
                if (record.isPresent()) {
                    return record;
                }
            } catch (AIServiceException ignored) {
                // Best-effort scan across scoped collections.
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        ensureEnabled();
        if (!VectorRecordInputValidation.hasEntityIdentity(entityType, entityId)) {
            return Optional.empty();
        }
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return Optional.empty();
        }
        return retrievePoint(collection, buildVectorId(entityType, entityId), "retrieve point by entity");
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        ensureEnabled();
        if (request == null) {
            throw new AIServiceException("Qdrant search requires a request");
        }
        VectorRecordInputValidation.requireEmbedding("Qdrant", "search", queryVector);

        String entityType = request.getEntityType();
        int limit = QdrantVectorDatabaseService.normalizeSearchLimit(request.getLimit());
        double threshold = QdrantVectorDatabaseService.normalizeScoreThreshold(request.getThreshold());

        if (hasRejectedMetadataFilter(request.getMetadata())) {
            return emptySearchResponse(request);
        }

        List<String> collections = resolveSearchCollections(entityType);
        if (collections.isEmpty()) {
            return emptySearchResponse(request);
        }

        List<VectorRecord> allResults = new ArrayList<>();
        for (String collection : collections) {
            ensureCollection(collection, queryVector.size());
            Optional<JsonNode> filter = buildMetadataFilter(request.getMetadata());

            ObjectNode body = objectMapper.createObjectNode();
            body.set("vector", toArrayNode(queryVector));
            body.put("limit", limit);
            body.set("with_payload", BooleanNode.TRUE);
            body.set("with_vector", BooleanNode.TRUE);
            filter.ifPresent(value -> body.set("filter", value));

            JsonNode result;
            try {
                result = request("POST", "/collections/" + pathSegment(collection) + "/points/search", body, "search points");
            } catch (AIServiceException ex) {
                if (filter.isEmpty() || !isMissingPayloadIndexFailure(ex)) {
                    throw ex;
                }
                if (failOnMissingPayloadIndex()) {
                    throw new AIServiceException(
                        "Qdrant REST metadata-filtered search requires a payload index for the requested metadata fields. "
                            + "Create the payload index or set ai.vector-db.operations.fail-on-missing-payload-index=false "
                            + "to allow AI Fabric to repair portable typed indexes and retry the filtered query.",
                        ex
                    );
                }
                payloadIndexRepairAttempts.merge(collection, 1, Integer::sum);
                repairMetadataPayloadIndexes(collection, request.getMetadata());
                VectorProviderMetrics.recordRetry("qdrant", "search", "payload_index_repair");
                log.warn(
                    "Qdrant REST filtered search for collection '{}' reported a missing payload index. "
                        + "The requested typed indexes were repaired and the same filtered query will be retried.",
                    collection
                );
                try {
                    result = request(
                        "POST",
                        "/collections/" + pathSegment(collection) + "/points/search",
                        body,
                        "retry metadata-filtered search points"
                    );
                } catch (AIServiceException retryFailure) {
                    throw new AIServiceException(
                        "Qdrant REST metadata-filtered search still requires a payload index after safe index repair. "
                            + "Verify the metadata field types and Qdrant payload schema.",
                        retryFailure
                    );
                }
            }

            JsonNode points = result.path("result");
            if (!points.isArray()) {
                continue;
            }
            for (JsonNode point : points) {
                double score = point.path("score").asDouble(0.0d);
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
            .map(this::toSearchRow)
            .toList();

        return AISearchResponse.builder()
            .query(request.getQuery())
            .results(mapped)
            .totalResults(mapped.size())
            .maxScore(mapped.isEmpty() ? 0.0 : asDouble(mapped.getFirst().get("score")))
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
        ensureEnabled();
        if (!VectorRecordInputValidation.hasEntityIdentity(entityType, entityId)) {
            return false;
        }
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return false;
        }
        String vectorId = buildVectorId(entityType, entityId);
        if (retrievePoint(collection, vectorId, "retrieve point before delete").isEmpty()) {
            return false;
        }
        deletePointIds(collection, List.of(vectorId));
        return true;
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        ensureEnabled();
        if (vectorId == null || vectorId.isBlank()) {
            return false;
        }
        String id = parseVectorUuid(vectorId).toString();
        boolean removed = false;
        for (String collection : listCandidateCollections()) {
            try {
                if (retrievePoint(collection, id, "retrieve point before delete").isEmpty()) {
                    continue;
                }
                deletePointIds(collection, List.of(id));
                removed = true;
            } catch (AIServiceException ex) {
                if (isCollectionNotFoundFailure(ex)) {
                    continue;
                }
                throw ex;
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
            if (record == null) {
                continue;
            }
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
            if (record == null || record.getVectorId() == null || record.getVectorId().isBlank()) {
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
        ensureEnabled();
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            return emptyScanPage();
        }

        if (hasRejectedMetadataFilter(request.getMetadataEquals())) {
            return emptyScanPage();
        }

        String collection = collectionName(request.getEntityType());
        if (!collectionExists(collection)) {
            return emptyScanPage();
        }

        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;
        int pageSize = limit;
        Optional<JsonNode> offset = decodeScrollCursor(request.getCursor());
        Optional<JsonNode> filter = buildMetadataFilter(request.getMetadataEquals());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("limit", pageSize);
        offset.ifPresent(value -> body.set("offset", value));
        filter.ifPresent(value -> body.set("filter", value));
        body.set("with_vector", BooleanNode.valueOf(request.isIncludeEmbedding()));
        body.set("with_payload", buildPayloadSelector(request));

        JsonNode result;
        try {
            result = request(
                "POST",
                "/collections/" + pathSegment(collection) + "/points/scroll",
                body,
                "scroll points (scan)"
            );
        } catch (AIServiceException ex) {
            if (filter.isEmpty() || !isMissingPayloadIndexFailure(ex)) {
                throw ex;
            }
            if (failOnMissingPayloadIndex()) {
                throw new AIServiceException(
                    "Qdrant REST metadata-filtered scan requires a payload index for the requested metadata fields. "
                        + "Create the payload index or set ai.vector-db.operations.fail-on-missing-payload-index=false "
                        + "to allow AI Fabric to repair portable typed indexes and retry the filtered scan.",
                    ex
                );
            }
            payloadIndexRepairAttempts.merge(collection, 1, Integer::sum);
            repairMetadataPayloadIndexes(collection, request.getMetadataEquals());
            VectorProviderMetrics.recordRetry("qdrant", "scan", "payload_index_repair");
            log.warn(
                "Qdrant REST filtered scan for collection '{}' reported a missing payload index. "
                    + "The requested typed indexes were repaired and the same filtered scan will be retried.",
                collection
            );
            try {
                result = request(
                    "POST",
                    "/collections/" + pathSegment(collection) + "/points/scroll",
                    body,
                    "retry metadata-filtered scan"
                );
            } catch (AIServiceException retryFailure) {
                throw new AIServiceException(
                    "Qdrant REST metadata-filtered scan still requires a payload index after safe index repair. "
                        + "Verify the metadata field types and Qdrant payload schema.",
                    retryFailure
                );
            }
        }
        JsonNode pointsNode = result.path("result").path("points");
        if (!pointsNode.isArray() || pointsNode.isEmpty()) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        List<JsonNode> fetchedPoints = new ArrayList<>();
        pointsNode.forEach(fetchedPoints::add);
        boolean overFetched = fetchedPoints.size() > limit;
        boolean hasMore = overFetched || !result.path("result").path("next_page_offset").isMissingNode()
            && !result.path("result").path("next_page_offset").isNull();
        JsonNode syntheticOffset = overFetched && limit > 0
            ? fetchedPoints.get(limit - 1).path("id")
            : null;
        List<JsonNode> pointNodes = fetchedPoints;
        if (overFetched) {
            pointNodes = fetchedPoints.subList(0, limit);
        }

        List<VectorRecord> records = pointNodes.stream()
            .map(point -> toVectorRecord(collection, point, null))
            .map(record -> applyScanProjection(record, request))
            .toList();

        JsonNode nextOffset = result.path("result").path("next_page_offset");
        String nextCursor = encodeScrollCursor(nextOffset).or(() -> encodeScrollCursor(syntheticOffset)).orElse(null);

        return VectorScanPage.builder()
            .vectors(records)
            .hasMore(hasMore && nextCursor != null)
            .nextCursor(nextCursor)
            .build();
    }

    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        ensureEnabled();
        if (!VectorRecordInputValidation.hasText(entityType)) {
            return Collections.emptyList();
        }
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return Collections.emptyList();
        }

        List<VectorRecord> records = new ArrayList<>();
        JsonNode offset = null;
        int pageSize = 256;
        while (true) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("limit", pageSize);
            body.set("with_payload", BooleanNode.TRUE);
            body.set("with_vector", BooleanNode.TRUE);
            if (offset != null) {
                body.set("offset", offset);
            }

            JsonNode result = request("POST", "/collections/" + pathSegment(collection) + "/points/scroll", body, "scroll points");
            JsonNode points = result.path("result").path("points");
            if (!points.isArray() || points.isEmpty()) {
                break;
            }
            points.forEach(point -> records.add(toVectorRecord(collection, point, null)));
            JsonNode nextOffset = result.path("result").path("next_page_offset");
            if (nextOffset.isMissingNode() || nextOffset.isNull()) {
                break;
            }
            offset = nextOffset;
        }
        return records;
    }

    @Override
    public long getVectorCountByEntityType(String entityType) {
        ensureEnabled();
        if (!VectorRecordInputValidation.hasText(entityType)) {
            return 0L;
        }
        String collection = collectionName(entityType);
        try {
            return countCollection(collection);
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
        ensureEnabled();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("type", "qdrant");
        stats.put("host", baseUrl);
        stats.put("collectionPrefix", collectionPrefix);
        stats.put("transport", "rest");
        stats.put("collections", listCandidateCollections());
        return stats;
    }

    @Override
    public long clearVectors() {
        ensureEnabled();
        long removed = 0L;
        for (String collection : listCandidateCollections()) {
            removed += clearCollection(collection);
        }
        return removed;
    }

    @Override
    public long clearVectorsByEntityType(String entityType) {
        ensureEnabled();
        if (!VectorRecordInputValidation.hasText(entityType)) {
            return 0L;
        }
        String collection = collectionName(entityType);
        if (!collectionExists(collection)) {
            return 0L;
        }
        return clearCollection(collection);
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close hook.
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new AIServiceException("Qdrant vector provider is disabled");
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

            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode vectors = objectMapper.createObjectNode();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            body.set("vectors", vectors);
            request("PUT", "/collections/" + pathSegment(collection), body, "create collection");
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
        ensurePayloadIndex(collection, fieldName, "keyword", false);
    }

    private void repairMetadataPayloadIndexes(String collection, Map<String, Object> metadata) {
        VectorMetadataFilterSupport.ValidationResult validation =
            VectorMetadataFilterSupport.validatePortableEquals(metadata);
        if (validation.hasRejectedFilters()) {
            return;
        }
        validation.terms().forEach(term -> {
            String schemaType = switch (term.kind()) {
                case STRING -> "keyword";
                case BOOLEAN -> "bool";
                case INTEGRAL_NUMBER -> "integer";
                case DECIMAL_NUMBER -> throw new IllegalArgumentException(
                    "Decimal metadata filters are outside the portable exact-match subset"
                );
            };
            ensurePayloadIndex(collection, term.key(), schemaType, true);
        });
    }

    private void ensurePayloadIndex(
        String collection,
        String fieldName,
        String schemaType,
        boolean forceSchemaRefresh
    ) {
        String cacheKey = collection + "::" + fieldName;
        if (!forceSchemaRefresh && payloadIndexCache.containsKey(cacheKey)) {
            return;
        }

        synchronized (payloadIndexCache) {
            if (!forceSchemaRefresh && payloadIndexCache.containsKey(cacheKey)) {
                return;
            }
            if (forceSchemaRefresh) {
                payloadIndexCache.remove(cacheKey);
            }
            if (!payloadSchemaExists(collection, fieldName)) {
                payloadIndexesSeenMissing.add(cacheKey);
                payloadIndexCreateAttempts.merge(cacheKey, 1, Integer::sum);
                ObjectNode body = objectMapper.createObjectNode();
                body.put("field_name", fieldName);
                body.put("field_schema", schemaType);
                try {
                    request("PUT", "/collections/" + pathSegment(collection) + "/index?wait=true", body,
                        "create payload index '" + fieldName + "' for collection " + collection);
                } catch (AIServiceException ex) {
                    if (!isAlreadyExistsFailure(ex) && !payloadSchemaExists(collection, fieldName)) {
                        payloadIndexCreateFailures.put(cacheKey, failureSummary(ex));
                        throw ex;
                    }
                }
            }
            payloadIndexCache.put(cacheKey, Boolean.TRUE);
            payloadIndexesSeenMissing.remove(cacheKey);
            payloadIndexCreateFailures.remove(cacheKey);
        }
    }

    private boolean payloadSchemaExists(String collection, String fieldName) {
        JsonNode result = request("GET", "/collections/" + pathSegment(collection), null, "get collection info for " + collection);
        return result.path("result").path("payload_schema").has(fieldName);
    }

    private static List<String> sortedKeys(Map<String, ?> values) {
        return values.keySet().stream().sorted().toList();
    }

    private static List<String> sortedValues(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static <T> Map<String, T> sortedMap(Map<String, T> values) {
        return values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private static String failureSummary(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getName();
        }
        return message;
    }

    private boolean collectionExists(String collection) {
        if (collectionCache.containsKey(collection)) {
            return true;
        }
        List<String> collections = listCollections();
        if (collections.contains(collection)) {
            collectionCache.put(collection, Boolean.TRUE);
            return true;
        }
        return false;
    }

    private boolean failOnMissingPayloadIndex() {
        return vectorDatabaseConfig != null
            && vectorDatabaseConfig.getOperations() != null
            && Boolean.TRUE.equals(vectorDatabaseConfig.getOperations().getFailOnMissingPayloadIndex());
    }

    private List<String> listCollections() {
        JsonNode result = request("GET", "/collections", null, "list collections");
        JsonNode collectionsNode = result.path("result").path("collections");
        if (!collectionsNode.isArray()) {
            return List.of();
        }
        List<String> collections = new ArrayList<>();
        collectionsNode.forEach(node -> {
            String name = node.path("name").asText("");
            if (!name.isBlank()) {
                collections.add(name);
            }
        });
        return collections;
    }

    private Optional<VectorRecord> retrievePoint(String collection, String vectorId, String action) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("ids", objectMapper.createArrayNode().add(vectorId));
        body.set("with_payload", BooleanNode.TRUE);
        body.set("with_vector", BooleanNode.TRUE);
        JsonNode result = request("POST", "/collections/" + pathSegment(collection) + "/points", body, action);
        JsonNode points = result.path("result");
        if (!points.isArray() || points.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toVectorRecord(collection, points.get(0), null));
    }

    private void deletePointIds(String collection, List<String> vectorIds) {
        if (CollectionUtils.isEmpty(vectorIds)) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode ids = objectMapper.createArrayNode();
        vectorIds.forEach(ids::add);
        body.set("points", ids);
        request("POST", "/collections/" + pathSegment(collection) + "/points/delete?wait=true", body, "delete point");
    }

    private long clearCollection(String collection) {
        long before = 0L;
        try {
            before = countCollection(collection);
        } catch (Exception ignored) {
            // Keep clearing best-effort if a count probe fails.
        }

        while (true) {
            List<String> ids = scrollPointIds(collection, 256);
            if (ids.isEmpty()) {
                break;
            }
            deletePointIds(collection, ids);
        }
        awaitCollectionCleared(collection);
        return before;
    }

    private List<String> scrollPointIds(String collection, int limit) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("limit", limit);
        body.set("with_payload", BooleanNode.FALSE);
        body.set("with_vector", BooleanNode.FALSE);
        JsonNode result = request("POST", "/collections/" + pathSegment(collection) + "/points/scroll", body, "scroll point ids");
        JsonNode points = result.path("result").path("points");
        if (!points.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        points.forEach(point -> {
            String id = point.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        });
        return ids;
    }

    private long countCollection(String collection) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("exact", true);
        JsonNode result = request("POST", "/collections/" + pathSegment(collection) + "/points/count", body, "count points");
        return result.path("result").path("count").asLong(0L);
    }

    private void awaitCollectionCleared(String collection) {
        if (collection == null || collection.isBlank()) {
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
        long sleepMs = 200L;
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                if (countCollection(collection) <= 0) {
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
            sleepMs = Math.min(1500L, sleepMs * 2L);
        }
    }

    private ObjectNode buildPayload(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("entityType", entityType);
        payload.put("entityId", entityId);
        if (content != null) {
            payload.put("content", content);
        }

        Map<String, Object> safeMetadata = VectorRecordLifecycleMetadata.enrichForStore(metadata);
        String rawMetadata = MetadataJsonSerializer.serialize(stripRaw(safeMetadata));
        payload.put("raw", rawMetadata);
        payload.set(EMBEDDING_PAYLOAD_FIELD, toArrayNode(embedding));

        safeMetadata.forEach((key, value) -> {
            if (key == null || value == null || "raw".equals(key) || RESERVED_PAYLOAD_FIELDS.contains(key)) {
                return;
            }
            payload.set(key, objectMapper.valueToTree(value));
        });
        return payload;
    }

    private Optional<JsonNode> buildMetadataFilter(Map<String, Object> metadata) {
        VectorMetadataFilterSupport.ValidationResult validation =
            VectorMetadataFilterSupport.validatePortableEquals(metadata);
        if (validation.isEmpty()) {
            return Optional.empty();
        }
        if (validation.hasRejectedFilters()) {
            return Optional.of(impossibleMetadataFilter());
        }

        ArrayNode must = objectMapper.createArrayNode();
        validation.terms().forEach(term -> {
            ObjectNode condition = objectMapper.createObjectNode();
            condition.put("key", term.key());
            ObjectNode match = objectMapper.createObjectNode();
            Object value = term.value();
            if (term.kind() == VectorMetadataFilterSupport.ValueKind.BOOLEAN) {
                Boolean bool = (Boolean) value;
                match.put("value", bool);
            } else if (term.kind() == VectorMetadataFilterSupport.ValueKind.INTEGRAL_NUMBER) {
                match.put("value", (Long) value);
            } else {
                match.put("value", String.valueOf(value));
            }
            condition.set("match", match);
            must.add(condition);
        });

        if (must.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode filter = objectMapper.createObjectNode();
        filter.set("must", must);
        return Optional.of(filter);
    }

    private JsonNode impossibleMetadataFilter() {
        ArrayNode must = objectMapper.createArrayNode();
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("key", VectorMetadataFilterSupport.IMPOSSIBLE_FILTER_FIELD);
        ObjectNode match = objectMapper.createObjectNode();
        match.put("value", VectorMetadataFilterSupport.IMPOSSIBLE_FILTER_VALUE);
        condition.set("match", match);
        must.add(condition);
        ObjectNode filter = objectMapper.createObjectNode();
        filter.set("must", must);
        return filter;
    }

    private JsonNode buildPayloadSelector(VectorScanRequest request) {
        List<String> excludedPayloadFields = new ArrayList<>();
        excludedPayloadFields.add(EMBEDDING_PAYLOAD_FIELD);
        if (!request.isIncludeContent()) {
            excludedPayloadFields.add("content");
        }
        if (!request.isIncludeMetadata()) {
            excludedPayloadFields.addAll(request.getMetadataEquals() != null
                ? request.getMetadataEquals().keySet().stream().filter(Objects::nonNull).collect(Collectors.toList())
                : List.of());
            excludedPayloadFields.add("raw");
        }

        if (excludedPayloadFields.size() == 1 && EMBEDDING_PAYLOAD_FIELD.equals(excludedPayloadFields.getFirst())) {
            return BooleanNode.TRUE;
        }
        ObjectNode selector = objectMapper.createObjectNode();
        ArrayNode excluded = objectMapper.createArrayNode();
        excludedPayloadFields.forEach(excluded::add);
        selector.set("exclude", excluded);
        return selector;
    }

    private VectorRecord toVectorRecord(String fallbackEntityType, JsonNode point, Double scoreOverride) {
        JsonNode payload = point.path("payload");
        String vectorId = point.path("id").asText(null);
        String entityType = payload.path("entityType").asText(fallbackEntityType);
        String entityId = payload.path("entityId").asText(null);
        String content = payload.path("content").asText(null);
        List<Double> embedding = extractEmbedding(payload, point);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (payload.isObject()) {
            payload.fields().forEachRemaining(entry -> {
                if (!RESERVED_PAYLOAD_FIELDS.contains(entry.getKey())) {
                    metadata.put(entry.getKey(), jsonToObjectOrNull(entry.getValue()));
                }
            });
        }
        if (!metadata.containsKey("raw") && !metadata.isEmpty()) {
            metadata.put("raw", MetadataJsonSerializer.serialize(metadata));
        }

        return VectorRecord.builder()
            .vectorId(vectorId)
            .entityType(entityType)
            .entityId(entityId)
            .content(content)
            .embedding(embedding)
            .metadata(metadata)
            .createdAt(VectorRecordLifecycleMetadata.readCreatedAt(metadata).orElse(null))
            .updatedAt(VectorRecordLifecycleMetadata.readUpdatedAt(metadata).orElse(null))
            .similarityScore(scoreOverride)
            .build();
    }

    private List<Double> extractEmbedding(JsonNode payload, JsonNode point) {
        List<Double> payloadEmbedding = readDoubleArray(payload.path(EMBEDDING_PAYLOAD_FIELD));
        if (!payloadEmbedding.isEmpty()) {
            return payloadEmbedding;
        }
        return readDoubleArray(point.path("vector"));
    }

    private List<Double> readDoubleArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<Double> values = new ArrayList<>(node.size());
        node.forEach(value -> {
            if (value.isNumber()) {
                values.add(value.asDouble());
            } else if (value.isTextual()) {
                try {
                    values.add(Double.parseDouble(value.asText()));
                } catch (NumberFormatException ignored) {
                    // Ignore unparsable vector values.
                }
            }
        });
        return values;
    }

    private VectorRecord applyScanProjection(VectorRecord record, VectorScanRequest request) {
        return VectorRecordProjection.projectForScan(record, request);
    }

    private Map<String, Object> toSearchRow(VectorRecord record) {
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
    }

    private AISearchResponse emptySearchResponse(AISearchRequest request) {
        return AISearchResponse.builder()
            .query(request.getQuery())
            .results(List.of())
            .totalResults(0)
            .maxScore(0.0)
            .model("qdrant")
            .build();
    }

    private VectorScanPage emptyScanPage() {
        return VectorScanPage.builder()
            .vectors(List.of())
            .hasMore(false)
            .nextCursor(null)
            .build();
    }

    private boolean hasRejectedMetadataFilter(Map<String, Object> metadata) {
        return VectorMetadataFilterSupport.validatePortableEquals(metadata).hasRejectedFilters();
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
            return listCollections().stream()
                .filter(this::isScopedCollection)
                .toList();
        } catch (Exception ex) {
            log.debug("Unable to list Qdrant REST collections; falling back to cache only", ex);
            return cached;
        }
    }

    private String collectionName(String entityType) {
        return QdrantVectorDatabaseService.scopedCollectionName(entityType, collectionPrefix);
    }

    private boolean isScopedCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return false;
        }
        return collectionPrefix.isBlank() || collection.startsWith(collectionPrefix);
    }

    private String buildVectorId(String entityType, String entityId) {
        String key = entityType + "::" + entityId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private UUID parseVectorUuid(String vectorId) {
        try {
            return UUID.fromString(vectorId);
        } catch (IllegalArgumentException ex) {
            throw new AIServiceException("Invalid Qdrant vector ID (expected UUID): " + vectorId, ex);
        }
    }

    private ArrayNode toArrayNode(List<Double> embedding) {
        ArrayNode node = objectMapper.createArrayNode();
        embedding.forEach(value -> node.add(value == null ? 0.0d : value));
        return node;
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey("raw")) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove("raw");
        return copy;
    }

    private Object jsonToObjectOrNull(JsonNode node) {
        return jsonToObject(node).orElse(null);
    }

    private Optional<Object> jsonToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(objectMapper.convertValue(node, Object.class));
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

    private JsonNode request(String method, String path, JsonNode body, String action) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
            if (!apiKey.isBlank()) {
                builder.header("api-key", apiKey);
            }

            String payload = body == null ? "" : objectMapper.writeValueAsString(body);
            switch (method.toUpperCase(Locale.ROOT)) {
                case "GET" -> builder.GET();
                case "POST" -> builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                case "PUT" -> builder.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                case "DELETE" -> builder.DELETE();
                default -> throw new AIServiceException("Unsupported Qdrant REST method: " + method);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new AIServiceException("Qdrant REST operation failed during " + action
                    + ": HTTP " + status + " " + response.body());
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (AIServiceException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AIServiceException("Qdrant REST operation interrupted during " + action, ex);
        } catch (Exception ex) {
            throw new AIServiceException("Qdrant REST operation failed during " + action + ": " + ex.getMessage(), ex);
        }
    }

    private String pathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Optional<JsonNode> decodeScrollCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Optional.of(objectMapper.readTree(raw));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<String> encodeScrollCursor(JsonNode offset) {
        if (offset == null || offset.isNull() || offset.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(offset.toString().getBytes(StandardCharsets.UTF_8)));
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
        return normalized.contains("http 404")
            || (normalized.contains("collection")
                && (normalized.contains("not found")
                    || normalized.contains("does not exist")
                    || normalized.contains("doesn't exist")));
    }

    private boolean isAlreadyExistsFailure(AIServiceException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exists");
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    private static String normalizeCollectionPrefix(String collectionPrefix) {
        return collectionPrefix == null ? "" : collectionPrefix.trim();
    }

    private static String normalizeBaseUrl(AIProviderConfig.QdrantConfig config) {
        String rawHost = Optional.ofNullable(config.getHost()).orElse("localhost").trim();
        while (rawHost.endsWith("/")) {
            rawHost = rawHost.substring(0, rawHost.length() - 1);
        }
        if (rawHost.startsWith("http://") || rawHost.startsWith("https://")) {
            return rawHost;
        }
        int port = Optional.ofNullable(config.getPort()).orElse(6333);
        if (rawHost.contains(":")) {
            return "http://" + rawHost;
        }
        return "http://" + rawHost + ":" + port;
    }
}
