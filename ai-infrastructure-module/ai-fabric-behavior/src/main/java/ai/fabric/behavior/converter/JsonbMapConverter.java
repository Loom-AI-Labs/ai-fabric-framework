package ai.fabric.behavior.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Converter(autoApply = false)
public class JsonbMapConverter implements AttributeConverter<Map<String, Object>, String> {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        return Optional.ofNullable(attribute)
            .filter(value -> !value.isEmpty())
            .map(this::writeJson)
            .orElse(null);
    }
    
    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            String payload = dbData.trim();
            if (payload.startsWith("\"") && payload.endsWith("\"")) {
                payload = payload.substring(1, payload.length() - 1).replace("\\\"", "\"");
            }
            return OBJECT_MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert JSON to map", e);
        }
    }

    private String writeJson(Map<String, Object> attribute) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert map to JSON", e);
        }
    }
}
