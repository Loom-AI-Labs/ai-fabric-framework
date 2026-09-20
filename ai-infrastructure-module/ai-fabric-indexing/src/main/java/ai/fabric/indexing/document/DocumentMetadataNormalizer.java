package ai.fabric.indexing.document;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionWarning;
import ai.fabric.indexing.document.model.DocumentIngestionWarningCode;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Allowlist-only normalization for parser and trusted application metadata. */
public class DocumentMetadataNormalizer {

    public static final Set<String> DEFAULT_ALLOWED_KEYS = Set.of(
        "documentTitle",
        "documentSection",
        "sourceType",
        "locale",
        "createdAt",
        "updatedAt",
        "attributionLabel"
    );

    private static final Set<String> PROTECTED_KEYS = lower(DocumentMetadataKeys.ALL);
    private static final List<String> BLOCKED_KEY_PARTS = List.of(
        "authorization",
        "cookie",
        "credential",
        "password",
        "secret",
        "token",
        "apikey",
        "url",
        "uri",
        "path",
        "prompt",
        "completion",
        "embedding",
        "vector",
        "raw"
    );

    public NormalizedMetadata normalize(
        Map<String, Object> parserMetadata,
        Map<String, Object> applicationMetadata,
        Set<String> allowedKeys,
        int maxEntries,
        int maxValueLength,
        boolean warnOnDrop
    ) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must not be negative");
        }
        if (maxValueLength <= 0) {
            throw new IllegalArgumentException("maxValueLength must be positive");
        }

        Set<String> allowed = new LinkedHashSet<>(DEFAULT_ALLOWED_KEYS);
        if (allowedKeys != null) {
            allowedKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .map(String::trim)
                .forEach(allowed::add);
        }
        Set<String> allowedNormalized = lower(allowed);

        Map<String, Object> candidates = new LinkedHashMap<>();
        Map<String, String> normalizedNames = new LinkedHashMap<>();
        addCandidates(candidates, normalizedNames, parserMetadata);
        addCandidates(candidates, normalizedNames, applicationMetadata);

        Map<String, Object> result = new LinkedHashMap<>();
        List<DocumentIngestionWarning> warnings = new ArrayList<>();
        int dropped = 0;
        for (Map.Entry<String, Object> entry : candidates.entrySet()) {
            String key = entry.getKey();
            String normalizedKey = normalizedKey(key);
            if (PROTECTED_KEYS.contains(normalizedKey)) {
                throw new DocumentIngestionException(
                    DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED,
                    "Document metadata cannot override protected key " + safeKey(key)
                );
            }
            if (isBlocked(normalizedKey)
                || !allowedNormalized.contains(normalizedKey)) {
                dropped++;
                warn(warnings, warnOnDrop, DocumentIngestionWarningCode.METADATA_KEY_DROPPED, key);
                continue;
            }
            if (result.size() >= maxEntries) {
                dropped++;
                warn(warnings, warnOnDrop, DocumentIngestionWarningCode.METADATA_LIMIT_REACHED, "metadata");
                continue;
            }
            SafeValue safeValue = safeValue(entry.getValue(), maxValueLength);
            if (safeValue.value() == null) {
                dropped++;
                warn(warnings, warnOnDrop, DocumentIngestionWarningCode.METADATA_KEY_DROPPED, key);
                continue;
            }
            result.put(key, safeValue.value());
            if (safeValue.truncated()) {
                warn(warnings, warnOnDrop, DocumentIngestionWarningCode.METADATA_VALUE_TRUNCATED, key);
            }
        }
        return new NormalizedMetadata(
            Collections.unmodifiableMap(result),
            List.copyOf(warnings),
            dropped
        );
    }

    private void addCandidates(
        Map<String, Object> candidates,
        Map<String, String> normalizedNames,
        Map<String, Object> additions
    ) {
        if (additions == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty() || key.length() > 128) {
                throw new DocumentIngestionException(
                    DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED,
                    "Document metadata contains an invalid key"
                );
            }
            String normalized = normalizedKey(key);
            String previous = normalizedNames.putIfAbsent(normalized, key);
            if (previous != null) {
                throw new DocumentIngestionException(
                    DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED,
                    "Document metadata contains duplicate key " + key
                );
            }
            candidates.put(key, entry.getValue());
        }
    }

    private SafeValue safeValue(Object value, int maxLength) {
        if (value == null) {
            return SafeValue.empty();
        }
        if (value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long) {
            return new SafeValue(value, false);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number)
                ? new SafeValue(number.doubleValue(), false)
                : SafeValue.empty();
        }
        if (value instanceof Double number) {
            return Double.isFinite(number)
                ? new SafeValue(number, false)
                : SafeValue.empty();
        }
        if (value instanceof Number number) {
            return new SafeValue(number, false);
        }
        if (value instanceof Enum<?> enumValue) {
            return bounded(enumValue.name(), maxLength);
        }
        if (value instanceof Character character) {
            return new SafeValue(character.toString(), false);
        }
        if (value instanceof TemporalAccessor) {
            return bounded(value.toString(), maxLength);
        }
        if (value instanceof CharSequence text) {
            return bounded(text.toString(), maxLength);
        }
        return SafeValue.empty();
    }

    private SafeValue bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return SafeValue.empty();
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
            ? new SafeValue(normalized, false)
            : new SafeValue(normalized.substring(0, maxLength), true);
    }

    private boolean isBlocked(String normalizedKey) {
        return BLOCKED_KEY_PARTS.stream().anyMatch(normalizedKey::contains);
    }

    private void warn(
        List<DocumentIngestionWarning> warnings,
        boolean enabled,
        DocumentIngestionWarningCode code,
        String field
    ) {
        if (enabled) {
            warnings.add(DocumentIngestionWarning.one(code, safeKey(field)));
        }
    }

    private String safeKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private String normalizedKey(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> lower(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", ""));
        }
        return Set.copyOf(normalized);
    }

    public record NormalizedMetadata(
        Map<String, Object> metadata,
        List<DocumentIngestionWarning> warnings,
        int droppedCount
    ) {
    }

    private record SafeValue(Object value, boolean truncated) {
        private static SafeValue empty() {
            return new SafeValue(null, false);
        }
    }
}
