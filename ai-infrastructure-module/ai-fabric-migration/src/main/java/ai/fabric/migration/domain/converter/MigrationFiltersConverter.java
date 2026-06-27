package ai.fabric.migration.domain.converter;

import ai.fabric.migration.domain.MigrationFilters;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA converter that stores filters as JSON.
 */
@Slf4j
@Converter(autoApply = false)
public class MigrationFiltersConverter implements AttributeConverter<MigrationFilters, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public String convertToDatabaseColumn(MigrationFilters attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize migration filters", e);
            throw new IllegalStateException("Failed to serialize migration filters.", e);
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
            log.warn("Failed to deserialize migration filters", e);
            throw new IllegalArgumentException("Invalid migration filters JSON.", e);
        }
    }
}
