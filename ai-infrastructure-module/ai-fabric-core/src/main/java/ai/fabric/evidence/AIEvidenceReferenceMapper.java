package ai.fabric.evidence;

import ai.fabric.dto.RAGResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects filtered RAG documents into the canonical read-side evidence contract.
 */
public final class AIEvidenceReferenceMapper {

    public static final Set<String> DEFAULT_SAFE_METADATA_KEYS = Set.of(
        "entityType",
        "entityId",
        "revision",
        "sourceVersion",
        "documentId",
        "contentHash"
    );

    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_COLLECTION_VALUES = 32;
    private static final int MAX_TEXT_VALUE_LENGTH = 2_048;

    private final Set<String> safeMetadataKeys;

    public AIEvidenceReferenceMapper() {
        this(DEFAULT_SAFE_METADATA_KEYS);
    }

    /**
     * Creates a mapper with an application/policy-owned metadata allowlist.
     */
    public AIEvidenceReferenceMapper(Set<String> safeMetadataKeys) {
        if (safeMetadataKeys == null || safeMetadataKeys.isEmpty()) {
            this.safeMetadataKeys = Set.of();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String key : safeMetadataKeys) {
            if (key != null && !key.isBlank()) {
                normalized.add(key.trim());
            }
        }
        this.safeMetadataKeys = Collections.unmodifiableSet(normalized);
    }

    public AIEvidenceReference map(RAGResponse.RAGDocument document, String vectorSpace) {
        Objects.requireNonNull(document, "document is required");
        return new AIEvidenceReference(
            document.getId(),
            document.getContent(),
            firstScore(document),
            safeText(document.getSource()),
            safeUrl(document.getUrl()),
            safeText(vectorSpace),
            filterMetadata(document.getMetadata())
        );
    }

    public List<AIEvidenceReference> mapAll(
        Collection<RAGResponse.RAGDocument> documents,
        String vectorSpace
    ) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<AIEvidenceReference> references = new ArrayList<>(documents.size());
        for (RAGResponse.RAGDocument document : documents) {
            if (document != null) {
                references.add(map(document, vectorSpace));
            }
        }
        return List.copyOf(references);
    }

    private Double firstScore(RAGResponse.RAGDocument document) {
        return document.getScore() != null ? document.getScore() : document.getSimilarity();
    }

    private Map<String, Object> filterMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || safeMetadataKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : safeMetadataKeys) {
            if (safe.size() >= MAX_METADATA_ENTRIES || !metadata.containsKey(key)) {
                continue;
            }
            Object value = safeValue(metadata.get(key));
            if (value != null) {
                safe.put(key, value);
            }
        }
        return safe.isEmpty() ? Map.of() : Collections.unmodifiableMap(safe);
    }

    private Object safeValue(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String text) {
            return boundedText(text);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> safe = new ArrayList<>();
            for (Object item : collection) {
                if (safe.size() >= MAX_COLLECTION_VALUES) {
                    break;
                }
                if (item instanceof Boolean || item instanceof Number) {
                    safe.add(item);
                } else if (item instanceof String text) {
                    safe.add(boundedText(text));
                }
            }
            return List.copyOf(safe);
        }
        return null;
    }

    private String safeUrl(String value) {
        String normalized = safeText(value);
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String safeText(String value) {
        if (value == null || value.isBlank() || containsControlCharacter(value)) {
            return null;
        }
        return boundedText(value.trim());
    }

    private String boundedText(String value) {
        String normalized = value.trim();
        return normalized.length() <= MAX_TEXT_VALUE_LENGTH
            ? normalized
            : normalized.substring(0, MAX_TEXT_VALUE_LENGTH);
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character ->
            Character.isISOControl(character) && character != '\t'
        );
    }
}
