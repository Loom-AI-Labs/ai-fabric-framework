package ai.fabric.intent;

import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.llm.structured.StructuredJsonExtraction;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Shared parsing/sanitization utilities for intent extraction JSON payloads.
 */
@Component
public class IntentExtractionJsonSupport {

    private final ObjectMapper objectMapper;
    private final StructuredJsonExtractor structuredJsonExtractor;

    @Autowired
    public IntentExtractionJsonSupport(ObjectMapper objectMapper, StructuredJsonExtractor structuredJsonExtractor) {
        this.structuredJsonExtractor = structuredJsonExtractor != null ? structuredJsonExtractor : new StructuredJsonExtractor();
        this.objectMapper = objectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            // Some providers (and some model outputs) may include Java-style comments or trailing commas.
            // Be tolerant here; schema validation happens after parsing.
            .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
    }

    public IntentExtractionJsonSupport(ObjectMapper objectMapper) {
        this(objectMapper, new StructuredJsonExtractor());
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public MultiIntentResponse parseResponse(String rawJson) {
        return parsePayload(rawJson, MultiIntentResponse.class);
    }

    public <T> T parsePayload(String rawJson, Class<T> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType is required");
        }
        try {
            String extractedJson = extractJsonPayload(rawJson);
            JsonNode root = objectMapper.readTree(extractedJson);
            if (root == null || root.isNull()) {
                throw new AIServiceException("Intent extraction returned null JSON payload");
            }
            return objectMapper.treeToValue(root, targetType);
        } catch (JsonProcessingException ex) {
            throw new AIServiceException("Unable to parse intent extraction response: " + ex.getMessage(), ex);
        }
    }

    public String extractJsonPayload(String content) {
        StructuredJsonExtraction extraction = structuredJsonExtractor.extractFirstJson(content);
        if (!extraction.jsonFound() || !StringUtils.hasText(extraction.payload())) {
            throw new AIServiceException("Unable to parse intent extraction response: No JSON payload found in provider response");
        }
        return extraction.payload();
    }

    public String stripCodeFences(String content) {
        return structuredJsonExtractor.stripCodeFences(content);
    }

    public Map<String, Object> jsonOnlyResponseParameters() {
        return StructuredJsonProviderHints.jsonObjectResponseParameters();
    }
}
