package ai.fabric.datasync.normalize;

import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSyncEntityNormalizerTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void projectUpsertBuildsCanonicalAllowlistedDocument() {
        AIDataSyncProperties properties = new AIDataSyncProperties();
        DataSyncEntityNormalizer normalizer = normalizer(properties);

        AIEntityConfig config = config(
            "product",
            List.of(
                searchable("title"),
                searchable("description")
            ),
            List.of(AIMetadataField.builder()
                .name("price")
                .dataType(AIContextDataType.NUMBER)
                .destinations(Set.of(AIContextDestination.VECTOR_METADATA))
                .priority(50)
                .required(true)
                .sanitizePII(false)
                .build())
        );

        var document = normalizer.projectUpsert(
            config,
            "product",
            "p1",
            null,
            Map.of(
                "title", "Sony WH-1000XM5",
                "description", "Noise cancelling headphones",
                "price", 399,
                "secret", "must not be projected"
            ),
            Map.of("locale", "en_US"),
            Map.of("_dataSyncTargetId", "p1"),
            7L,
            "request-1"
        );

        assertThat(document.workType()).isEqualTo(AIIndexWorkType.UPSERT);
        assertThat(document.semanticSearchText())
            .contains("title: Sony WH-1000XM5")
            .contains("description: Noise cancelling headphones");
        assertThat(document.ragContextText())
            .contains("title: Sony WH-1000XM5");
        assertThat(document.vectorMetadata())
            .containsEntry("price", 399)
            .containsEntry("_dataSyncTargetId", "p1")
            .doesNotContainKeys("locale", "secret");
        assertThat(document.sourceVersion()).isEqualTo(7L);
        assertThat(document.correlationId()).isEqualTo("request-1");
    }

    @Test
    void projectUpsertRejectsProjectedContentBeyondIngressLimit() {
        AIDataSyncProperties properties = new AIDataSyncProperties();
        properties.setMaxContentChars(10);

        AIEntityConfig config = config(
            "doc",
            List.of(searchable("content")),
            List.of()
        );

        assertThatThrownBy(() -> normalizer(properties).projectUpsert(
            config,
            "doc",
            "d1",
            "01234567890",
            null,
            null,
            Map.of(),
            null,
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxContentChars");
    }

    @Test
    void projectDeleteUsesCanonicalDeleteDocument() {
        AIEntityConfig config = config(
            "product",
            List.of(searchable("content")),
            List.of()
        );

        var document = normalizer(new AIDataSyncProperties()).projectDelete(
            config,
            "product",
            "p1",
            "request-delete"
        );

        assertThat(document.workType()).isEqualTo(AIIndexWorkType.DELETE);
        assertThat(document.entityType()).isEqualTo("product");
        assertThat(document.entityId()).isEqualTo("p1");
        assertThat(document.semanticSearchText()).isNull();
        assertThat(document.vectorMetadata()).isEmpty();
    }

    @Test
    void rejectsVectorSpaceThatDoesNotMatchConfiguredEntityType() {
        AIEntityConfig config = config(
            "product",
            List.of(searchable("content")),
            List.of()
        );

        assertThatThrownBy(() -> normalizer(new AIDataSyncProperties())
            .projectUpsert(
                config,
                "other",
                "p1",
                "content",
                null,
                null,
                Map.of(),
                null,
                null
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configured entityType");
    }

    private DataSyncEntityNormalizer normalizer(
        AIDataSyncProperties properties
    ) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        ObjectProvider<PIIDetectionService> piiProvider =
            factory.getBeanProvider(PIIDetectionService.class);
        AIConfiguredEntityProjectionService projectionService =
            new AIConfiguredEntityProjectionService(
                piiProvider,
                new ObjectMapper(),
                CLOCK
            );
        return new DataSyncEntityNormalizer(properties, projectionService);
    }

    private AIEntityConfig config(
        String entityType,
        List<AISearchableField> searchableFields,
        List<AIMetadataField> metadataFields
    ) {
        return AIEntityConfig.builder()
            .entityType(entityType)
            .indexing(AIEntityIndexingPolicy.builder()
                .enabled(true)
                .maxCharacters(8_000)
                .build())
            .searchableFields(searchableFields)
            .metadataFields(metadataFields)
            .build();
    }

    private AISearchableField searchable(String name) {
        return AISearchableField.builder()
            .name(name)
            .destinations(Set.of(
                AISearchDestination.SEMANTIC_SEARCH,
                AISearchDestination.RAG_CONTEXT
            ))
            .preprocessing(AISearchPreprocessing.NORMALIZE)
            .maxLength(-1)
            .priority(50)
            .required(true)
            .build();
    }
}
