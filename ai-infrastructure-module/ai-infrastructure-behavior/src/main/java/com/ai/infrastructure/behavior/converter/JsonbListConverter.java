package com.ai.infrastructure.behavior.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.List;

@Converter(autoApply = false)
public class JsonbListConverter implements AttributeConverter<List<String>, String> {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert list to JSON", e);
        }
    }
    
    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Collections.emptyList();
        }
        try {
            String payload = dbData.trim();
            // Defensive: handle double-encoded JSON coming back as a quoted string
            if (payload.startsWith("\"") && payload.endsWith("\"")) {
                payload = payload.substring(1, payload.length() - 1).replace("\\\"", "\"");
            }
            return OBJECT_MAPPER.readValue(payload, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert JSON to list", e);
        }
    }
}
