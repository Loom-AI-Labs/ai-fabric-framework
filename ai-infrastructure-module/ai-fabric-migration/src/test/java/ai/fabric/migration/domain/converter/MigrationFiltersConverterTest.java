package ai.fabric.migration.domain.converter;

import ai.fabric.migration.domain.MigrationFilters;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void treatsBlankJsonAsAbsentFilters() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(" ")).isNull();
    }

    @Test
    void rejectsMalformedJsonInsteadOfDroppingFilters() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not-json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid migration filters JSON");
    }
}
