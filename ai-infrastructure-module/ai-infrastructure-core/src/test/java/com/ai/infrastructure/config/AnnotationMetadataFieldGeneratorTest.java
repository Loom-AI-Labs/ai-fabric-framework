package com.ai.infrastructure.config;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.annotation.AIContext;
import com.ai.infrastructure.dto.AIMetadataField;
import com.ai.infrastructure.processor.AnnotationFieldScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnnotationMetadataFieldGenerator Tests")
class AnnotationMetadataFieldGeneratorTest {

    @Test
    @DisplayName("Should infer metadata field types from @AIContext dataType/java type")
    void shouldInferTypes() {
        AnnotationFieldScanner scanner = new AnnotationFieldScanner();
        AnnotationMetadataFieldGenerator generator = new AnnotationMetadataFieldGenerator(scanner);

        List<AIMetadataField> fields = generator.buildMetadataFields(TestEntity.class);

        assertThat(fields).extracting(AIMetadataField::getName)
            .contains("imageUrl", "stock", "active", "createdAt", "status", "customer_id");

        assertThat(find(fields, "imageUrl").getType()).isEqualTo("string");
        assertThat(find(fields, "stock").getType()).isEqualTo("number");
        assertThat(find(fields, "active").getType()).isEqualTo("boolean");
        assertThat(find(fields, "createdAt").getType()).isEqualTo("date");
        assertThat(find(fields, "status").getType()).isEqualTo("enum");
        assertThat(find(fields, "customer_id").getType()).isEqualTo("id");
    }

    private static AIMetadataField find(List<AIMetadataField> fields, String name) {
        return fields.stream()
            .filter(field -> field != null && name.equals(field.getName()))
            .findFirst()
            .orElseThrow();
    }

    enum Status { ACTIVE, INACTIVE }

    @AICapable(entityType = "product")
    static class TestEntity {
        @AIContext(contextKey = "imageUrl", dataType = "string")
        private String imageUrl;

        @AIContext(contextKey = "stock", dataType = "auto")
        private int stock;

        @AIContext(contextKey = "active", dataType = "auto")
        private Boolean active;

        @AIContext(contextKey = "createdAt", dataType = "auto")
        private LocalDateTime createdAt;

        @AIContext(contextKey = "status", dataType = "auto")
        private Status status;

        @AIContext(contextKey = "customer_id", dataType = "id")
        private UUID customerId;
    }
}

