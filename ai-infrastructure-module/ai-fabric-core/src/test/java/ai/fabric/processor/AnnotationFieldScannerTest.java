package ai.fabric.processor;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.testsupport.SimplePIIDetectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for AnnotationFieldScanner
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnotationFieldScanner Tests")
class AnnotationFieldScannerTest {

    @InjectMocks
    private AnnotationFieldScanner scanner;

    @Test
    @DisplayName("Should extract searchable content from @AISearchable fields")
    void shouldExtractSearchableContent() {
        // Arrange
        TestProduct product = new TestProduct();
        product.setName("MacBook Pro M3");
        product.setDescription("Powerful laptop for developers with 16GB RAM");

        // Act
        String searchableContent = scanner.extractSearchableContent(product);

        // Assert
        assertThat(searchableContent).isNotNull();
        assertThat(searchableContent).contains("macbook pro m3");  // normalized
        assertThat(searchableContent).contains("powerful laptop");
    }

    @Test
    @DisplayName("Should extract context metadata from @AIContext fields")
    void shouldExtractContextMetadata() {
        // Arrange
        TestProduct product = new TestProduct();
        product.setId(UUID.randomUUID());
        product.setCategory("Electronics");
        product.setStatus(ProductStatus.ACTIVE);
        product.setCreatedAt(LocalDateTime.of(2026, 1, 6, 10, 30));

        // Act
        Map<String, Object> metadata = scanner.extractContextMetadata(product);

        // Assert
        assertThat(metadata).isNotNull();
        assertThat(metadata).containsKey("id");
        assertThat(metadata).containsKey("category");
        assertThat(metadata).containsKey("product_status");
        assertThat(metadata).containsKey("createdAt");

        assertThat(metadata.get("category")).isEqualTo("Electronics");
        assertThat(metadata.get("product_status")).isEqualTo(ProductStatus.ACTIVE);
        assertThat(metadata.get("createdAt")).isEqualTo("2026-01-06");  // Formatted
    }

    @Test
    @DisplayName("Should cache searchable fields at application level")
    void shouldCacheSearchableFields() {
        // Act - Call twice
        List<AnnotationFieldScanner.SearchableFieldMetadata> fields1 =
            scanner.getSearchableFields(TestProduct.class);
        List<AnnotationFieldScanner.SearchableFieldMetadata> fields2 =
            scanner.getSearchableFields(TestProduct.class);

        // Assert - Same instance (cached)
        assertThat(fields1).isSameAs(fields2);
        assertThat(fields1).hasSize(2);  // name, description
    }

    @Test
    @DisplayName("Should cache context fields at application level")
    void shouldCacheContextFields() {
        // Act - Call twice
        List<AnnotationFieldScanner.ContextFieldMetadata> fields1 =
            scanner.getContextFields(TestProduct.class);
        List<AnnotationFieldScanner.ContextFieldMetadata> fields2 =
            scanner.getContextFields(TestProduct.class);

        // Assert - Same instance (cached)
        assertThat(fields1).isSameAs(fields2);
        assertThat(fields1).hasSize(4);  // id, category, status, createdAt
    }

    @Test
    @DisplayName("Should apply preprocessing to searchable content")
    void shouldApplyPreprocessing() {
        // Arrange
        TestProduct product = new TestProduct();
        product.setName("  MacBook Pro M3  ");  // Extra spaces
        product.setDescription("POWERFUL LAPTOP");  // Uppercase

        // Act
        String searchableContent = scanner.extractSearchableContent(product);

        // Assert - Normalized (trimmed, lowercase)
        assertThat(searchableContent).isEqualTo("macbook pro m3 powerful laptop");
    }

    @Test
    @DisplayName("Should keep sanitize preprocessing character cleanup without PII service")
    void shouldSanitizeWithoutPiiService() {
        SanitizedProduct product = new SanitizedProduct();
        product.setNotes("VIP #A-1! Contact: support@example.com");

        String searchableContent = new AnnotationFieldScanner().extractSearchableContent(product);

        assertThat(searchableContent).isEqualTo("VIP A1 Contact supportexamplecom");
    }

