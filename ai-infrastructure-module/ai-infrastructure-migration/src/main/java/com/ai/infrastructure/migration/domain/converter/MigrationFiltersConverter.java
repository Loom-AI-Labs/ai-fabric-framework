package com.ai.infrastructure.migration.domain.converter;

import com.ai.infrastructure.migration.domain.MigrationFilters;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA converter that stores filters as JSON.
 */
@Slf4j
@Converter(autoApply = false)
public class MigrationFiltersConverter implements AttributeConverter<MigrationFilters, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(MigrationFilters attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize migration filters, storing null", e);
            return null;
        }
    }

    @Override
    public MigrationFilters convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, MigrationFilters.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize migration filters, ignoring value", e);
            return null;
        }
    }
}
