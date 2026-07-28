package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts only canonical, policy-filtered evidence from orchestration results.
 */
public final class OrchestrationEvidenceProjector {

    private static final String DOCUMENTS_KEY = "documents";
    private static final String RAG_RESPONSE_KEY = "ragResponse";

    private final AIEvidenceReferenceMapper mapper;

    public OrchestrationEvidenceProjector(AIEvidenceReferenceMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper is required");
    }

    public List<AIEvidenceReference> project(
        OrchestrationResult result,
        String fallbackVectorSpace,
        int limit
    ) {
        if (result == null || limit <= 0) {
            return List.of();
        }
        Map<String, AIEvidenceReference> references = new LinkedHashMap<>();
        collect(result, fallbackVectorSpace, Set.of(), false, limit, references);
        return List.copyOf(references.values());
    }

    /**
     * Projects evidence only when its vector space is proven to be in the effective profile.
     *
     * <p>An unresolved or denied space fails the execution because result prose may already
     * have been influenced by that evidence. Silently dropping the reference would not make
     * the generated answer safe.</p>
     */
    public List<AIEvidenceReference> projectStrict(
        OrchestrationResult result,
        Set<String> allowedVectorSpaces,
        String fallbackVectorSpace,
        int limit
    ) {
        if (result == null || limit <= 0) {
            return List.of();
        }
        Set<String> allowed = normalizeSpaces(allowedVectorSpaces);
        Map<String, AIEvidenceReference> references = new LinkedHashMap<>();
        collect(result, fallbackVectorSpace, allowed, true, limit, references);
        return List.copyOf(references.values());
    }

    private void collect(
        OrchestrationResult result,
        String fallbackVectorSpace,
        Set<String> allowedVectorSpaces,
        boolean strict,
        int limit,
        Map<String, AIEvidenceReference> references
    ) {
        if (references.size() >= limit || result == null) {
            return;
        }
        Map<String, Object> data = result.getData();
        if (data != null) {
            Object documents = data.get(DOCUMENTS_KEY);
            if (documents instanceof List<?> values) {
                collectDocuments(
                    values,
                    fallbackVectorSpace,
                    allowedVectorSpaces,
                    strict,
                    limit,
                    references
                );
            }
            Object ragResponse = data.get(RAG_RESPONSE_KEY);
            if (ragResponse instanceof RAGResponse response) {
                collectDocuments(
                    response.getDocuments() != null ? response.getDocuments() : List.of(),
                    fallbackVectorSpace,
                    allowedVectorSpaces,
                    strict,
                    limit,
                    references
                );
            }
        }
        if (result.getChildren() != null) {
            for (OrchestrationResult child : result.getChildren()) {
                collect(
                    child,
                    fallbackVectorSpace,
                    allowedVectorSpaces,
                    strict,
                    limit,
                    references
                );
                if (references.size() >= limit) {
                    break;
                }
            }
        }
    }

    private void collectDocuments(
        List<?> values,
        String fallbackVectorSpace,
        Set<String> allowedVectorSpaces,
        boolean strict,
        int limit,
        Map<String, AIEvidenceReference> references
    ) {
        for (Object value : new ArrayList<>(values)) {
            if (!(value instanceof RAGResponse.RAGDocument document)) {
                continue;
            }
            String vectorSpace = vectorSpace(document, fallbackVectorSpace);
            if (strict) {
                requireAllowedVectorSpace(vectorSpace, allowedVectorSpaces);
            }
            AIEvidenceReference reference = mapper.map(document, vectorSpace);
            String key = String.valueOf(reference.vectorSpace())
                + '\u0000'
                + reference.evidenceId();
            references.putIfAbsent(key, reference);
            if (references.size() >= limit) {
                break;
            }
        }
    }

    private String vectorSpace(
        RAGResponse.RAGDocument document,
        String fallbackVectorSpace
    ) {
        Object value = document.getMetadata() != null
            ? document.getMetadata().get("vectorSpace")
            : null;
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallbackVectorSpace;
    }

    private void requireAllowedVectorSpace(
        String vectorSpace,
        Set<String> allowedVectorSpaces
    ) {
        if (vectorSpace == null || vectorSpace.isBlank()) {
            throw new EvidencePolicyException(
                "EVIDENCE_VECTOR_SPACE_UNRESOLVED",
                "Evidence vector space could not be resolved."
            );
        }
        String normalized = vectorSpace.trim().toLowerCase(Locale.ROOT);
        if (!allowedVectorSpaces.contains(normalized)) {
            throw new EvidencePolicyException(
                "EVIDENCE_VECTOR_SPACE_DENIED",
                "Evidence is outside the effective vector-space profile."
            );
        }
    }

    private Set<String> normalizeSpaces(Set<String> spaces) {
        if (spaces == null || spaces.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        spaces.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    public static final class EvidencePolicyException extends RuntimeException {
        private final String reason;

        private EvidencePolicyException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
