package ai.fabric.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorMetadataFilterSupportTest {

    @Test
    void acceptsPortableScalarExactFilters() {
        VectorMetadataFilterSupport.ValidationResult result = VectorMetadataFilterSupport.validatePortableEquals(
            Map.of("tenant", "t1", "featured", true, "rank", 7, "large", BigInteger.valueOf(9))
        );

        assertThat(result.hasRejectedFilters()).isFalse();
        assertThat(result.terms()).hasSize(4);
    }

    @Test
    void acceptsEmptyStringAsPortableExactFilterValue() {
        VectorMetadataFilterSupport.ValidationResult result = VectorMetadataFilterSupport.validatePortableEquals(
            Map.of("status", "")
        );

        assertThat(result.hasRejectedFilters()).isFalse();
        assertThat(result.terms())
            .singleElement()
            .satisfies(term -> {
                assertThat(term.key()).isEqualTo("status");
                assertThat(term.value()).isEqualTo("");
                assertThat(term.kind()).isEqualTo(VectorMetadataFilterSupport.ValueKind.STRING);
            });
        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("status", ""),
            Map.of("status", "")
        )).isTrue();
    }

    @Test
    void rejectsNullArraysNestedObjectsAndDecimalByDefault() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("tenant", null);
        filters.put("tags", List.of("public"));
        filters.put("nested", Map.of("tier", "gold"));
        filters.put("score", 0.75d);

        VectorMetadataFilterSupport.ValidationResult result = VectorMetadataFilterSupport.validatePortableEquals(filters);

        assertThat(result.hasRejectedFilters()).isTrue();
        assertThat(result.rejectedFilters()).hasSize(4);
    }

    @Test
    void portableMatcherFailsClosedWhenFilterContainsUnsupportedShape() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("tenant", "t1");
        filters.put("tags", List.of("public"));

        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("tenant", "t1", "tags", List.of("public")),
            filters
        )).isFalse();
    }

    @Test
    void portableMatcherSupportsExactScalarComparison() {
        Map<String, Object> metadata = Map.of("tenant", "t1", "featured", "true", "rank", "7");

        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            metadata,
            Map.of("tenant", "t1", "featured", true, "rank", 7)
        )).isTrue();
    }

    @Test
    void integralFilterDoesNotMatchDecimalMetadataByTruncation() {
        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("rank", 7.0d),
            Map.of("rank", 7)
        )).isFalse();

        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("rank", 7.9d),
            Map.of("rank", 7)
        )).isFalse();
    }

    @Test
    void integralFilterStillMatchesIntegralMetadataRepresentations() {
        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("rank", BigInteger.valueOf(7)),
            Map.of("rank", 7)
        )).isTrue();

        assertThat(VectorMetadataFilterSupport.matchesPortableEquals(
            Map.of("rank", "7"),
            Map.of("rank", 7)
        )).isTrue();
    }
}
