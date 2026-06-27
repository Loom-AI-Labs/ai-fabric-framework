package ai.fabric.rag.evaluation.springai;

import ai.fabric.dto.RAGResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.util.StringUtils;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * AI Fabric facade for Spring AI RAG evaluation helpers.
 */
public class SpringAiRagEvaluationService {

    private static final String EVALUATOR_RELEVANCY = "spring-ai-relevancy";
    private static final String EVALUATOR_FACT_CHECKING = "spring-ai-fact-checking";

    private static final int DEFAULT_MAX_DOCUMENTS = 20;
    private static final int DEFAULT_MAX_DOCUMENT_CONTENT_CHARS = 4000;
    private static final int DEFAULT_MAX_METADATA_KEYS = 25;
    private static final int DEFAULT_MAX_METADATA_VALUE_CHARS = 512;

    private static final List<String> SENSITIVE_METADATA_KEY_PARTS = List.of(
        "authorization",
        "credential",
        "password",
        "secret",
        "token",
        "api_key",
        "apikey",
        "url",
        "uri",
        "path",
        "prompt",
        "completion",
        "embedding"
    );

    private final Evaluator relevancyEvaluator;
    private final Evaluator factCheckingEvaluator;
    private final int maxDocuments;
    private final int maxDocumentContentChars;
    private final int maxMetadataKeys;
    private final int maxMetadataValueChars;

    public SpringAiRagEvaluationService(Evaluator relevancyEvaluator,
                                        Evaluator factCheckingEvaluator) {
        this(
            relevancyEvaluator,
            factCheckingEvaluator,
            DEFAULT_MAX_DOCUMENTS,
            DEFAULT_MAX_DOCUMENT_CONTENT_CHARS,
            DEFAULT_MAX_METADATA_KEYS,
            DEFAULT_MAX_METADATA_VALUE_CHARS
        );
    }

    public SpringAiRagEvaluationService(Evaluator relevancyEvaluator,
                                        Evaluator factCheckingEvaluator,
                                        int maxDocuments,
                                        int maxDocumentContentChars,
                                        int maxMetadataKeys,
                                        int maxMetadataValueChars) {
        this.relevancyEvaluator = Objects.requireNonNull(relevancyEvaluator, "relevancyEvaluator is required");
        this.factCheckingEvaluator = factCheckingEvaluator;
        this.maxDocuments = maxDocuments > 0 ? maxDocuments : DEFAULT_MAX_DOCUMENTS;
        this.maxDocumentContentChars = maxDocumentContentChars > 0
            ? maxDocumentContentChars
            : DEFAULT_MAX_DOCUMENT_CONTENT_CHARS;
        this.maxMetadataKeys = maxMetadataKeys > 0 ? maxMetadataKeys : DEFAULT_MAX_METADATA_KEYS;
        this.maxMetadataValueChars = maxMetadataValueChars > 0
            ? maxMetadataValueChars
            : DEFAULT_MAX_METADATA_VALUE_CHARS;
    }

    public SpringAiRagEvaluationResult evaluateRelevancy(SpringAiRagEvaluationInput input) {
        return evaluate(input, relevancyEvaluator, EVALUATOR_RELEVANCY);
    }

    public SpringAiRagEvaluationResult evaluateFactChecking(SpringAiRagEvaluationInput input) {
        if (factCheckingEvaluator == null) {
            return SpringAiRagEvaluationResult.failed(
                EVALUATOR_FACT_CHECKING,
                "Spring AI fact-checking evaluator is not configured"
            );
        }
        return evaluate(input, factCheckingEvaluator, EVALUATOR_FACT_CHECKING);
    }

    EvaluationRequest toEvaluationRequest(SpringAiRagEvaluationInput input) {
        Objects.requireNonNull(input, "input is required");
        List<Document> documents = toSpringAiDocuments(input.ragResponse());
        return new EvaluationRequest(input.userText(), documents, input.responseContent());
    }

    private SpringAiRagEvaluationResult evaluate(SpringAiRagEvaluationInput input,
                                                 Evaluator evaluator,
                                                 String evaluatorName) {
        EvaluationRequest request = toEvaluationRequest(input);
        int documentCount = request.getDataList() != null ? request.getDataList().size() : 0;
        if (documentCount == 0) {
            return SpringAiRagEvaluationResult.failed(
                evaluatorName,
                "No RAG documents are available for evaluation"
            );
        }

        EvaluationResponse response = evaluator.evaluate(request);
        return SpringAiRagEvaluationResult.from(
            response,
            evaluatorName,
            documentCount,
            safeResponseMetadata(response)
        );
    }

    private List<Document> toSpringAiDocuments(RAGResponse ragResponse) {
        if (ragResponse == null || ragResponse.getDocuments() == null || ragResponse.getDocuments().isEmpty()) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        for (RAGResponse.RAGDocument ragDocument : ragResponse.getDocuments()) {
            if (ragDocument == null || !StringUtils.hasText(ragDocument.getContent())) {
                continue;
            }
            documents.add(toSpringAiDocument(ragDocument));
            if (documents.size() >= maxDocuments) {
                break;
            }
        }
        return List.copyOf(documents);
    }

    private Document toSpringAiDocument(RAGResponse.RAGDocument ragDocument) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfText(metadata, "title", ragDocument.getTitle());
        putIfText(metadata, "type", ragDocument.getType());
        putIfText(metadata, "source", ragDocument.getSource());
        putIfNumber(metadata, "score", ragDocument.getScore());
        putIfNumber(metadata, "similarity", ragDocument.getSimilarity());
        metadata.putAll(sanitizeMetadata(ragDocument.getMetadata()));

        Document.Builder builder = Document.builder()
            .text(boundedText(ragDocument.getContent(), maxDocumentContentChars))
            .metadata(metadata);
        if (StringUtils.hasText(ragDocument.getId())) {
            builder.id(ragDocument.getId().trim());
        }
        if (ragDocument.getScore() != null) {
            builder.score(ragDocument.getScore());
        }
        return builder.build();
    }

    private void putIfText(Map<String, Object> metadata, String key, String value) {
        String bounded = boundedText(value, maxMetadataValueChars);
        if (bounded != null) {
            metadata.put(key, bounded);
        }
    }

    private void putIfNumber(Map<String, Object> metadata, String key, Number value) {
        if (value != null) {
            metadata.put(key, value.doubleValue());
        }
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (sanitized.size() >= maxMetadataKeys) {
                break;
            }
            String key = safeMetadataKey(entry.getKey());
            if (!StringUtils.hasText(key) || isSensitiveMetadataKey(key)) {
                continue;
            }
            Object value = safeMetadataValue(entry.getValue());
            if (value != null) {
                sanitized.putIfAbsent(key, value);
            }
        }
        return sanitized;
    }

    private Map<String, Object> safeResponseMetadata(EvaluationResponse response) {
        return response == null ? Map.of() : sanitizeMetadata(response.getMetadata());
    }

    private String safeMetadataKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String trimmed = key.trim();
        return trimmed.length() <= 96 ? trimmed : "";
    }

    private boolean isSensitiveMetadataKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_METADATA_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private Object safeMetadataValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) ? number.doubleValue() : null;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? number : null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Enum<?> enumValue) {
            return boundedText(enumValue.name(), maxMetadataValueChars);
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof TemporalAccessor) {
            return boundedText(value.toString(), maxMetadataValueChars);
        }
        if (value instanceof CharSequence text) {
            return boundedText(text.toString(), maxMetadataValueChars);
        }
        return null;
    }

    private String boundedText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