    @Test
    @DisplayName("Should apply optional PII redaction before sanitize preprocessing cleanup")
    void shouldSanitizeWithPiiServiceWhenAvailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(new SimplePIIDetectionService());
        AnnotationFieldScanner scannerWithPii = new AnnotationFieldScanner(piiProvider);

        SanitizedProduct product = new SanitizedProduct();
        product.setNotes("Contact jane@example.com about order");

        String searchableContent = scannerWithPii.extractSearchableContent(product);

        assertThat(searchableContent).isEqualTo("Contact about order");
        assertThat(searchableContent).doesNotContain("jane", "example", "@");
    }

    @Test
    @DisplayName("Should handle null entity gracefully")
    void shouldHandleNullEntity() {
        // Act & Assert
        assertThatThrownBy(() -> scanner.extractSearchableContent(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entity cannot be null");

        assertThatThrownBy(() -> scanner.extractContextMetadata(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entity cannot be null");
    }

    @Test
    @DisplayName("Should return empty string for entity with no @AISearchable fields")
    void shouldReturnEmptyForNoSearchableFields() {
        // Arrange
        TestUser user = new TestUser();
        user.setEmail("test@example.com");

        // Act
        String searchableContent = scanner.extractSearchableContent(user);

        // Assert
        assertThat(searchableContent).isEmpty();
    }

    @Test
    @DisplayName("Should return empty map for entity with no @AIContext fields")
    void shouldReturnEmptyMapForNoContextFields() {
        // Arrange
        TestUserWithoutContext user = new TestUserWithoutContext();
        user.setName("John Doe");

        // Act
        Map<String, Object> metadata = scanner.extractContextMetadata(user);

        // Assert
        assertThat(metadata).isEmpty();
    }

    @Test
    @DisplayName("Should respect field weights in metadata")
    void shouldRespectFieldWeights() {
        // Arrange
        TestProduct product = new TestProduct();

        // Act
        List<AnnotationFieldScanner.SearchableFieldMetadata> fields =
            scanner.getSearchableFields(TestProduct.class);

        // Assert
        AnnotationFieldScanner.SearchableFieldMetadata nameField = fields.stream()
            .filter(f -> f.getField().getName().equals("name"))
            .findFirst()
            .orElseThrow();

        assertThat(nameField.getWeight()).isEqualTo(2.0);  // Higher weight for titles
    }

    @Test
    @DisplayName("Should sort context fields by priority")
    void shouldSortContextFieldsByPriority() {
        // Act
        List<AnnotationFieldScanner.ContextFieldMetadata> fields =
            scanner.getContextFields(TestProduct.class);

        // Assert - Sorted by priority (highest first)
        assertThat(fields).isNotEmpty();
        // Verify descending priority order
        for (int i = 0; i < fields.size() - 1; i++) {
            assertThat(fields.get(i).getPriority())
                .isGreaterThanOrEqualTo(fields.get(i + 1).getPriority());
        }
    }

    // ========== Test Entities ==========

    @AICapable(entityType = "product")
    static class TestProduct {
        @AISearchable(weight = 2.0, preprocessing = "normalize")
        private String name;

        @AISearchable(preprocessing = "normalize")
        private String description;

        @AIContext
        private UUID id;

        @AIContext(contextKey = "category", priority = 6)
        private String category;

        @AIContext(contextKey = "product_status", priority = 8)
        private ProductStatus status;

        @AIContext(format = "yyyy-MM-dd", priority = 5)
        private LocalDateTime createdAt;

        // Getters and setters
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public ProductStatus getStatus() { return status; }
        public void setStatus(ProductStatus status) { this.status = status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    @AICapable(entityType = "user")
    static class TestUser {
        @AIContext(contextKey = "email")
        private String email;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    static class TestUserWithoutContext {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @AICapable(entityType = "sanitized_product")
    static class SanitizedProduct {
        @AISearchable(preprocessing = "sanitize")
        private String notes;

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    enum ProductStatus {
        ACTIVE, INACTIVE, DISCONTINUED
    }
}
