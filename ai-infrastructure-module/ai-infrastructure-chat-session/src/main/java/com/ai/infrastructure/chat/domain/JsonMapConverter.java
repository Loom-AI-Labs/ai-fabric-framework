package com.ai.infrastructure.chat.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize metadata map", ex);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(dbData, MAP_REF);
            return parsed != null ? Collections.unmodifiableMap(parsed) : Collections.emptyMap();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to deserialize metadata map", ex);
        }
    }
}

