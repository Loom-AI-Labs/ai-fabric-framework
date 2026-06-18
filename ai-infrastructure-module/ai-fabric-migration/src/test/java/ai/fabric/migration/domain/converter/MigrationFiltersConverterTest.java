package ai.fabric.migration.domain.converter;

import ai.fabric.migration.domain.MigrationFilters;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationFiltersConverterTest {

    private final MigrationFiltersConverter converter = new MigrationFiltersConverter();

    @Test
    void roundTripsMigrationFiltersAsJson() {
        MigrationFilters filters = MigrationFilters.builder()
            .createdAfter(LocalDate.of(2024, 1, 1))
            .createdBefore(LocalDate.of(2024, 2, 1))
            .entityIds(List.of("a", "b"))
            .build();

        String json = converter.convertToDatabaseColumn(filters);
        MigrationFilters restored = converter.convertToEntityAttribute(json);

        assertThat(json).contains("createdAfter", "entityIds");
        assertThat(restored.getCreatedAfter()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(restored.getCreatedBefore()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(restored.getEntityIds()).containsExactly("a", "b");
    }

    @Test
    void treatsBlankOrMalformedJsonAsAbsentFilters() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(" ")).isNull();
        assertThat(converter.convertToEntityAttribute("{not-json")).isNull();
    }
}
