package ai.fabric.intent.retrieval.connector;

import ai.fabric.dto.RAGResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mandatory fail-closed sanitizer for connector-owned retrieval documents.
 */
public final class DefaultRetrievalDocumentSanitizer
    implements RetrievalDocumentSanitizer {

    private static final Set<String> STRUCTURAL_METADATA_KEYS = Set.of(
        "vectorSpace",
        "source",
        "url"
    );
    private static final int MAX_METADATA_KEY_CHARACTERS = 128;

    private final RetrievalResponsePolicy policy;
    private final ObjectMapper objectMapper;

    public DefaultRetrievalDocumentSanitizer(
        RetrievalResponsePolicy policy,
        ObjectMapper objectMapper
    ) {
        this.policy = Objects.requireNonNull(policy, "policy is required");
        this.objectMapper = Objects.requireNonNullElseGet(
            objectMapper,
            ObjectMapper::new
        );
    }

    @Override
    public RAGResponse.RAGDocument sanitize(
        RAGResponse.RAGDocument document,
        RetrievalDocumentSanitizationContext context
    ) {
        Objects.requireNonNull(document, "document is required");
        Objects.requireNonNull(context, "context is required");

        String id = requiredText(
            document.getId(),
            "document id",
            policy.maxDocumentIdCharacters(),
            false
        );
        String content = requiredText(
            document.getContent(),
            "document content",
            policy.maxContentCharacters(),
            true
        );
        Double score = document.getScore();
        if (score == null || !Double.isFinite(score)) {
            throw violation(
                RetrievalDocumentPolicyException.DOCUMENT_POLICY_VIOLATION,
                "Retrieval connector document score must be finite."
            );
        }

        String requestedVectorSpace = boundedVectorSpace(
            context.requestedVectorSpace()
        );
        String documentVectorSpace = optionalText(
            document.getType(),
            "document vector space",
            policy.maxVectorSpaceCharacters(),
            false
        );
        String metadataVectorSpace = metadataVectorSpace(
            document.getMetadata()
        );
        if (documentVectorSpace != null
            && metadataVectorSpace != null
            && !documentVectorSpace.equals(metadataVectorSpace)) {
            throw vectorSpaceMismatch();
        }
        String returnedVectorSpace = documentVectorSpace != null
            ? documentVectorSpace
            : metadataVectorSpace;
        if (returnedVectorSpace == null) {
            returnedVectorSpace = requestedVectorSpace;
        }
        if (!requestedVectorSpace.equals(returnedVectorSpace)) {
            throw vectorSpaceMismatch();
        }

        String source = optionalText(
            document.getSource(),
            "document source",
            policy.maxSourceCharacters(),
            false
        );
        String url = sanitizeUrl(document.getUrl());
        Map<String, Object> metadata = projectMetadata(
            document.getMetadata()
        );

        return RAGResponse.RAGDocument.builder()
            .id(id)
            .content(content)
            .type(requestedVectorSpace)
            .score(score)
            .similarity(score)
            .source(source)
            .url(url)
            .metadata(metadata)
            .build();
    }

    private String boundedVectorSpace(String value) {
        return requiredText(
            value,
            "requested vector space",
            policy.maxVectorSpaceCharacters(),
            false
        );
    }

    private String metadataVectorSpace(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("vectorSpace");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw vectorSpaceMismatch();
        }
        return optionalText(
            text,
            "document vector space",
            policy.maxVectorSpaceCharacters(),
            false
        );
    }

    private String requiredText(
        String value,
        String field,
        int maxCharacters,
        boolean allowFormattingControls
    ) {
        String normalized = optionalText(
            value,
            field,
            maxCharacters,
            allowFormattingControls
        );
        if (normalized == null) {
            throw violation(
                RetrievalDocumentPolicyException.DOCUMENT_POLICY_VIOLATION,
                "Retrieval connector " + field + " is required."
            );
        }
        return normalized;
    }

    private String optionalText(
        String value,
        String field,
        int maxCharacters,
        boolean allowFormattingControls
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxCharacters) {
            throw violation(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector " + field
                    + " exceeds the configured character limit."
            );
        }
        if (containsUnsafeControl(
            normalized,
            allowFormattingControls
        )) {
            throw violation(
                RetrievalDocumentPolicyException.DOCUMENT_POLICY_VIOLATION,
                "Retrieval connector " + field
                    + " contains unsupported control characters."
            );
        }
        return normalized;
    }

    private boolean containsUnsafeControl(
        String value,
        boolean allowFormattingControls
    ) {
        return value.chars().anyMatch(character ->
            Character.isISOControl(character)
                && (!allowFormattingControls
                    || (character != '\n'
                        && character != '\r'
                        && character != '\t'))
        );
    }

    private String sanitizeUrl(String value) {
        String normalized = optionalText(
            value,
            "document URL",
            policy.maxUrlCharacters(),
            false
        );
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() != null
                ? uri.getScheme().toLowerCase(Locale.ROOT)
                : null;
            if (scheme == null
                || !policy.allowedUrlSchemes().contains(scheme)
                || !uri.isAbsolute()) {
                throw invalidUrl();
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (uri.getUserInfo() != null || uri.getHost() == null) {
                    throw invalidUrl();
                }
                validateHost(uri.getHost());
            }
            return uri.normalize().toASCIIString();
        } catch (RetrievalDocumentPolicyException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalidUrl();
        }
    }

    private void validateHost(String host) {
        if (policy.allowedUrlHostSuffixes().isEmpty()) {
            return;
        }
        String canonicalHost;
        try {
            canonicalHost = withoutTrailingDots(
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException ex) {
            throw invalidUrl();
        }
        boolean allowed = policy.allowedUrlHostSuffixes().stream()
            .anyMatch(suffix ->
                canonicalHost.equals(suffix)
                    || canonicalHost.endsWith("." + suffix)
            );
        if (!allowed) {
            throw invalidUrl();
        }
    }

    private String withoutTrailingDots(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        return value.substring(0, end);
    }

    private Map<String, Object> projectMetadata(
        Map<String, Object> metadata
    ) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        MetadataBudget budget = new MetadataBudget(
            policy.maxMetadataEntries()
        );
        Map<String, Object> projected = projectMap(
            metadata,
            "",
            0,
            budget,
            true
        );
        if (projected.isEmpty()) {
            return Map.of();
        }
        try {
            String serialized = objectMapper.writeValueAsString(projected);
            if (serialized.length() > policy.maxMetadataCharacters()) {
                throw violation(
                    RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                    "Retrieval connector metadata exceeds the configured"
                        + " character limit."
                );
            }
        } catch (RetrievalDocumentPolicyException ex) {
            throw ex;
        } catch (Exception ex) {
            throw violation(
                RetrievalDocumentPolicyException.METADATA_POLICY_VIOLATION,
                "Retrieval connector metadata is not valid JSON metadata."
            );
        }
        return Collections.unmodifiableMap(projected);
    }

    private Map<String, Object> projectMap(
        Map<?, ?> input,
        String parentPath,
        int depth,
        MetadataBudget budget,
        boolean root
    ) {
        requireMetadataDepth(depth);
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            budget.consume();
            if (!(entry.getKey() instanceof String rawKey)) {
                throw metadataViolation(
                    "Retrieval connector metadata keys must be strings."
                );
            }
            String key = normalizeMetadataKey(rawKey);
            String path = parentPath.isEmpty()
                ? key
                : parentPath + "." + key;
            if (RetrievalResponsePolicy.reservedMetadataPath(path)) {
                throw metadataViolation(
                    "Retrieval connector metadata contains a reserved key."
                );
            }
            if (root && STRUCTURAL_METADATA_KEYS.contains(key)) {
                continue;
            }
            if (!pathAllowedOrParent(path)) {
                handleUnknownMetadataPath();
                continue;
            }
            Object projected = projectValue(
                entry.getValue(),
                path,
                depth + 1,
                budget
            );
            if (projected != null
                && (!(projected instanceof Map<?, ?> map)
                    || !map.isEmpty())
                && (!(projected instanceof Collection<?> collection)
                    || !collection.isEmpty())) {
                output.put(key, projected);
            }
        }
        return output;
    }

    private Object projectValue(
        Object value,
        String path,
        int depth,
        MetadataBudget budget
    ) {
        requireMetadataDepth(depth);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return projectMap(map, path, depth, budget, false);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> output = new ArrayList<>();
            for (Object item : collection) {
                budget.consume();
                Object projected = projectValue(
                    item,
                    path,
                    depth + 1,
                    budget
                );
                if (projected != null) {
                    output.add(projected);
                }
            }
            return List.copyOf(output);
        }
        if (!policy.allowedMetadataKeys().contains(path)) {
            handleUnknownMetadataPath();
            return null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw metadataViolation(
                    "Retrieval connector metadata contains a non-finite number."
                );
            }
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > policy.maxMetadataCharacters()
                || RetrievalResponsePolicy.containsControlCharacter(text)) {
                throw metadataViolation(
                    "Retrieval connector metadata contains an invalid text value."
                );
            }
            return text;
        }
        throw metadataViolation(
            "Retrieval connector metadata contains an unsupported value."
        );
    }

    private boolean pathAllowedOrParent(String path) {
        if (policy.allowedMetadataKeys().contains(path)) {
            return true;
        }
        String prefix = path + ".";
        return policy.allowedMetadataKeys().stream()
            .anyMatch(candidate -> candidate.startsWith(prefix));
    }

    private void handleUnknownMetadataPath() {
        if (policy.unknownMetadataPolicy()
            == RetrievalUnknownMetadataPolicy.REJECT) {
            throw metadataViolation(
                "Retrieval connector metadata contains a non-allowlisted key."
            );
        }
    }

    private String normalizeMetadataKey(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()
            || normalized.length() > MAX_METADATA_KEY_CHARACTERS
            || normalized.contains(".")
            || RetrievalResponsePolicy.containsControlCharacter(normalized)) {
            throw metadataViolation(
                "Retrieval connector metadata contains an invalid key."
            );
        }
        return normalized;
    }

    private void requireMetadataDepth(int depth) {
        if (depth > policy.maxMetadataDepth()) {
            throw violation(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                "Retrieval connector metadata exceeds the configured depth."
            );
        }
    }

    private RetrievalDocumentPolicyException vectorSpaceMismatch() {
        return violation(
            RetrievalDocumentPolicyException.VECTOR_SPACE_MISMATCH,
            "Retrieval connector returned evidence from a different"
                + " vector space."
        );
    }

    private RetrievalDocumentPolicyException invalidUrl() {
        return violation(
            RetrievalDocumentPolicyException.URL_POLICY_VIOLATION,
            "Retrieval connector returned a URL that violates response policy."
        );
    }

    private RetrievalDocumentPolicyException metadataViolation(
        String message
    ) {
        return violation(
            RetrievalDocumentPolicyException.METADATA_POLICY_VIOLATION,
            message
        );
    }

    private RetrievalDocumentPolicyException violation(
        String errorCode,
        String message
    ) {
        return new RetrievalDocumentPolicyException(errorCode, message);
    }

    private static final class MetadataBudget {
        private final int maximum;
        private int consumed;

        private MetadataBudget(int maximum) {
            this.maximum = maximum;
        }

        private void consume() {
            consumed++;
            if (consumed > maximum) {
                throw new RetrievalDocumentPolicyException(
                    RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED,
                    "Retrieval connector metadata exceeds the configured"
                        + " entry limit."
                );
            }
        }
    }
}
