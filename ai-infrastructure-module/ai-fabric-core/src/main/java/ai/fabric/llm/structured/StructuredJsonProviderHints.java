package ai.fabric.llm.structured;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-agnostic hints for requesting structured JSON responses.
 *
 * <p>Some providers (OpenAI/Azure) support {@code response_format}. Others may ignore it, but still benefit from
 * explicit "JSON-only" prompt contracts.</p>
 */
public final class StructuredJsonProviderHints {

    public static final String RESPONSE_FORMAT_PARAMETER = "response_format";
    public static final String OUTPUT_SCHEMA_PARAMETER = "output_schema";

    private StructuredJsonProviderHints() {
    }

    public static Map<String, Object> jsonObjectResponseParameters() {
        return Map.of(
            RESPONSE_FORMAT_PARAMETER, Map.of("type", "json_object")
        );
    }

    /**
     * Request provider-native JSON-object output while retaining the full
     * schema for prompt guidance and framework validation.
     *
     * <p>This contract is selected before the provider call for output models
     * that contain dynamic maps. Those maps cannot be represented by strict
     * provider schemas without discarding valid application-owned keys. This
     * is not a retry or a provider-failure fallback.</p>
     */
    public static Map<String, Object> schemaGuidedJsonObjectResponseParameters(String jsonSchema) {
        if (!StringUtils.hasText(jsonSchema)) {
            return jsonObjectResponseParameters();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(RESPONSE_FORMAT_PARAMETER, Map.of("type", "json_object"));
        parameters.put(OUTPUT_SCHEMA_PARAMETER, jsonSchema.trim());
        return Map.copyOf(parameters);
    }

    /**
     * Request provider-native JSON schema output while retaining a provider-
     * agnostic parameter contract at the AI Fabric boundary.
     */
    public static Map<String, Object> jsonSchemaResponseParameters(String jsonSchema) {
        if (!StringUtils.hasText(jsonSchema)) {
            return jsonObjectResponseParameters();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(RESPONSE_FORMAT_PARAMETER, Map.of("type", "json_schema"));
        parameters.put(OUTPUT_SCHEMA_PARAMETER, jsonSchema.trim());
        return Map.copyOf(parameters);
    }

    public static String outputSchema(Map<String, Object> parameters) {
        Object value = parameters != null ? parameters.get(OUTPUT_SCHEMA_PARAMETER) : null;
        return value instanceof String schema && StringUtils.hasText(schema)
            ? schema.trim()
            : null;
    }

    public static String responseFormatType(Map<String, Object> parameters) {
        Object value = parameters != null ? parameters.get(RESPONSE_FORMAT_PARAMETER) : null;
        if (!(value instanceof Map<?, ?> format)) {
            return null;
        }
        Object type = format.get("type");
        return type instanceof String text && StringUtils.hasText(text)
            ? text.trim()
            : null;
    }
}
