package ai.fabric.vector.weaviate;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.fabric.util.MetadataJsonSerializer;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.base.WeaviateError;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.Meta;
import io.weaviate.client.v1.misc.model.MultiTenancyConfig;
import io.weaviate.client.v1.schema.model.DataType;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.Tenant;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import io.weaviate.client.v1.filters.WhereFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * Vector database implementation backed by Weaviate using the official Java client.
 */
@Slf4j
public class WeaviateVectorDatabaseService implements VectorDatabaseService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private static final String MODEL_NAME = "weaviate";
    private static final String PROPERTY_ENTITY_TYPE = "entityType";
    private static final String PROPERTY_ENTITY_ID = "entityId";
    private static final String PROPERTY_CONTENT = "content";
    private static final String PROPERTY_RAW = "raw";
    private static final String PROPERTY_META_PREFIX = "meta_";

    private final AIProviderConfig.WeaviateConfig config;
    private final VectorDatabaseConfig vectorDatabaseConfig;
    private final String classPrefix;
    private final String tenantName;
    private final boolean nativeMultiTenancyEnabled;
    private final WeaviateClient client;
    private final Set<String> knownClasses = ConcurrentHashMap.newKeySet(); // cache of class names
    private final Set<String> missingClasses = ConcurrentHashMap.newKeySet(); // cache of schema misses
    private final Set<String> knownEntityTypes = ConcurrentHashMap.newKeySet(); // cache of original entity types
    private final ConcurrentMap<String, Set<String>> knownPropertiesByClass = new ConcurrentHashMap<>();
    private final Set<String> knownTenantBindings = ConcurrentHashMap.newKeySet();

    public WeaviateVectorDatabaseService(AIProviderConfig providerConfig) {
        this(providerConfig, null);
    }

    public WeaviateVectorDatabaseService(AIProviderConfig providerConfig, VectorDatabaseConfig vectorDatabaseConfig) {
        this(providerConfig, vectorDatabaseConfig, null);
    }

    WeaviateVectorDatabaseService(AIProviderConfig providerConfig,
                                  VectorDatabaseConfig vectorDatabaseConfig,
                                  WeaviateClient client) {
        this.config = Objects.requireNonNull(providerConfig.getWeaviate(), "Weaviate configuration must be present");
        this.vectorDatabaseConfig = vectorDatabaseConfig != null ? vectorDatabaseConfig : new VectorDatabaseConfig();
        this.classPrefix = normalizeClassPrefix(this.config.getClassPrefix());
        this.tenantName = normalizeTenantName(this.config.getTenantName());
        this.nativeMultiTenancyEnabled = Boolean.TRUE.equals(this.config.getNativeMultiTenancyEnabled());
        if (nativeMultiTenancyEnabled && !hasText(this.tenantName)) {
            throw new AIServiceException("Weaviate native multi-tenancy requires tenantName to be configured");
        }
        this.client = client != null ? client : buildClient(config);
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
        diagnostics.put("sharedStorage", nativeMultiTenancyEnabled);
        diagnostics.put("scopeType", nativeMultiTenancyEnabled ? "CLASS_AND_TENANT" : "CLASS_PREFIX");
        diagnostics.put("rootResourceLabel", "Host");
        diagnostics.put("rootResourceValue", config.getHost());
        diagnostics.put("scopePrefix", classPrefix);
        diagnostics.put("tenantHandle", tenantName);
        diagnostics.put("nativeMultiTenancyEnabled", nativeMultiTenancyEnabled);
        if (nativeMultiTenancyEnabled && (hasText(classPrefix) || hasText(tenantName))) {
            diagnostics.put("scopePattern", classPrefix + "<EntityType> @ tenant " + tenantName);
        }
        return diagnostics;
    }

    @Override
    public String storeVector(String entityType, String entityId, String content,
                              List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        if (CollectionUtils.isEmpty(embedding)) {
            throw new AIServiceException("Weaviate storeVector requires a non-empty embedding vector");
        }

        String className = toClassName(entityType);
        ensureClassExists(entityType, className);
        ensureMetadataProperties(className, metadata);
        knownEntityTypes.add(entityType);

        String vectorId = buildVectorId(entityType, entityId);
        upsertObject(className, vectorId, buildProperties(entityType, entityId, content, metadata), toFloatArray(embedding));
        return vectorId;
    }

    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content,
                                List<Double> embedding, Map<String, Object> metadata) {
        ensureEnabled();
        if (!hasText(vectorId) || !hasText(entityType) || !hasText(entityId)) {
            return false;
        }
        if (CollectionUtils.isEmpty(embedding)) {
            throw new AIServiceException("Weaviate updateVector requires a non-empty embedding vector");
        }

        try {
            String className = toClassName(entityType);
            ensureClassExists(entityType, className);
            ensureMetadataProperties(className, metadata);
            knownEntityTypes.add(entityType);
            upsertObject(className, vectorId, buildProperties(entityType, entityId, content, metadata), toFloatArray(embedding));
            return true;
        } catch (AIServiceException ex) {
            log.warn("Failed to update vector {}: {}", vectorId, ex.getMessage());
            return false;
        }
    }

    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        ensureEnabled();
        if (!hasText(vectorId)) {
            return Optional.empty();
        }

        // Weaviate >= 1.23 deprecates object paths without className; prefer class-qualified lookups.
        List<String> classCandidates = knownClasses.isEmpty()
            ? List.of()
            : knownClasses.stream().filter(this::hasText).sorted().toList();

        for (String className : classCandidates) {
            var getter = client.data()
                .objectsGetter()
                .withClassName(className)
                .withID(vectorId)
                .withVector();
            Result<List<WeaviateObject>> result = applyTenant(getter).run();

            if (result.hasErrors()) {
                if (isNotFound(result.getError())) {
                    continue;
                }
                throw new AIServiceException("Weaviate getVector failed: " + errorMessages(result.getError()));
            }

            if (!CollectionUtils.isEmpty(result.getResult())) {
                return toVectorRecord(result.getResult().getFirst(), null);
            }
        }

        // Fallback for older stores/unknown classes: may trigger a deprecation warning on Weaviate >= 1.23.
        if (nativeMultiTenancyEnabled) {
            return Optional.empty();
        }

        Result<List<WeaviateObject>> result = client.data()
            .objectsGetter()
            .withID(vectorId)
            .withVector()
            .run();

        if (result.hasErrors()) {
            if (isNotFound(result.getError())) {
                return Optional.empty();
            }
            throw new AIServiceException("Weaviate getVector failed: " + errorMessages(result.getError()));
        }

        if (CollectionUtils.isEmpty(result.getResult())) {
            return Optional.empty();
        }

        return toVectorRecord(result.getResult().getFirst(), null);
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        ensureEnabled();
        if (!hasText(entityType) || !hasText(entityId)) {
            return Optional.empty();
        }

        String className = toClassName(entityType);
        if (!classExists(className)) {
            return Optional.empty();
        }

        String vectorId = buildVectorId(entityType, entityId);
        var getter = client.data()
            .objectsGetter()
            .withClassName(className)
            .withID(vectorId)
            .withVector();
        Result<List<WeaviateObject>> result = applyTenant(getter).run();

        if (result.hasErrors()) {
            if (isNotFound(result.getError())) {
                return Optional.empty();
            }
            throw new AIServiceException("Weaviate getVectorByEntity failed: " + errorMessages(result.getError()));
        }

        if (CollectionUtils.isEmpty(result.getResult())) {
            return Optional.empty();
        }

        return toVectorRecord(result.getResult().getFirst(), null);
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        ensureEnabled();
        if (request == null) {
            throw new AIServiceException("Weaviate search requires a request");
        }
        if (CollectionUtils.isEmpty(queryVector)) {
            throw new AIServiceException("Weaviate search requires a non-empty query vector");
        }

        int limit = normalizeSearchLimit(request.getLimit());
        double threshold = normalizeSearchThreshold(request.getThreshold());

        List<String> entityTypes = request.getEntityType() != null
            ? List.of(request.getEntityType())
            : new ArrayList<>(knownEntityTypes);

        if (entityTypes.isEmpty()) {
            return AISearchResponse.builder()
                .query(request.getQuery())
                .results(List.of())
                .totalResults(0)
                .maxScore(0.0)
                .processingTimeMs(0L)
                .model(MODEL_NAME)
                .build();
        }

        long start = System.currentTimeMillis();
        Float[] queryFloatVector = toFloatArray(queryVector);

        Stream<String> entityTypeStream = entityTypes.size() > 1
            ? entityTypes.parallelStream()
            : entityTypes.stream();
        List<VectorRecord> allResults = entityTypeStream
            .filter(this::hasText)
            .distinct()
            .map(entityType -> Map.entry(entityType, toClassName(entityType)))
            .filter(entry -> classExists(entry.getValue()))
            .flatMap(entry -> searchClass(entry.getValue(), queryFloatVector, limit, threshold, request.getMetadata()).stream())
            .toList();

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
        double maxScore = mapped.stream()
            .map(row -> row.get("score"))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .mapToDouble(Number::doubleValue)
            .max()
            .orElse(0.0);

        return AISearchResponse.builder()
            .query(request.getQuery())
            .results(mapped)
            .totalResults(mapped.size())
            .maxScore(maxScore)
            .processingTimeMs(System.currentTimeMillis() - start)
            .requestId(UUID.randomUUID().toString())
            .model(MODEL_NAME)
            .build();
    }

    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
        return search(queryVector, AISearchRequest.builder()
            .entityType(entityType)
            .limit(limit)
            .threshold(threshold)
            .build());
    }

    @Override
    public boolean removeVector(String entityType, String entityId) {
        ensureEnabled();
        if (!hasText(entityType) || !hasText(entityId)) {
            return false;
        }

        String className = toClassName(entityType);
        if (!classExists(className)) {
            return false;
        }

        return deleteById(className, buildVectorId(entityType, entityId));
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        ensureEnabled();
        if (!hasText(vectorId)) {
            return false;
        }
        try {
            // Prefer class-qualified deletes to avoid Weaviate 1.23+ deprecation warnings.
            if (!knownClasses.isEmpty()) {
                for (String className : knownClasses.stream().filter(this::hasText).sorted().toList()) {
                    if (deleteById(className, vectorId)) {
                        return true;
                    }
                }
            }

            if (nativeMultiTenancyEnabled) {
                return false;
            }

            // Fallback: may trigger a deprecation warning on Weaviate >= 1.23.
            Result<Boolean> result = client.data()
                .deleter()
                .withID(vectorId)
                .run();

            if (result.hasErrors()) {
                if (isNotFound(result.getError())) {
                    return false;
                }
                log.warn("Weaviate delete failed for {}: {}", vectorId, errorMessages(result.getError()));
                return false;
            }

            return Boolean.TRUE.equals(result.getResult());
        } catch (AIServiceException ex) {
            log.warn("Failed to remove vector {}: {}", vectorId, ex.getMessage());
            return false;
        }
    }

    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        if (CollectionUtils.isEmpty(vectors)) {
            return Collections.emptyList();
        }
        List<VectorRecord> safeVectors = safeVectors(vectors);
        List<String> ids = new ArrayList<>(safeVectors.size());
        for (VectorRecord record : safeVectors) {
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
        for (VectorRecord record : safeVectors(vectors)) {
            if (!hasText(record.getEntityType()) || !hasText(record.getEntityId())) {
                continue;
            }
            String vectorId = buildVectorId(record.getEntityType(), record.getEntityId());
            if (updateVector(vectorId, record.getEntityType(), record.getEntityId(), record.getContent(),
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
        for (String id : safeVectorIds(vectorIds)) {
            if (removeVectorById(id)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        ensureEnabled();
        if (request == null || !hasText(request.getEntityType())) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        String entityType = request.getEntityType();
        String className = toClassName(entityType);
        if (!classExists(className)) {
            return VectorScanPage.builder()
                .vectors(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        int limit = Optional.ofNullable(request.getLimit()).orElse(200);
        if (limit <= 0) {
            limit = 200;
        }

        String after = decodeAfterCursor(request.getCursor()).orElse(null);
        Optional<WhereFilter> where = buildWhereFilter(request.getMetadataEquals());

        List<Field> fields = new ArrayList<>();
        fields.add(Field.builder().name(PROPERTY_ENTITY_TYPE).build());
        fields.add(Field.builder().name(PROPERTY_ENTITY_ID).build());
        if (request.isIncludeContent()) {
            fields.add(Field.builder().name(PROPERTY_CONTENT).build());
        }
        // Always fetch raw so createdAt/updatedAt can be derived from metadata when present.
        fields.add(Field.builder().name(PROPERTY_RAW).build());

        List<Field> additional = new ArrayList<>();
        additional.add(Field.builder().name("id").build());
        if (request.isIncludeEmbedding()) {
            additional.add(Field.builder().name("vector").build());
        }
        fields.add(Field.builder().name("_additional").fields(additional.toArray(Field[]::new)).build());

        var query = client.graphQL()
            .get()
            .withClassName(className)
            .withLimit(limit + 1)
            .withFields(fields.toArray(Field[]::new));

        if (where.isPresent()) {
            query = query.withWhere(where.get());
        }
        if (after != null) {
            query = query.withAfter(after);
        }

        query = applyTenant(query);

        Result<GraphQLResponse> result = query.run();
        if (result.hasErrors()) {
            throw new AIServiceException("Weaviate scan failed for class " + className + ": " + errorMessages(result.getError()));
        }

        List<VectorRecord> records = parseGraphQLScanRecords(className, result.getResult(), request);
        boolean hasMore = records.size() > limit;
        if (hasMore) {
            records = records.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            nextCursor = encodeAfterCursor(records.get(records.size() - 1).getVectorId()).orElse(null);
        }

        return VectorScanPage.builder()
            .vectors(records)
            .hasMore(hasMore)
            .nextCursor(nextCursor)
            .build();
    }

    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        ensureEnabled();
        if (!hasText(entityType)) {
            return List.of();
        }

        String className = toClassName(entityType);
        if (!classExists(className)) {
            return List.of();
        }

        var getter = client.data()
            .objectsGetter()
            .withClassName(className)
            .withVector()
            .withLimit(1000);
        Result<List<WeaviateObject>> result = applyTenant(getter).run();

        if (result.hasErrors()) {
            if (isNotFound(result.getError())) {
                return List.of();
            }
            throw new AIServiceException("Weaviate list vectors failed: " + errorMessages(result.getError()));
        }

        if (CollectionUtils.isEmpty(result.getResult())) {
            return List.of();
        }

        return result.getResult().stream()
            .flatMap(obj -> toVectorRecord(obj, null).stream())
            .toList();
    }

    @Override
    public long getVectorCountByEntityType(String entityType) {
        return getVectorsByEntityType(entityType).size();
    }

    @Override
    public boolean vectorExists(String entityType, String entityId) {
        return getVectorByEntity(entityType, entityId).isPresent();
    }

    @Override
    public Map<String, Object> getStatistics() {
        ensureEnabled();
        Result<Meta> metaResult = client.misc().metaGetter().run();
        if (metaResult.hasErrors() || metaResult.getResult() == null) {
            return Map.of(
                "knownEntityTypes", new ArrayList<>(knownEntityTypes),
                "meta", Collections.emptyMap()
            );
        }

        Map<String, Object> meta = OBJECT_MAPPER.convertValue(metaResult.getResult(), Map.class);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("knownEntityTypes", new ArrayList<>(knownEntityTypes));
        response.put("knownClasses", new ArrayList<>(knownClasses));
        response.put("classPrefix", classPrefix);
        response.put("tenantName", tenantName);
        response.put("nativeMultiTenancyEnabled", nativeMultiTenancyEnabled);
        response.put("meta", meta);
        return response;
    }

    @Override
    public long clearVectors() {
        ensureEnabled();
        long removed = 0;
        for (String entityType : Set.copyOf(knownEntityTypes)) {
            removed += clearVectorsByEntityType(entityType);
        }
        return removed;
    }

    @Override
    public long clearVectorsByEntityType(String entityType) {
        ensureEnabled();
        if (!hasText(entityType)) {
            return 0;
        }
        String className = toClassName(entityType);
        if (!classExists(className)) {
            return 0;
        }

        List<VectorRecord> records = getVectorsByEntityType(entityType);
        records.forEach(record -> deleteById(className, record.getVectorId()));
        awaitClassCleared(entityType);
        return records.size();
    }

    private void awaitClassCleared(String entityType) {
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
            throw new AIServiceException("Weaviate vector provider is disabled");
        }
    }

    private String buildVectorId(String entityType, String entityId) {
        return UUID.nameUUIDFromBytes((entityType + "::" + entityId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int normalizeSearchLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, 100);
    }

    private double normalizeSearchThreshold(Double threshold) {
        if (threshold == null || !Double.isFinite(threshold)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, threshold));
    }

    private List<VectorRecord> safeVectors(List<VectorRecord> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return Collections.emptyList();
        }
        return vectors.stream()
            .filter(Objects::nonNull)
            .toList();
    }

    private List<String> safeVectorIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return Collections.emptyList();
        }
        return vectorIds.stream()
            .filter(this::hasText)
            .toList();
    }

    private WeaviateClient buildClient(AIProviderConfig.WeaviateConfig config) {
        String scheme = hasText(config.getScheme()) ? config.getScheme().trim() : "https";
        String host = Objects.requireNonNull(config.getHost(), "Weaviate host must be configured");
        int port = config.getPort() != null ? config.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        int timeoutSeconds = Optional.ofNullable(config.getTimeout()).orElse(30);

        Map<String, String> headers = new HashMap<>();
        if (hasText(config.getApiKey())) {
            headers.put("Authorization", "Bearer " + config.getApiKey());
            headers.put("X-API-Key", config.getApiKey());
        }

        Config clientConfig = new Config(scheme, host + ":" + port, headers, timeoutSeconds);
        log.info("Weaviate client initialized: {}://{}:{}", scheme, host, port);
        return new WeaviateClient(clientConfig);
    }

    private void ensureClassExists(String entityType, String className) {
        if (knownClasses.contains(className)) {
            ensureTenantExists(className);
            return;
        }

        synchronized (knownClasses) {
            if (knownClasses.contains(className)) {
                return;
            }

            if (!classExists(className)) {
                createClass(entityType, className);
            }

            knownClasses.add(className);
            missingClasses.remove(className);
            primePropertyCache(className);
            ensureTenantExists(className);
        }
    }

    private boolean classExists(String className) {
        if (knownClasses.contains(className)) {
            return true;
        }
        if (missingClasses.contains(className)) {
            return false;
        }
        Result<WeaviateClass> result = client.schema().classGetter().withClassName(className).run();
        if (!result.hasErrors()) {
            boolean exists = result.getResult() != null;
            if (exists) {
                knownClasses.add(className);
                missingClasses.remove(className);
            } else {
                missingClasses.add(className);
            }
            return exists;
        }
        if (isNotFound(result.getError())) {
            missingClasses.add(className);
            return false;
        }
        log.error("Weaviate class existence check failed for host {} class {}: {}", config.getHost(), className, errorMessages(result.getError()));
        throw new AIServiceException("Weaviate class existence check failed: " + errorMessages(result.getError()));
    }

    private void createClass(String entityType, String className) {
        List<Property> properties = List.of(
            Property.builder()
                .name(PROPERTY_ENTITY_TYPE)
                .dataType(List.of(DataType.TEXT))
                .description("Original entity type")
                .build(),
            Property.builder()
                .name(PROPERTY_ENTITY_ID)
                .dataType(List.of(DataType.TEXT))
                .description("Entity identifier")
                .build(),
            Property.builder()
                .name(PROPERTY_CONTENT)
                .dataType(List.of(DataType.TEXT))
                .description("Entity content")
                .build(),
            Property.builder()
                .name(PROPERTY_RAW)
                .dataType(List.of(DataType.TEXT))
                .description("Metadata JSON")
                .build()
        );

        WeaviateClass.WeaviateClassBuilder builder = WeaviateClass.builder()
            .className(className)
            .description("AI-Fabric class for entityType: " + entityType)
            .vectorizer("none")
            .properties(properties);
        if (nativeMultiTenancyEnabled) {
            builder.multiTenancyConfig(MultiTenancyConfig.builder().enabled(true).build());
        }
        WeaviateClass weaviateClass = builder.build();

        Result<Boolean> result = client.schema()
            .classCreator()
            .withClass(weaviateClass)
            .run();

        if (result.hasErrors()) {
            log.error("Failed to create Weaviate class {} on host {}: {}", className, config.getHost(), errorMessages(result.getError()));
            throw new AIServiceException("Failed to create Weaviate class '" + className + "': " + errorMessages(result.getError()));
        }

        log.info("Created Weaviate class {} for entityType {}", className, entityType);
    }

    private void ensureTenantExists(String className) {
        if (!nativeMultiTenancyEnabled) {
            return;
        }
        String tenantBinding = className + "::" + tenantName;
        if (knownTenantBindings.contains(tenantBinding)) {
            return;
        }
        Result<Boolean> result = client.schema()
            .tenantsCreator()
            .withClassName(className)
            .withTenants(Tenant.builder().name(tenantName).build())
            .run();
        if (result.hasErrors() && !isTenantAlreadyPresent(result.getError())) {
            throw new AIServiceException(
                "Failed to create or verify Weaviate tenant '" + tenantName + "' for class '" + className + "': "
                    + errorMessages(result.getError())
            );
        }
        knownTenantBindings.add(tenantBinding);
    }

    private void ensureMetadataProperties(String className, Map<String, Object> metadata) {
        if (!hasText(className) || metadata == null || metadata.isEmpty()) {
            return;
        }

        primePropertyCache(className);
        Set<String> knownProperties = knownPropertiesByClass.getOrDefault(className, ConcurrentHashMap.newKeySet());

        metadata.forEach((key, value) -> {
            if (!hasText(key) || value == null) {
                return;
            }
            if (PROPERTY_RAW.equals(key) || PROPERTY_ENTITY_ID.equals(key) || PROPERTY_ENTITY_TYPE.equals(key) || PROPERTY_CONTENT.equals(key)) {
                return;
            }

            Optional<String> propertyName = toMetadataPropertyName(key);
            if (propertyName.isEmpty() || knownProperties.contains(propertyName.get())) {
                return;
            }

            Property property = Property.builder()
                .name(propertyName.get())
                .dataType(List.of(DataType.TEXT))
                .description("Metadata field '" + key + "'")
                .build();

            Result<Boolean> result = client.schema()
                .propertyCreator()
                .withClassName(className)
                .withProperty(property)
                .run();

            if (result.hasErrors()) {
                log.debug("Weaviate property creation failed for {}.{}: {}", className, propertyName, errorMessages(result.getError()));
                return;
            }

            knownProperties.add(propertyName.get());
            knownPropertiesByClass.put(className, knownProperties);
        });
    }

    private void primePropertyCache(String className) {
        if (!hasText(className) || knownPropertiesByClass.containsKey(className)) {
            return;
        }

        Result<WeaviateClass> result = client.schema().classGetter().withClassName(className).run();
        if (result.hasErrors() || result.getResult() == null || result.getResult().getProperties() == null) {
            knownPropertiesByClass.put(className, ConcurrentHashMap.newKeySet());
            return;
        }

        Set<String> props = ConcurrentHashMap.newKeySet();
        for (Property property : result.getResult().getProperties()) {
            if (property != null && hasText(property.getName())) {
                props.add(property.getName());
            }
        }
        knownPropertiesByClass.put(className, props);
    }

    private Optional<WhereFilter> buildWhereFilter(Map<String, Object> metadataEquals) {
        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return Optional.empty();
        }

        List<WhereFilter> operands = new ArrayList<>();
        metadataEquals.forEach((key, value) -> {
            if (!hasText(key) || value == null) {
                return;
            }
            toMetadataPropertyName(key)
                .map(property -> WhereFilter.builder()
                    .path(new String[]{property})
                    .operator("Equal")
                    .valueText(String.valueOf(value))
                    .build())
                .ifPresent(operands::add);
        });

        if (operands.isEmpty()) {
            return Optional.empty();
        }
        if (operands.size() == 1) {
            return Optional.of(operands.getFirst());
        }
        return Optional.of(WhereFilter.builder()
            .operator("And")
            .operands(operands.toArray(WhereFilter[]::new))
            .build());
    }

    private List<VectorRecord> parseGraphQLScanRecords(String className, GraphQLResponse response, VectorScanRequest request) {
        if (response == null || response.getData() == null) {
            return List.of();
        }

        if (!(response.getData() instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object getObject = dataMap.get("Get");
        if (!(getObject instanceof Map<?, ?> getMap)) {
            return List.of();
        }
        Object classResults = getMap.get(className);
        if (!(classResults instanceof List<?> results)) {
            return List.of();
        }

        List<VectorRecord> records = new ArrayList<>(results.size());
        for (Object entry : results) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }

            Object additionalObject = row.get("_additional");
            if (!(additionalObject instanceof Map<?, ?> additional)) {
                continue;
            }

            String vectorId = String.valueOf(additional.get("id"));
            List<Double> embedding = request.isIncludeEmbedding()
                ? toDoubleList(extractVector(additional.get("vector")))
                : List.of();

            String entityId = row.get(PROPERTY_ENTITY_ID) != null ? String.valueOf(row.get(PROPERTY_ENTITY_ID)) : null;
            String entityType = row.get(PROPERTY_ENTITY_TYPE) != null ? String.valueOf(row.get(PROPERTY_ENTITY_TYPE)) : null;
            String content = request.isIncludeContent() && row.get(PROPERTY_CONTENT) != null ? String.valueOf(row.get(PROPERTY_CONTENT)) : null;
            String raw = row.get(PROPERTY_RAW) != null ? String.valueOf(row.get(PROPERTY_RAW)) : "{}";

            Map<String, Object> parsedMetadata = parseRawMetadata(raw);
            LocalDateTime createdAt = readTimestamp(parsedMetadata, "_indexedCreatedAt").orElse(null);
            LocalDateTime updatedAt = readTimestamp(parsedMetadata, "_indexedUpdatedAt").orElse(null);

            records.add(VectorRecord.builder()
                .vectorId(vectorId)
                .entityType(entityType)
                .entityId(entityId)
                .content(content)
                .embedding(embedding)
                .metadata(request.isIncludeMetadata() ? parsedMetadata : Map.of())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build());
        }

        return records;
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

    private static Optional<String> encodeAfterCursor(String vectorId) {
        if (!hasTextStatic(vectorId)) {
            return Optional.empty();
        }
        String raw = "after:" + vectorId;
        return Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<String> decodeAfterCursor(String cursor) {
        if (!hasTextStatic(cursor)) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith("after:")) {
                return Optional.empty();
            }
            String after = raw.substring("after:".length());
            return hasTextStatic(after) ? Optional.of(after) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static boolean hasTextStatic(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private io.weaviate.client.v1.data.api.ObjectsGetter applyTenant(io.weaviate.client.v1.data.api.ObjectsGetter getter) {
        return nativeMultiTenancyEnabled ? getter.withTenant(tenantName) : getter;
    }

    private io.weaviate.client.v1.data.api.ObjectCreator applyTenant(io.weaviate.client.v1.data.api.ObjectCreator creator) {
        return nativeMultiTenancyEnabled ? creator.withTenant(tenantName) : creator;
    }

    private io.weaviate.client.v1.data.api.ObjectDeleter applyTenant(io.weaviate.client.v1.data.api.ObjectDeleter deleter) {
        return nativeMultiTenancyEnabled ? deleter.withTenant(tenantName) : deleter;
    }

    private io.weaviate.client.v1.graphql.query.Get applyTenant(io.weaviate.client.v1.graphql.query.Get query) {
        return nativeMultiTenancyEnabled ? query.withTenant(tenantName) : query;
    }

    private boolean isTenantAlreadyPresent(WeaviateError error) {
        String messages = errorMessages(error).toLowerCase();
        return messages.contains("already exists") || messages.contains("tenant exists");
    }

    private Optional<String> toMetadataPropertyName(String key) {
        if (!hasText(key)) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase();
        String base = normalized.replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        if (base.isEmpty()) {
            base = "key";
        }
        if (!Character.isLetter(base.charAt(0))) {
            base = "k_" + base;
        }
        String suffix = shortHash(normalized);
        String candidate = PROPERTY_META_PREFIX + base + "_" + suffix;
        if (candidate.length() > 64) {
            candidate = candidate.substring(0, 63);
        }
        return Optional.of(candidate);
    }

    private String toClassName(String entityType) {
        return scopedClassName(entityType, classPrefix);
    }

    static String scopedClassName(String entityType, String classPrefix) {
        String base = baseClassName(entityType);
        String prefix = normalizeClassPrefix(classPrefix);
        if (!hasTextStatic(prefix)) {
            return base;
        }
        String combined = prefix + "_" + base;
        return combined.length() > 230
            ? combined.substring(0, 221) + "_" + shortHash(combined)
            : combined;
    }

    private static String baseClassName(String entityType) {
        String normalized = entityType == null ? "" : entityType.trim();
        if (normalized.isEmpty()) {
            return "Entity_" + shortHash("");
        }

        StringBuilder base = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                base.append(upperNext ? Character.toUpperCase(current) : current);
                upperNext = false;
            } else {
                upperNext = true;
            }
        }

        if (base.isEmpty()) {
            base.append("Entity");
        }

        if (!Character.isLetter(base.charAt(0))) {
            base.insert(0, "Entity");
        }

        if (!Character.isUpperCase(base.charAt(0))) {
            base.setCharAt(0, Character.toUpperCase(base.charAt(0)));
        }

        return base + "_" + shortHash(normalized);
    }

    private static String normalizeClassPrefix(String classPrefix) {
        if (!hasTextStatic(classPrefix)) {
            return "";
        }
        return baseClassName(classPrefix);
    }

    private static String normalizeTenantName(String tenantName) {
        return tenantName == null ? "" : tenantName.trim();
    }

    private static String shortHash(String value) {
        String compact = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "");
        return compact.substring(0, 8);
    }

    private Float[] toFloatArray(List<Double> vector) {
        Float[] values = new Float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            values[i] = vector.get(i).floatValue();
        }
        return values;
    }

    private List<Double> toDoubleList(Float[] vector) {
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> values = new ArrayList<>(vector.length);
        for (Float value : vector) {
            values.add(value == null ? 0.0 : value.doubleValue());
        }
        return values;
    }

    private Map<String, Object> buildProperties(String entityType, String entityId, String content, Map<String, Object> metadata) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PROPERTY_ENTITY_TYPE, entityType);
        props.put(PROPERTY_ENTITY_ID, entityId);
        if (content != null) {
            props.put(PROPERTY_CONTENT, content);
        }

        Map<String, Object> safeMetadata = metadata == null ? Collections.emptyMap() : metadata;
        Map<String, Object> stripped = stripRaw(safeMetadata);
        props.put(PROPERTY_RAW, MetadataJsonSerializer.serialize(stripped));

        stripped.forEach((key, value) -> {
            if (!hasText(key) || value == null) {
                return;
            }
            if (PROPERTY_RAW.equals(key) || PROPERTY_ENTITY_ID.equals(key) || PROPERTY_ENTITY_TYPE.equals(key) || PROPERTY_CONTENT.equals(key)) {
                return;
            }
            toMetadataPropertyName(key)
                .ifPresent(propertyName -> props.put(propertyName, String.valueOf(value)));
        });
        return props;
    }

    private Map<String, Object> stripRaw(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(PROPERTY_RAW)) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove(PROPERTY_RAW);
        return copy;
    }

    private void upsertObject(String className, String vectorId, Map<String, Object> properties, Float[] vector) {
        applyTenant(client.data()
            .deleter()
            .withClassName(className)
            .withID(vectorId))
            .run();

        Result<WeaviateObject> result = applyTenant(client.data()
            .creator()
            .withClassName(className)
            .withID(vectorId)
            .withProperties(properties)
            .withVector(vector))
            .run();

        if (result.hasErrors()) {
            log.error(
                "Weaviate upsert failed for host {} class {} vector {}: {}",
                config.getHost(),
                className,
                vectorId,
                errorMessages(result.getError())
            );
            throw new AIServiceException("Failed to upsert Weaviate object " + vectorId + ": " + errorMessages(result.getError()));
        }
    }

    private boolean deleteById(String className, String vectorId) {
        Result<Boolean> result = applyTenant(client.data()
            .deleter()
            .withClassName(className)
            .withID(vectorId))
            .run();

        if (result.hasErrors()) {
            if (isNotFound(result.getError())) {
                return false;
            }
            log.warn("Weaviate delete failed for {}: {}", vectorId, errorMessages(result.getError()));
            return false;
        }
        return Boolean.TRUE.equals(result.getResult());
    }

    private List<VectorRecord> searchClass(String className,
                                           Float[] queryVector,
                                           int limit,
                                           double threshold,
                                           Map<String, Object> metadataEquals) {
        NearVectorArgument nearVector = NearVectorArgument.builder()
            .vector(queryVector)
            .build();
        Optional<WhereFilter> where = buildWhereFilter(metadataEquals);

        Field[] fields = new Field[]{
            Field.builder().name(PROPERTY_ENTITY_TYPE).build(),
            Field.builder().name(PROPERTY_ENTITY_ID).build(),
            Field.builder().name(PROPERTY_CONTENT).build(),
            Field.builder().name(PROPERTY_RAW).build(),
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build(),
                Field.builder().name("certainty").build(),
                Field.builder().name("distance").build()
            ).build()
        };

        var query = applyTenant(client.graphQL()
            .get()
            .withClassName(className)
            .withNearVector(nearVector)
            .withLimit(limit)
            .withFields(fields));
        if (where.isPresent()) {
            query = query.withWhere(where.get());
        }

        Result<GraphQLResponse> result = query.run();

        if (result.hasErrors()) {
            throw new AIServiceException("Weaviate search failed for class " + className + ": " + errorMessages(result.getError()));
        }

        return parseGraphQLRecords(className, result.getResult(), threshold);
    }

    private List<VectorRecord> parseGraphQLRecords(String className, GraphQLResponse response, double threshold) {
        if (response == null || response.getData() == null) {
            return List.of();
        }

        if (!(response.getData() instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object getObject = dataMap.get("Get");
        if (!(getObject instanceof Map<?, ?> getMap)) {
            return List.of();
        }
        Object classResults = getMap.get(className);
        if (!(classResults instanceof List<?> results)) {
            return List.of();
        }

        List<VectorRecord> records = new ArrayList<>(results.size());
        for (Object entry : results) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }

            Object additionalObject = row.get("_additional");
            if (!(additionalObject instanceof Map<?, ?> additional)) {
                continue;
            }

            String vectorId = String.valueOf(additional.get("id"));

            Optional<Double> certainty = toDouble(additional.get("certainty"));
            Optional<Double> distance = toDouble(additional.get("distance"));
            double score = certainty.orElseGet(() -> distance
                .map(value -> Math.max(0.0, 1.0 - value))
                .orElse(0.0));

            if (score < threshold) {
                continue;
            }

            String entityId = row.get(PROPERTY_ENTITY_ID) != null ? String.valueOf(row.get(PROPERTY_ENTITY_ID)) : null;
            String entityType = row.get(PROPERTY_ENTITY_TYPE) != null ? String.valueOf(row.get(PROPERTY_ENTITY_TYPE)) : null;
            String content = row.get(PROPERTY_CONTENT) != null ? String.valueOf(row.get(PROPERTY_CONTENT)) : null;
            String raw = row.get(PROPERTY_RAW) != null ? String.valueOf(row.get(PROPERTY_RAW)) : "{}";

            Map<String, Object> metadata = parseRawMetadata(raw);
            LocalDateTime createdAt = readTimestamp(metadata, "_indexedCreatedAt").orElse(null);
            LocalDateTime updatedAt = readTimestamp(metadata, "_indexedUpdatedAt").orElse(null);

            records.add(VectorRecord.builder()
                .vectorId(vectorId)
                .entityType(entityType)
                .entityId(entityId)
                .content(content)
                .metadata(metadata)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .similarityScore(score)
                .build());
        }

        return records;
    }

    private Float[] extractVector(Object vectorValue) {
        if (!(vectorValue instanceof List<?> list) || list.isEmpty()) {
            return new Float[0];
        }

        Float[] vector = new Float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof Number number) {
                vector[i] = number.floatValue();
            } else if (element instanceof String str) {
                try {
                    vector[i] = Float.parseFloat(str);
                } catch (NumberFormatException ignored) {
                    vector[i] = 0.0f;
                }
            } else {
                vector[i] = 0.0f;
            }
        }
        return vector;
    }

    private Optional<Double> toDouble(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (value instanceof String string && hasText(string)) {
            try {
                return Optional.of(Double.parseDouble(string));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> parseRawMetadata(String raw) {
        if (!hasText(raw) || "{}".equals(raw.trim())) {
            return Map.of(PROPERTY_RAW, "{}");
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(raw, METADATA_TYPE);
            Map<String, Object> metadata = parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
            metadata.put(PROPERTY_RAW, raw);
            return metadata;
        } catch (Exception ex) {
            return Map.of(PROPERTY_RAW, raw);
        }
    }

    private Optional<VectorRecord> toVectorRecord(WeaviateObject object, Double similarityScore) {
        if (object == null) {
            return Optional.empty();
        }

        Map<String, Object> props = object.getProperties() != null ? object.getProperties() : Collections.emptyMap();
        String entityType = props.get(PROPERTY_ENTITY_TYPE) != null ? String.valueOf(props.get(PROPERTY_ENTITY_TYPE)) : null;
        String entityId = props.get(PROPERTY_ENTITY_ID) != null ? String.valueOf(props.get(PROPERTY_ENTITY_ID)) : null;
        String content = props.get(PROPERTY_CONTENT) != null ? String.valueOf(props.get(PROPERTY_CONTENT)) : null;
        String raw = props.get(PROPERTY_RAW) != null ? String.valueOf(props.get(PROPERTY_RAW)) : "{}";

        Map<String, Object> metadata = parseRawMetadata(raw);
        LocalDateTime createdAt = readTimestamp(metadata, "_indexedCreatedAt").orElse(null);
        LocalDateTime updatedAt = readTimestamp(metadata, "_indexedUpdatedAt").orElse(null);

        return Optional.of(VectorRecord.builder()
            .vectorId(object.getId())
            .entityType(entityType)
            .entityId(entityId)
            .content(content)
            .embedding(toDoubleList(object.getVector()))
            .metadata(metadata)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .similarityScore(similarityScore)
            .build());
    }

    private boolean isNotFound(WeaviateError error) {
        if (error == null) {
            return false;
        }
        if (error.getStatusCode() == 404) {
            return true;
        }
        return errorMessages(error).toLowerCase(java.util.Locale.ROOT).contains("tenant not found");
    }

    private String errorMessages(WeaviateError error) {
        if (error == null || error.getMessages() == null || error.getMessages().isEmpty()) {
            return "unknown error";
        }
        return error.getMessages().toString();
    }

    private void requireText(String value, String field) {
        if (!hasText(value)) {
            throw new AIServiceException(field + " must not be blank");
        }
    }
}
