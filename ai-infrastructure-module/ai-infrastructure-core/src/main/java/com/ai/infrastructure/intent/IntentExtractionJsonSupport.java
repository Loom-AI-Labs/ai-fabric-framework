package com.ai.infrastructure.intent;

import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.exception.AIServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Shared parsing/sanitization utilities for intent extraction JSON payloads.
 */
@Component
public class IntentExtractionJsonSupport {

    private final ObjectMapper objectMapper;

    public IntentExtractionJsonSupport(ObjectMapper objectMapper) {
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

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public MultiIntentResponse parseResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root == null || root.isNull()) {
                throw new AIServiceException("Intent extraction returned null JSON payload");
            }
            return objectMapper.treeToValue(root, MultiIntentResponse.class);
        } catch (JsonProcessingException firstAttempt) {
            String extractedJson = extractJsonFromText(rawJson);
            if (extractedJson != null && !extractedJson.equals(rawJson)) {
                try {
                    JsonNode root = objectMapper.readTree(extractedJson);
                    if (root == null || root.isNull()) {
                        throw new AIServiceException("Intent extraction returned null JSON payload");
                    }
                    return objectMapper.treeToValue(root, MultiIntentResponse.class);
                } catch (JsonProcessingException ignored) {
                    // Fall through to throw the original parse error.
                }
            }
            throw new AIServiceException("Unable to parse intent extraction response: " + firstAttempt.getMessage(), firstAttempt);
        }
    }

    public String stripCodeFences(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }

        String trimmed = content.trim();
        while (trimmed.startsWith("###")) {
            int nextNewline = trimmed.indexOf('\n');
            if (nextNewline < 0) {
                break;
            }
            trimmed = trimmed.substring(nextNewline + 1).trim();
        }

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }

        int firstFence = trimmed.indexOf("```");
        if (firstFence >= 0) {
            int endFence = trimmed.indexOf("```", firstFence + 3);
            if (endFence > firstFence) {
                trimmed = trimmed.substring(firstFence + 3, endFence);
            }
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    public Map<String, Object> jsonOnlyResponseParameters() {
        return Map.of(
            "response_format", Map.of("type", "json_object")
        );
    }

    private String extractJsonFromText(String text) {
        if (text == null) {
            return null;
        }
        int startIdx = text.indexOf('{');
        if (startIdx >= 0) {
            int endIdx = text.lastIndexOf('}');
            if (endIdx > startIdx) {
                return text.substring(startIdx, endIdx + 1);
            }
        }
        return text;
    }
}
