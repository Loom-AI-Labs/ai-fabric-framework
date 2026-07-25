package ai.fabric.indexing.projection;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIConfiguredEntityProjectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void buildsSeparateAllowlistedViewsForYamlOnlyIngress() {
        AIEntityConfig config = config(
            1_000,
            List.of(
                search(
                    "title",
                    Set.of(
                        AISearchDestination.SEMANTIC_SEARCH,
                        AISearchDestination.RAG_CONTEXT
                    ),
                    90,
                    true
                ),
                search(
                    "internalNotes",
                    Set.of(AISearchDestination.SEMANTIC_SEARCH),
                    10,
                    false
                )
            ),
            List.of(
                context(
                    "tenantId",
                    AIContextDataType.ID,
                    Set.of(AIContextDestination.VECTOR_METADATA),
                    true
                ),
                context(
                    "supportHint",
                    AIContextDataType.STRING,
                    Set.of(AIContextDestination.LLM_CONTEXT),
                    false
                ),
                context(
                    "publicStatus",
                    AIContextDataType.STRING,
                    Set.of(AIContextDestination.API_RESPONSE),
                    false
                )
            )
        );

        var document = service(null).project(
            config,
            "article-1",
            null,
            Map.of(
                "title", "  Reset   an account ",
                "internalNotes", "staff-only ranking text",
                "supportHint", "Ask for the verified email",
                "publicStatus", "PUBLISHED",
                "secret", "must never enter a projection"
            ),
            Map.of("tenantId", "tenant-a"),
            Map.of("_dataSyncSourceRecordId", "source-1"),
            7L,
            "trace-1",
            AIProcessOperation.UPDATE
        );

        assertThat(document.semanticSearchText()).isEqualTo("""
            title: Reset an account
            internalNotes: staff-only ranking text""");
        assertThat(document.ragContextText()).isEqualTo(
            "title: Reset an account"
        );
        assertThat(document.vectorMetadata())
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("_dataSyncSourceRecordId", "source-1")
            .doesNotContainKey("secret");
        assertThat(document.llmContext()).containsOnlyKeys("supportHint");
        assertThat(document.responseMetadata())
            .containsOnlyKeys("publicStatus");
        assertThat(document.sourceVersion()).isEqualTo(7L);
        assertThat(document.descriptorHash()).hasSize(64);
    }

    @Test
    void enforcesPriorityBudgetAndRequiredFieldsAfterPreprocessing() {
        AIEntityConfig config = config(
            32,
            List.of(
                search(
                    "requiredTitle",
                    Set.of(AISearchDestination.SEMANTIC_SEARCH),
                    100,
                    true
                ),
                search(
                    "optionalDetails",
                    Set.of(AISearchDestination.SEMANTIC_SEARCH),
                    1,
                    false
                )
            ),
            List.of()
        );

        var document = service(null).project(
            config,
            "p-1",
            null,
            Map.of(
                "requiredTitle", "Laptop",
                "optionalDetails", "This lower priority text is much too long"
            ),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        );

        assertThat(document.semanticSearchText())
            .startsWith("requiredTitle: Laptop")
            .hasSizeLessThanOrEqualTo(32);

        config.getSearchableFields().getFirst()
            .setPreprocessing(AISearchPreprocessing.CLEAN);

        assertThatThrownBy(() -> service(null).project(
            config,
            "p-2",
            null,
            Map.of("requiredTitle", "\u0007"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.UPDATE
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("REQUIRED_FIELD_EMPTY_AFTER_PREPROCESSING")
            .hasMessageNotContaining("\u0007");
    }

    @Test
    void requiredYamlProjectionCanExactlyFillItsConfiguredBudget() {
        AIEntityConfig config = config(
            8,
            List.of(search(
                "title",
                Set.of(AISearchDestination.SEMANTIC_SEARCH),
                100,
                true
            )),
            List.of()
        );

        var document = service(null).project(
            config,
            "exact-1",
            null,
            Map.of("title", "X"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        );

        assertThat(document.semanticSearchText()).isEqualTo("title: X");
    }

    @Test
    void appliesTypedContextFormatAndFailsClosedWithoutPiiService() {
        AIMetadataField amount = context(
            "amount",
            AIContextDataType.NUMBER,
            Set.of(AIContextDestination.API_RESPONSE),
            true
        );
        amount.setFormat("0.00");
        AIMetadataField email = context(
            "email",
            AIContextDataType.STRING,
            Set.of(AIContextDestination.VECTOR_METADATA),
            true
        );
        email.setSanitizePII(true);
        AIEntityConfig config = config(
            1_000,
            List.of(search(
                "title",
                Set.of(AISearchDestination.SEMANTIC_SEARCH),
                50,
                true
            )),
            List.of(amount, email)
        );

        assertThatThrownBy(() -> service(null).project(
            config,
            "invoice-1",
            null,
            Map.of("title", "Invoice", "amount", 12.5, "email", "x@example.com"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("PII_SERVICE_UNAVAILABLE")
            .hasMessageNotContaining("x@example.com");

        PIIDetectionService pii = mock(PIIDetectionService.class);
        when(pii.detectAndProcess("x@example.com")).thenReturn(
            PIIDetectionResult.builder()
                .piiDetected(true)
                .processedQuery("[EMAIL]")
                .build()
        );
        var document = service(pii).project(
            config,
            "invoice-1",
            null,
            Map.of("title", "Invoice", "amount", 12.5, "email", "x@example.com"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        );

        assertThat(document.responseMetadata()).containsEntry("amount", "12.50");
        assertThat(document.vectorMetadata()).containsEntry("email", "[EMAIL]");
    }

    @Test
    void formatsYamlDateContextDeterministicallyInUtc() {
        AIMetadataField publishedOn = context(
            "publishedOn",
            AIContextDataType.DATE,
            Set.of(AIContextDestination.VECTOR_METADATA),
            true
        );
        publishedOn.setFormat("yyyy-MM-dd");
        AIEntityConfig config = config(
            1_000,
            List.of(search(
                "title",
                Set.of(AISearchDestination.SEMANTIC_SEARCH),
                50,
                true
            )),
            List.of(publishedOn)
        );

        var document = service(null).project(
            config,
            "article-utc",
            null,
            Map.of(
                "title", "UTC date",
                "publishedOn", Instant.parse("2026-07-24T23:59:00Z")
            ),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        );

        assertThat(document.vectorMetadata())
            .containsEntry("publishedOn", "2026-07-24");
    }

    @Test
    void rejectsImplicitEnablementAndDuplicateProjectionFields() {
        AIEntityConfig disabled = config(
            1_000,
            List.of(search(
                "title",
                Set.of(AISearchDestination.SEMANTIC_SEARCH),
                50,
                true
            )),
            List.of()
        );
        disabled.getIndexing().setEnabled(null);

        assertThatThrownBy(() -> service(null).project(
            disabled,
            "p-1",
            null,
            Map.of("title", "Laptop"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("INDEXING_NOT_EXPLICITLY_ENABLED");

        AIEntityConfig duplicate = config(
            1_000,
            List.of(
                search(
                    "title",
                    Set.of(AISearchDestination.SEMANTIC_SEARCH),
                    50,
                    true
                ),
                search(
                    "TITLE",
                    Set.of(AISearchDestination.RAG_CONTEXT),
                    40,
                    false
                )
            ),
            List.of()
        );
        assertThatThrownBy(() -> service(null).project(
            duplicate,
            "p-1",
            null,
            Map.of("title", "Laptop"),
            Map.of(),
            Map.of(),
            null,
            null,
            AIProcessOperation.CREATE
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("DUPLICATE_SEARCHABLE_FIELD");
    }

    @Test
    void validatesYamlOnlyContractBeforeTheFirstSourceRecord() {
        AIEntityConfig valid = config(
            1_000,
            List.of(search(
                "title",
                Set.of(AISearchDestination.SEMANTIC_SEARCH),
                50,
                true
            )),
            List.of(context(
                "tenantId",
                AIContextDataType.ID,
                Set.of(AIContextDestination.VECTOR_METADATA),
                true
            ))
        );

        service(null).validateConfiguration(valid);

        valid.getSearchableFields().getFirst()
            .setDestinations(Set.of(AISearchDestination.RAG_CONTEXT));
        assertThatThrownBy(() -> service(null).validateConfiguration(valid))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("SEMANTIC_SEARCH_FIELD_REQUIRED");
    }

    private AIConfiguredEntityProjectionService service(
        PIIDetectionService piiService
    ) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (piiService != null) {
            factory.addBean("pii", piiService);
        }
        ObjectProvider<PIIDetectionService> provider =
            factory.getBeanProvider(PIIDetectionService.class);
        return new AIConfiguredEntityProjectionService(
            provider,
            new ObjectMapper(),
            CLOCK
        );
    }

    private AIEntityConfig config(
        int budget,
        List<AISearchableField> searchFields,
        List<AIMetadataField> metadataFields
    ) {
        return AIEntityConfig.builder()
            .entityType("configured-article")
            .indexing(AIEntityIndexingPolicy.builder()
                .enabled(true)
                .maxCharacters(budget)
                .build())
            .searchableFields(searchFields)
            .metadataFields(metadataFields)
            .build();
    }

    private AISearchableField search(
        String name,
        Set<AISearchDestination> destinations,
        int priority,
        boolean required
    ) {
        return AISearchableField.builder()
            .name(name)
            .destinations(destinations)
            .preprocessing(AISearchPreprocessing.NORMALIZE)
            .maxLength(-1)
            .priority(priority)
            .required(required)
            .build();
    }

    private AIMetadataField context(
        String name,
        AIContextDataType dataType,
        Set<AIContextDestination> destinations,
        boolean required
    ) {
        return AIMetadataField.builder()
            .name(name)
            .dataType(dataType)
            .destinations(destinations)
            .priority(50)
            .required(required)
            .build();
    }
}
