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

import java.util.Optional;

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
        return serialize(attribute).orElse(null);
    }

    private Optional<String> serialize(MigrationFilters attribute) {
        if (attribute == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.writeValueAsString(attribute));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize migration filters, storing null", e);
            return Optional.empty();
        }
    }

    @Override
    public MigrationFilters convertToEntityAttribute(String dbData) {
        return deserialize(dbData).orElse(null);
    }

    private Optional<MigrationFilters> deserialize(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(dbData, MigrationFilters.class));
        } catch (Exception e) {
            log.warn("Failed to deserialize migration filters, ignoring value", e);
            return Optional.empty();
        }
    }
}
