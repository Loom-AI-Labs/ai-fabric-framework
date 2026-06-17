package ai.fabric.rag.service;

import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGResponse;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class RAGDocumentMapper {

    static final String RESULT_KEY_CONTENT = "content";
    static final String RESULT_KEY_SCORE = "score";
    static final String RESULT_KEY_SIMILARITY = "similarity";
    static final String RESULT_KEY_ID = "id";
    static final String RESULT_KEY_TITLE = "title";
    static final String RESULT_KEY_VECTOR_SPACE = "vectorSpace";
    static final String RESULT_KEY_ENTITY_TYPE = "entityType";
    static final String RESULT_KEY_METADATA = "metadata";

    private static final String NO_CONTEXT_MESSAGE = "No relevant context found.";
    private static final String CONTEXT_HEADER = "Relevant Context:\n\n";

    List<RAGResponse.RAGDocument> toDocuments(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
            .filter(Objects::nonNull)
            .map(this::toDocument)
            .collect(Collectors.toList());
    }

    List<RAGResponse.RAGDocument> toFilteredDocuments(List<Map<String, Object>> results, Map<String, Object> filters) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
            .filter(Objects::nonNull)
            .map(result -> {
                Map<String, Object> metadata = new LinkedHashMap<>(
                    RAGMetadataSupport.normalizeMetadata(result.get(RESULT_KEY_METADATA))
                );
                if (!RAGMetadataSupport.matchesFilters(metadata, filters)) {
                    return null;
                }
                return toDocument(result, metadata);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    RAGResponse.RAGDocument toDocument(Map<String, Object> result) {
        Map<String, Object> metadata = new LinkedHashMap<>(
            RAGMetadataSupport.normalizeMetadata(result.get(RESULT_KEY_METADATA))
        );
        return toDocument(result, metadata);
    }

    String buildContext(AISearchResponse searchResponse) {
        if (searchResponse == null || searchResponse.getResults() == null || searchResponse.getResults().isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }

        StringBuilder context = new StringBuilder(CONTEXT_HEADER);
        List<Map<String, Object>> results = searchResponse.getResults();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> result = results.get(i);
            context.append(String.format("%d. %s (Score: %.3f)\n",
                i + 1,
                valueAsString(result != null ? result.get(RESULT_KEY_CONTENT) : null),
                score(result != null ? result.get(RESULT_KEY_SCORE) : null)));
        }
        return context.toString();
    }

    String buildContextFromDocuments(List<RAGResponse.RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }

        StringBuilder context = new StringBuilder(CONTEXT_HEADER);
        for (int i = 0; i < documents.size(); i++) {
            RAGResponse.RAGDocument doc = documents.get(i);
            String content = doc != null ? doc.getContent() : null;
            double score = doc != null && doc.getScore() != null ? doc.getScore() : 0.0;
            String header = documentHeader(doc);

            context.append(String.format("%d. %s%s (Score: %.3f)\n",
                i + 1,
                header,
                content != null ? content : "",
                score));
        }
        return context.toString();
    }

    private RAGResponse.RAGDocument toDocument(Map<String, Object> result, Map<String, Object> metadata) {
        String vectorSpace = resolveVectorSpace(result);
        if (StringUtils.hasText(vectorSpace)) {
            metadata.put(RESULT_KEY_VECTOR_SPACE, vectorSpace);
        }

        return RAGResponse.RAGDocument.builder()
            .id(valueAsString(result.get(RESULT_KEY_ID)))
            .content(valueAsString(result.get(RESULT_KEY_CONTENT)))
            .title(valueAsString(result.get(RESULT_KEY_TITLE)))
            .type(vectorSpace)
            .score(score(result.get(RESULT_KEY_SCORE)))
            .similarity(score(result.get(RESULT_KEY_SIMILARITY)))
            .metadata(metadata)
            .source(resolveAttributionLabel(metadata))
            .build();
    }

    private String resolveVectorSpace(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }

        String direct = valueAsString(result.get(RESULT_KEY_VECTOR_SPACE));
        if (StringUtils.hasText(direct)) {
            return direct.trim();
        }

        String entityType = valueAsString(result.get(RESULT_KEY_ENTITY_TYPE));
        if (StringUtils.hasText(entityType)) {
            return entityType.trim();
        }

        return null;
    }

    private String resolveAttributionLabel(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String attributionLabel = valueAsString(metadata.get("knowledgeSourceAttributionLabel"));
        if (StringUtils.hasText(attributionLabel)) {
            return attributionLabel.trim();
        }
        String sourceId = valueAsString(metadata.get("knowledgeSourceId"));
        if (StringUtils.hasText(sourceId)) {
            return sourceId.trim();
        }
        return null;
    }

    private String documentHeader(RAGResponse.RAGDocument doc) {
        if (doc == null) {
            return "";
        }

        String id = doc.getId();
        String vectorSpace = null;
        if (doc.getMetadata() != null) {
            String metadataVectorSpace = valueAsString(doc.getMetadata().get(RESULT_KEY_VECTOR_SPACE));
            if (StringUtils.hasText(metadataVectorSpace)) {
                vectorSpace = metadataVectorSpace.trim();
            }
        }
        if (!StringUtils.hasText(vectorSpace) && StringUtils.hasText(doc.getType())) {
            vectorSpace = doc.getType().trim();
        }

        if (!StringUtils.hasText(vectorSpace) && !StringUtils.hasText(id)) {
            return "";
        }

        return "["
            + (StringUtils.hasText(vectorSpace) ? "vectorSpace=" + vectorSpace : "")
            + (StringUtils.hasText(vectorSpace) && StringUtils.hasText(id) ? " " : "")
            + (StringUtils.hasText(id) ? "id=" + id : "")
            + "] ";
    }

    private double score(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        Double parsed = RAGMetadataSupport.parseDouble(value);
        return parsed != null ? parsed : 0.0;
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
