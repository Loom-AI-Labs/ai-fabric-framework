package ai.fabric.intent.retrieval.connector;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.http.HttpClient;
import ai.fabric.spi.RAGProvider;
import ai.fabric.util.UlidGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Documents-only external retrieval implementation of {@link RAGProvider}.
 *
 * <p>This provider calls the Customer Connector API endpoint {@code POST /retrieval/search} and returns
 * retrieved documents/chunks. It does not perform generation.</p>
 */
@Slf4j
public class RetrievalConnectorRAGProvider implements RAGProvider {

    private static final String NO_CONTEXT_MESSAGE = "No relevant context found.";
    private static final String CONTEXT_HEADER = "Relevant Context:\n\n";

    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_TIMEOUT = "TIMEOUT";
    private static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    private static final String ERROR_HTTP_ERROR = "HTTP_ERROR";
    private static final String ERROR_INVALID_RESPONSE = "INVALID_RESPONSE";

    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
        ERROR_TIMEOUT,
        ERROR_SERVICE_UNAVAILABLE,
        ERROR_RATE_LIMITED
    );
    private static final Set<String> FORBIDDEN_DOCUMENTS_ONLY_RESPONSE_KEYS = Set.of(
        "answer",
        "generatedanswer",
        "finalanswer",
        "response",
        "completion",
        "toolinstructions",
        "toolcalls",
        "tools",
        "prompt",
        "systemprompt",
        "hiddenprompt",
        "messages",
        "instructions"
    );

    private static final String DOC_ID = "id";
    private static final String DOC_CONTENT = "content";
    private static final String DOC_SCORE = "score";
    private static final String DOC_SOURCE = "source";
    private static final String DOC_URL = "url";
    private static final String DOC_VECTOR_SPACE = "vectorSpace";
    private static final String DOC_METADATA = "metadata";
    private static final String HEADER_HMAC_TIMESTAMP = "X-AIFABRIC-TIMESTAMP";
    private static final String HEADER_HMAC_NONCE = "X-AIFABRIC-NONCE";
    private static final String HEADER_HMAC_SIGNATURE = "X-AIFABRIC-SIGNATURE";
    private static final Pattern CONNECTOR_ERROR_CODE =
        Pattern.compile("[A-Z][A-Z0-9_]*");

    private final AIRetrievalConnectorProperties properties;
    private final AIHttpClientFactory httpClientFactory;
    private final ObjectMapper objectMapper;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;
    private final RetrievalResponsePolicy responsePolicy;
    private final RetrievalDocumentSanitizer mandatorySanitizer;
    private final List<RetrievalDocumentSanitizer> customSanitizers;

    private volatile HttpClient httpClient;
    private volatile boolean httpClientInitialized = false;

    public RetrievalConnectorRAGProvider(AIRetrievalConnectorProperties properties,
                                         AIHttpClientFactory httpClientFactory,
                                         ObjectProvider<ObjectMapper> objectMapperProvider,
                                         Clock clock) {
        this(
            properties,
            httpClientFactory,
            objectMapperProvider,
            clock,
            List.of()
        );
    }

    public RetrievalConnectorRAGProvider(
        AIRetrievalConnectorProperties properties,
        AIHttpClientFactory httpClientFactory,
        ObjectProvider<ObjectMapper> objectMapperProvider,
        Clock clock,
        List<RetrievalDocumentSanitizer> customSanitizers
    ) {
        if (properties == null) {
            throw new IllegalArgumentException("properties is required");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new IllegalStateException("ai.retrieval.connector.baseUrl is required when external retrieval connector is enabled.");
        }
        if (httpClientFactory == null) {
            throw new IllegalArgumentException("httpClientFactory is required");
        }

        this.properties = properties;
        this.httpClientFactory = httpClientFactory;
        this.objectMapper = objectMapperProvider != null
            ? objectMapperProvider.getIfAvailable(ObjectMapper::new)
            : new ObjectMapper();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.ulidGenerator = new UlidGenerator(this.clock);
        this.responsePolicy = RetrievalResponsePolicy.from(properties);
        this.mandatorySanitizer = new DefaultRetrievalDocumentSanitizer(
            responsePolicy,
            objectMapper
        );
        this.customSanitizers = customSanitizers == null
            ? List.of()
            : customSanitizers.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public RAGResponse performRag(RAGRequest request) {
        return performSearch(request);
    }

    @Override
    public RAGResponse performRAGQuery(RAGRequest request) {
        return performSearch(request);
    }

    @Override
    public void indexContent(String entityType, String entityId, String content, Map<String, Object> metadata) {
        throw new IllegalStateException("External retrieval connector is read-only. Indexing is not supported.");
    }

    @Override
    public void removeContent(String entityType, String entityId) {
        throw new IllegalStateException("External retrieval connector is read-only. Remove is not supported.");
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", getProviderName());
        out.put("enabled", properties != null && properties.isEnabled());
        out.put("baseUrlConfigured", properties != null && StringUtils.hasText(properties.getBaseUrl()));
        out.put("maxResponseDocuments", responsePolicy.maxDocuments());
        out.put(
            "unknownMetadataPolicy",
            responsePolicy.unknownMetadataPolicy().name()
        );
        out.put("customSanitizerCount", customSanitizers.size());
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String getProviderName() {
        return "connector-retrieval";
    }

    private RAGResponse performSearch(RAGRequest request) {
        long start = System.currentTimeMillis();
        if (request == null) {
            return failure("INVALID_REQUEST", "RAG request is required.", start);
        }
        String query = request.getQuery();
        if (!StringUtils.hasText(query)) {
            return failure("INVALID_REQUEST", "RAG request query is required.", start);
        }

        String vectorSpace = request.getEntityType();
        if (!StringUtils.hasText(vectorSpace)) {
            return failure("INVALID_REQUEST", "RAG request entityType must be set to the vectorSpace name.", start);
        }
        vectorSpace = vectorSpace.trim();
        if (vectorSpace.length()
            > responsePolicy.maxVectorSpaceCharacters()) {
            return failure(
                "INVALID_REQUEST",
                "RAG request entityType exceeds the configured vector-space"
                    + " limit.",
                start
            );
        }

        int topK = resolveTopK(request.getLimit());
        Map<String, Object> connectorFilters = request.getFilters() != null ? new LinkedHashMap<>(request.getFilters()) : null;

        String url;
        try {
            url = buildSearchUrl();
        } catch (Exception ex) {
            return failure(ERROR_SERVICE_UNAVAILABLE, ex.getMessage(), start);
        }

        Map<String, Object> connectorRequest = new LinkedHashMap<>();
        connectorRequest.put(RetrievalConnectorProtocol.KEY_QUERY, query.trim());
        connectorRequest.put(RetrievalConnectorProtocol.KEY_VECTOR_SPACE, vectorSpace);
        connectorRequest.put(RetrievalConnectorProtocol.KEY_TOP_K, topK);
        if (connectorFilters != null && !connectorFilters.isEmpty()) {
            connectorRequest.put(RetrievalConnectorProtocol.KEY_FILTERS, Collections.unmodifiableMap(connectorFilters));
        }
        connectorRequest.put(RetrievalConnectorProtocol.KEY_TRACE, buildTrace(request));

        String body = writeJson(connectorRequest);
        HttpHeaders headers = buildHeaders(body);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        int maxAttempts = Math.max(1, properties != null ? properties.getMaxAttempts() : 1);
        Duration backoff = properties != null && properties.getInitialBackoff() != null
            ? properties.getInitialBackoff()
            : Duration.ofSeconds(1);

        ConnectorResult connectorResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response = httpClient().exchange(url, HttpMethod.POST, entity, String.class);
                connectorResult = parseResponse(
                    response,
                    vectorSpace,
                    topK
                );

                if (shouldRetry(connectorResult) && attempt < maxAttempts) {
                    sleep(backoffForAttempt(backoff, attempt));
                    continue;
                }

                break;
            } catch (Exception ex) {
                if (shouldRetryException(ex) && attempt < maxAttempts) {
                    sleep(backoffForAttempt(backoff, attempt));
                    continue;
                }
                log.warn("Retrieval connector failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage());
                connectorResult = ConnectorResult.failure(ERROR_SERVICE_UNAVAILABLE, "Retrieval connector is unavailable.", List.of());
                break;
            }
        }

        if (connectorResult == null) {
            connectorResult = ConnectorResult.failure(ERROR_SERVICE_UNAVAILABLE, "Retrieval connector returned no result.", List.of());
        }

        long processingTimeMs = System.currentTimeMillis() - start;
        List<RAGResponse.RAGDocument> docs = connectorResult.documents() != null ? connectorResult.documents() : List.of();
        String context = buildContextFromDocuments(docs);
        if (connectorResult.success()
            && context.length() > responsePolicy.maxContextCharacters()) {
            connectorResult = ConnectorResult.failure(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector context exceeds the configured"
                    + " character limit.",
                connectorResult.warnings()
            );
            docs = List.of();
            context = NO_CONTEXT_MESSAGE;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", getProviderName());
        meta.put("vectorSpace", vectorSpace);
        if (StringUtils.hasText(connectorResult.errorCode())) {
            meta.put("errorCode", connectorResult.errorCode());
        }

        RAGResponse.RAGResponseBuilder builder = RAGResponse.builder()
            .success(connectorResult.success())
            .documents(docs)
            .context(context)
            .totalDocuments(connectorResult.totalCount() != null ? connectorResult.totalCount().intValue() : docs.size())
            .usedDocuments(docs.size())
            .relevanceScores(docs.stream().map(RAGResponse.RAGDocument::getScore).toList())
            .processingTimeMs(processingTimeMs)
            .requestId(request.getRequestId())
            .originalQuery(query)
            .entityType(vectorSpace)
            .timestamp(LocalDateTime.now(clock))
            .metadata(Collections.unmodifiableMap(meta));

        if (!connectorResult.success()) {
            builder.errorMessage(StringUtils.hasText(connectorResult.message())
                ? connectorResult.message()
                : "Retrieval connector request failed.");
        }

        if (!connectorResult.warnings().isEmpty()) {
            builder.warnings(connectorResult.warnings());
        }

        return builder.build();
    }

    private int resolveTopK(Integer requested) {
        int topK = requested != null ? requested : DEFAULT_LIMIT;
        topK = Math.max(1, topK);
        int max = properties != null ? properties.getMaxTopK() : 50;
        max = max > 0 ? max : 50;
        return Math.min(topK, max);
    }

    private Map<String, Object> buildTrace(RAGRequest request) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (request == null) {
            return trace;
        }
        putIfText(trace, RetrievalConnectorProtocol.TRACE_REQUEST_ID, request.getRequestId());
        Map<String, Object> authContext = buildAuthContext(request.getAuthContext());
        if (!authContext.isEmpty()) {
            trace.put(RetrievalConnectorProtocol.TRACE_AUTH_CONTEXT, Collections.unmodifiableMap(authContext));
        }
        return trace;
    }

    private Map<String, Object> buildAuthContext(AIAccessSubjectContext authContext) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (authContext == null) {
            return out;
        }
        putIfText(out, "subjectId", authContext.getSubjectId());
        putIfText(out, "sessionId", authContext.getSessionId());
        putIfText(out, "subjectType", authContext.getSubjectType());
        putIfText(out, "authMode", authContext.getAuthMode());
        putIfText(out, "callerType", authContext.getCallerType());
        putIfText(out, "deploymentId", authContext.getDeploymentId());
        putIfText(out, "customerId", authContext.getCustomerId());
        putIfText(out, "tenantId", authContext.getTenantId());
        putIfText(out, "issuer", authContext.getIssuer());
        putIfTextList(out, "grantedScopes", authContext.getGrantedScopes());
        putIfTextList(out, "audiences", authContext.getAudiences());
        putIfText(out, "expiresAt", authContext.getExpiresAt());
        return out;
    }

    private void putIfText(Map<String, Object> out, String key, String value) {
        if (out == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        out.put(key, value.trim());
    }

    private void putIfTextList(Map<String, Object> out, String key, List<String> values) {
        if (out == null || !StringUtils.hasText(key) || values == null || values.isEmpty()) {
            return;
        }
        List<String> clean = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                clean.add(value.trim());
            }
        }
        if (!clean.isEmpty()) {
            out.put(key, List.copyOf(clean));
        }
    }

    private String buildSearchUrl() {
        String baseUrl = properties != null ? properties.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("ai.retrieval.connector.baseUrl is required when external retrieval connector is enabled.");
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String path = properties != null ? properties.getSearchPath() : null;
        if (!StringUtils.hasText(path)) {
            path = RetrievalConnectorProtocol.PATH_DEFAULT_SEARCH;
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }

        return base + p;
    }

    private HttpClient httpClient() {
        if (httpClientInitialized) {
            return httpClient;
        }
        synchronized (this) {
            if (httpClientInitialized) {
                return httpClient;
            }
            Duration connect = properties != null ? properties.getConnectTimeout() : null;
            Duration read = properties != null ? properties.getReadTimeout() : null;
            HttpClient created = httpClientFactory != null && connect != null && read != null
                ? httpClientFactory.create(connect, read)
                : httpClientFactory.create();
            if (created == null) {
                throw new IllegalStateException("AIHttpClientFactory returned null for retrieval connector.");
            }
            httpClient = created;
            httpClientInitialized = true;
            log.debug("Initialized retrieval connector HttpClient (connectTimeout={}, readTimeout={})", connect, read);
            return httpClient;
        }
    }

    private HttpHeaders buildHeaders(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        AIRetrievalConnectorProperties.ApiKeyProperties apiKey = properties != null ? properties.getApiKey() : null;
        if (apiKey != null && StringUtils.hasText(apiKey.getHeader()) && StringUtils.hasText(apiKey.getValue())) {
            headers.set(apiKey.getHeader().trim(), apiKey.getValue().trim());
        }

        AIRetrievalConnectorProperties.HmacProperties hmac = properties != null ? properties.getHmac() : null;
        if (hmac != null && StringUtils.hasText(hmac.getSecret())) {
            String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
            String nonce = ulidGenerator.nextUlid();
            String signature = sign(hmac.getSecret(), timestamp, nonce, body);
            headers.set(headerOrDefault(hmac.getTimestampHeader(), HEADER_HMAC_TIMESTAMP), timestamp);
            headers.set(headerOrDefault(hmac.getNonceHeader(), HEADER_HMAC_NONCE), nonce);
            headers.set(headerOrDefault(hmac.getSignatureHeader(), HEADER_HMAC_SIGNATURE), signature);
        }

        return headers;
    }

    private String headerOrDefault(String configured, String fallback) {
        return StringUtils.hasText(configured) ? configured.trim() : fallback;
    }

    private String sign(String secret, String timestamp, String nonce, String body) {
        try {
            String message = timestamp + "\n" + nonce + "\n" + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC signature: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize retrieval connector request JSON: " + ex.getMessage(), ex);
        }
    }

    private ConnectorResult parseResponse(
        ResponseEntity<String> response,
        String requestedVectorSpace,
        int effectiveTopK
    ) {
        if (response == null) {
            return ConnectorResult.failure(ERROR_SERVICE_UNAVAILABLE, "Retrieval connector returned no response.", List.of());
        }

        String body = response.getBody();
        if (!StringUtils.hasText(body)) {
            if (!response.getStatusCode().is2xxSuccessful()) {
                int status = response.getStatusCode().value();
                return ConnectorResult.failure(errorCodeForStatus(status), "Retrieval connector returned HTTP " + status + ".", List.of());
            }
            return ConnectorResult.failure(ERROR_SERVICE_UNAVAILABLE, "Retrieval connector returned an empty response.", List.of());
        }
        if (body.length() > responsePolicy.maxResponseCharacters()) {
            return ConnectorResult.failure(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector response exceeds the configured"
                    + " character limit.",
                List.of()
            );
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return ConnectorResult.failure(
                ERROR_INVALID_RESPONSE,
                "Retrieval connector returned invalid JSON.",
                List.of()
            );
        }
        if (parsed == null) {
            return ConnectorResult.failure(
                ERROR_INVALID_RESPONSE,
                "Retrieval connector response must be a JSON object.",
                List.of()
            );
        }

        String message;
        String errorCode;
        try {
            message = readConnectorMessage(
                parsed.get(RetrievalConnectorProtocol.KEY_MESSAGE)
            );
            errorCode = readConnectorErrorCode(
                parsed.get(RetrievalConnectorProtocol.KEY_ERROR_CODE)
            );
        } catch (RetrievalDocumentPolicyException ex) {
            return ConnectorResult.failure(
                ex.errorCode(),
                ex.getMessage(),
                List.of()
            );
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            int status = response.getStatusCode().value();
            String msg = StringUtils.hasText(message) ? message : "Retrieval connector returned HTTP " + status + ".";
            String code = StringUtils.hasText(errorCode) ? errorCode : errorCodeForStatus(status);
            return ConnectorResult.failure(code, msg, List.of());
        }

        boolean success = readBoolean(parsed.get(RetrievalConnectorProtocol.KEY_SUCCESS), false);
        if (!success) {
            String msg = StringUtils.hasText(message) ? message : "Retrieval connector request failed.";
            String code = StringUtils.hasText(errorCode) ? errorCode : ERROR_SERVICE_UNAVAILABLE;
            return ConnectorResult.failure(code, msg, List.of());
        }

        List<String> forbiddenKeys = forbiddenDocumentsOnlyResponseKeys(parsed);
        if (!forbiddenKeys.isEmpty()) {
            return ConnectorResult.failure(
                ERROR_INVALID_RESPONSE,
                "Retrieval connector response must be documents-only. Forbidden top-level field(s): "
                    + String.join(", ", forbiddenKeys) + ".",
                List.of()
            );
        }

        DocumentParseResult documentResult;
        try {
            documentResult = parseDocuments(
                parsed.get(RetrievalConnectorProtocol.KEY_DOCUMENTS),
                requestedVectorSpace,
                effectiveTopK
            );
        } catch (RetrievalDocumentPolicyException ex) {
            return ConnectorResult.failure(
                ex.errorCode(),
                ex.getMessage(),
                List.of()
            );
        }
        if (documentResult.invalidResponse()) {
            return ConnectorResult.failure(ERROR_INVALID_RESPONSE, documentResult.message(), documentResult.warnings());
        }
        List<RAGResponse.RAGDocument> docs = documentResult.documents();
        List<String> warnings = new ArrayList<>(documentResult.warnings());
        if (docs.isEmpty()) {
            warnings.add("Retrieval connector returned 0 documents.");
        }

        Object totalCountRaw = parsed.get(
            RetrievalConnectorProtocol.KEY_TOTAL_COUNT
        );
        Long totalCount = readLong(totalCountRaw);
        if (totalCountRaw != null && totalCount == null) {
            return ConnectorResult.failure(
                ERROR_INVALID_RESPONSE,
                "Retrieval connector count fields must be integers.",
                warnings
            );
        }
        if (totalCount == null) {
            Object countRaw = parsed.get(
                RetrievalConnectorProtocol.KEY_COUNT
            );
            Long count = readLong(countRaw);
            if (countRaw != null && count == null) {
                return ConnectorResult.failure(
                    ERROR_INVALID_RESPONSE,
                    "Retrieval connector count fields must be integers.",
                    warnings
                );
            }
            totalCount = count != null ? count : (long) docs.size();
        }
        if (totalCount < 0 || totalCount > Integer.MAX_VALUE) {
            return ConnectorResult.failure(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector total count is outside the supported"
                    + " range.",
                warnings
            );
        }

        return new ConnectorResult(true, message, null, docs, warnings, totalCount);
    }

    private List<String> forbiddenDocumentsOnlyResponseKeys(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String key : parsed.keySet()) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String normalized = key.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_DOCUMENTS_ONLY_RESPONSE_KEYS.contains(normalized)) {
                String safeKey = key.trim();
                out.add(
                    safeKey.length() <= 64
                        ? safeKey
                        : "forbidden-field"
                );
            }
        }
        return List.copyOf(out);
    }

    private String readConnectorMessage(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw invalidConnectorText("message");
        }
        String normalized = text.trim();
        if (normalized.length() > responsePolicy.maxMessageCharacters()) {
            throw new RetrievalDocumentPolicyException(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector message exceeds the configured"
                    + " character limit."
            );
        }
        if (RetrievalResponsePolicy.containsControlCharacter(normalized)) {
            throw invalidConnectorText("message");
        }
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String readConnectorErrorCode(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw invalidConnectorText("error code");
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > responsePolicy.maxErrorCodeCharacters()) {
            throw new RetrievalDocumentPolicyException(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector error code exceeds the configured"
                    + " character limit."
            );
        }
        if (!StringUtils.hasText(normalized)
            || !CONNECTOR_ERROR_CODE.matcher(normalized).matches()) {
            throw invalidConnectorText("error code");
        }
        return normalized;
    }

    private RetrievalDocumentPolicyException invalidConnectorText(
        String field
    ) {
        return new RetrievalDocumentPolicyException(
            RetrievalDocumentPolicyException.DOCUMENT_POLICY_VIOLATION,
            "Retrieval connector " + field + " is invalid."
        );
    }

    private String errorCodeForStatus(int status) {
        if (status == 408) {
            return ERROR_TIMEOUT;
        }
        if (status == 429) {
            return ERROR_RATE_LIMITED;
        }
        if (status >= 500) {
            return ERROR_SERVICE_UNAVAILABLE;
        }
        return ERROR_HTTP_ERROR;
    }

    private DocumentParseResult parseDocuments(
        Object raw,
        String requestedVectorSpace,
        int effectiveTopK
    ) {
        if (raw == null) {
            return DocumentParseResult.invalid("Retrieval connector response documents must be an array.", List.of());
        }
        if (!(raw instanceof List<?> list)) {
            return DocumentParseResult.invalid("Retrieval connector response documents must be an array.", List.of());
        }
        int maximumDocuments = Math.min(
            effectiveTopK,
            responsePolicy.maxDocuments()
        );
        if (list.size() > maximumDocuments) {
            throw new RetrievalDocumentPolicyException(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector returned more documents than permitted."
            );
        }

        List<RAGResponse.RAGDocument> out = new ArrayList<>();
        int invalidDocuments = 0;
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            RAGResponse.RAGDocument doc = parseDocument(item);
            if (doc != null) {
                RetrievalDocumentSanitizationContext context =
                    new RetrievalDocumentSanitizationContext(
                        requestedVectorSpace,
                        effectiveTopK,
                        index
                    );
                out.add(sanitizeDocument(doc, context));
            } else {
                invalidDocuments++;
            }
        }
        List<String> warnings = new ArrayList<>();
        if (invalidDocuments > 0) {
            warnings.add("Retrieval connector skipped " + invalidDocuments + " invalid document(s).");
        }
        if (!list.isEmpty() && out.isEmpty()) {
            return DocumentParseResult.invalid(
                "Retrieval connector response did not include any valid documents.",
                warnings
            );
        }
        return DocumentParseResult.valid(Collections.unmodifiableList(out), warnings);
    }

    private RAGResponse.RAGDocument parseDocument(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }

        String id = readDocumentString(map.get(DOC_ID));
        String content = readDocumentString(map.get(DOC_CONTENT));
        Double score = readDocumentScore(map.get(DOC_SCORE));
        if (!StringUtils.hasText(id) || !StringUtils.hasText(content) || score == null) {
            return null;
        }

        String source = readDocumentString(map.get(DOC_SOURCE));
        String url = readDocumentString(map.get(DOC_URL));
        String vectorSpace = readDocumentString(
            map.get(DOC_VECTOR_SPACE)
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        Object metaRaw = map.get(DOC_METADATA);
        if (metaRaw instanceof Map<?, ?> metaMap) {
            for (Map.Entry<?, ?> entry : metaMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new RetrievalDocumentPolicyException(
                        RetrievalDocumentPolicyException
                            .METADATA_POLICY_VIOLATION,
                        "Retrieval connector metadata keys must be strings."
                    );
                }
                metadata.put(key, entry.getValue());
            }
        } else if (metaRaw != null) {
            throw new RetrievalDocumentPolicyException(
                RetrievalDocumentPolicyException
                    .METADATA_POLICY_VIOLATION,
                "Retrieval connector document metadata must be an object."
            );
        }

        return RAGResponse.RAGDocument.builder()
            .id(id.trim())
            .content(content)
            .type(StringUtils.hasText(vectorSpace) ? vectorSpace.trim() : null)
            .score(score)
            .similarity(score)
            .source(source)
            .url(url)
            .metadata(metadata.isEmpty() ? null : Collections.unmodifiableMap(metadata))
            .build();
    }

    private RAGResponse.RAGDocument sanitizeDocument(
        RAGResponse.RAGDocument document,
        RetrievalDocumentSanitizationContext context
    ) {
        RAGResponse.RAGDocument current = mandatorySanitizer.sanitize(
            document,
            context
        );
        for (RetrievalDocumentSanitizer customSanitizer
            : customSanitizers) {
            RAGResponse.RAGDocument before = mandatorySanitizer.sanitize(
                current,
                context
            );
            RAGResponse.RAGDocument customInput =
                mandatorySanitizer.sanitize(current, context);
            RAGResponse.RAGDocument candidate;
            try {
                candidate = customSanitizer.sanitize(
                    customInput,
                    context
                );
            } catch (RuntimeException ex) {
                throw sanitizerFailure();
            }
            if (candidate == null) {
                throw sanitizerFailure();
            }
            RAGResponse.RAGDocument approved;
            try {
                approved = mandatorySanitizer.sanitize(
                    candidate,
                    context
                );
            } catch (RuntimeException ex) {
                throw sanitizerFailure();
            }
            if (!isNarrowingSanitization(before, approved)) {
                throw sanitizerFailure();
            }
            current = approved;
        }
        return current;
    }

    private boolean isNarrowingSanitization(
        RAGResponse.RAGDocument before,
        RAGResponse.RAGDocument after
    ) {
        return Objects.equals(before.getId(), after.getId())
            && Objects.equals(before.getType(), after.getType())
            && Objects.equals(before.getScore(), after.getScore())
            && textWasNarrowed(
                before.getContent(),
                after.getContent(),
                false
            )
            && textWasNarrowed(
                before.getSource(),
                after.getSource(),
                true
            )
            && textWasNarrowed(
                before.getUrl(),
                after.getUrl(),
                true
            )
            && metadataWasNarrowed(
                before.getMetadata(),
                after.getMetadata()
            );
    }

    private boolean textWasNarrowed(
        String before,
        String after,
        boolean requireSameValue
    ) {
        if (after == null) {
            return true;
        }
        if (before == null) {
            return false;
        }
        if (requireSameValue) {
            return before.equals(after);
        }
        return after.length() <= before.length();
    }

    private boolean metadataWasNarrowed(
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        Map<String, Object> baseline =
            before != null ? before : Map.of();
        Map<String, Object> candidate =
            after != null ? after : Map.of();
        if (!baseline.keySet().containsAll(candidate.keySet())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
            if (!metadataValueWasNarrowed(
                baseline.get(entry.getKey()),
                entry.getValue()
            )) {
                return false;
            }
        }
        return true;
    }

    private boolean metadataValueWasNarrowed(
        Object before,
        Object after
    ) {
        if (after == null) {
            return true;
        }
        if (before instanceof Map<?, ?> beforeMap
            && after instanceof Map<?, ?> afterMap) {
            Map<String, Object> typedBefore = new LinkedHashMap<>();
            Map<String, Object> typedAfter = new LinkedHashMap<>();
            beforeMap.forEach((key, value) ->
                typedBefore.put(String.valueOf(key), value)
            );
            afterMap.forEach((key, value) ->
                typedAfter.put(String.valueOf(key), value)
            );
            return metadataWasNarrowed(typedBefore, typedAfter);
        }
        if (before instanceof List<?> beforeList
            && after instanceof List<?> afterList) {
            if (afterList.size() > beforeList.size()) {
                return false;
            }
            for (int index = 0; index < afterList.size(); index++) {
                if (!metadataValueWasNarrowed(
                    beforeList.get(index),
                    afterList.get(index)
                )) {
                    return false;
                }
            }
            return true;
        }
        if (before instanceof String beforeText
            && after instanceof String afterText) {
            return afterText.length() <= beforeText.length();
        }
        return Objects.equals(before, after);
    }

    private RetrievalDocumentPolicyException sanitizerFailure() {
        return new RetrievalDocumentPolicyException(
            RetrievalDocumentPolicyException.SANITIZATION_FAILED,
            "Application retrieval document sanitization failed."
        );
    }

    private boolean shouldRetry(ConnectorResult result) {
        if (result == null) {
            return true;
        }
        if (result.success()) {
            return false;
        }
        if (!StringUtils.hasText(result.errorCode())) {
            return false;
        }
        String code = result.errorCode().trim().toUpperCase(Locale.ROOT);
        return RETRYABLE_ERROR_CODES.contains(code);
    }

    private boolean shouldRetryException(Exception ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return false;
        }
        return true;
    }

    private Duration backoffForAttempt(Duration initial, int attempt) {
        Duration base = initial != null ? initial : Duration.ofSeconds(1);
        long multiplier = Math.max(1, 1L << Math.min(8, Math.max(0, attempt - 1)));
        long millis = base.toMillis() * multiplier;
        return Duration.ofMillis(Math.min(millis, Duration.ofSeconds(8).toMillis()));
    }

    private void sleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildContextFromDocuments(List<RAGResponse.RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }

        StringBuilder context = new StringBuilder();
        context.append(CONTEXT_HEADER);

        for (int i = 0; i < documents.size(); i++) {
            RAGResponse.RAGDocument doc = documents.get(i);
            String content = doc != null ? doc.getContent() : null;
            double score = doc != null && doc.getScore() != null ? doc.getScore() : 0.0;

            String id = doc != null ? doc.getId() : null;
            String vectorSpace = null;
            if (doc != null && doc.getMetadata() != null) {
                Object vs = doc.getMetadata().get(DOC_VECTOR_SPACE);
                if (vs instanceof String vsText && StringUtils.hasText(vsText)) {
                    vectorSpace = vsText.trim();
                }
            }
            if (!StringUtils.hasText(vectorSpace) && doc != null && StringUtils.hasText(doc.getType())) {
                vectorSpace = doc.getType().trim();
            }

            String header = "";
            if (StringUtils.hasText(vectorSpace) || StringUtils.hasText(id)) {
                header = "["
                    + (StringUtils.hasText(vectorSpace) ? "vectorSpace=" + vectorSpace : "")
                    + (StringUtils.hasText(vectorSpace) && StringUtils.hasText(id) ? " " : "")
                    + (StringUtils.hasText(id) ? "id=" + id : "")
                    + "] ";
            }

            context.append(String.format("%d. %s%s (Score: %.3f)\n",
                i + 1,
                header,
                content != null ? content : "",
                score));
        }

        return context.toString();
    }

    private boolean readBoolean(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s && StringUtils.hasText(s)) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private String readDocumentString(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Long readLong(Object raw) {
        if (raw instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double readDocumentScore(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private RAGResponse failure(String errorCode, String message, long startedAtMs) {
        long processingTimeMs = System.currentTimeMillis() - startedAtMs;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", getProviderName());
        if (StringUtils.hasText(errorCode)) {
            meta.put("errorCode", errorCode);
        }

        return RAGResponse.builder()
            .success(false)
            .documents(List.of())
            .context(NO_CONTEXT_MESSAGE)
            .totalDocuments(0)
            .usedDocuments(0)
            .relevanceScores(List.of())
            .processingTimeMs(processingTimeMs)
            .timestamp(LocalDateTime.now(clock))
            .metadata(Collections.unmodifiableMap(meta))
            .errorMessage(StringUtils.hasText(message) ? message : "Retrieval failed.")
            .build();
    }

    private record ConnectorResult(
        boolean success,
        String message,
        String errorCode,
        List<RAGResponse.RAGDocument> documents,
        List<String> warnings,
        Long totalCount
    ) {
        static ConnectorResult failure(String errorCode, String message, List<String> warnings) {
            return new ConnectorResult(false, message, errorCode, List.of(), warnings != null ? List.copyOf(warnings) : List.of(), 0L);
        }
    }

    private record DocumentParseResult(
        boolean invalidResponse,
        String message,
        List<RAGResponse.RAGDocument> documents,
        List<String> warnings
    ) {
        static DocumentParseResult valid(List<RAGResponse.RAGDocument> documents, List<String> warnings) {
            return new DocumentParseResult(
                false,
                null,
                documents != null ? List.copyOf(documents) : List.of(),
                warnings != null ? List.copyOf(warnings) : List.of()
            );
        }

        static DocumentParseResult invalid(String message, List<String> warnings) {
            return new DocumentParseResult(
                true,
                message,
                List.of(),
                warnings != null ? List.copyOf(warnings) : List.of()
            );
        }
    }
}
