package ai.fabric.relationship.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.RAGResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultRelationshipQueryDocumentMapperTest {

    @Test
    void shouldMapConfiguredSearchableContentAndMetadata() {
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        when(configurationLoader.getEntityConfig("product")).thenReturn(AIEntityConfig.builder()
            .searchableFields(List.of(
                AISearchableField.builder().name("name").includeInRAG(true).build(),
                AISearchableField.builder().name("brand.name").includeInRAG(true).build(),
                AISearchableField.builder().name("emptyValue").includeInRAG(true).build()
            ))
            .metadataFields(List.of(
                AIMetadataField.builder().name("brand.name").build(),
                AIMetadataField.builder().name("price").build()
            ))
            .build());

        DefaultRelationshipQueryDocumentMapper mapper =
            new DefaultRelationshipQueryDocumentMapper(null, configurationLoader);

        Optional<RAGResponse.RAGDocument> mapped = mapper.map(
            "product",
            new Product("Blue Runner", new Brand("Nike"), 85),
            "product-1"
        );

        assertThat(mapped).isPresent();
        assertThat(mapped.get().getContent()).isEqualTo("Blue Runner Nike");
        assertThat(mapped.get().getMetadata())
            .containsEntry("brand", "Nike")
            .containsEntry("price", 85);
    }

    @Test
    void shouldReturnEmptyWhenConfigCannotResolveAnyFields() {
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        when(configurationLoader.getEntityConfig("product")).thenThrow(new IllegalStateException("loader down"));

        DefaultRelationshipQueryDocumentMapper mapper =
            new DefaultRelationshipQueryDocumentMapper(null, configurationLoader);

        Optional<RAGResponse.RAGDocument> mapped = mapper.map(
            "product",
            new Product("Blue Runner", new Brand("Nike"), 85),
            "product-1"
        );

        assertThat(mapped).isEmpty();
    }

    private record Product(String name, Brand brand, int price) {
        String emptyValue() {
            return " ";
        }
    }

    private record Brand(String name) {
    }
}
