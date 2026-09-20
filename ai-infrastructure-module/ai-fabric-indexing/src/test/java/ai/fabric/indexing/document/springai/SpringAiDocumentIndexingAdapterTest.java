package ai.fabric.indexing.document.springai;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.document.DocumentChunkIdentity;
import ai.fabric.indexing.document.DocumentEntityPolicyValidator;
import ai.fabric.indexing.document.DocumentManifestOperations;
import ai.fabric.indexing.document.DocumentMetadataNormalizer;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionManifest;
import ai.fabric.indexing.document.model.DocumentIngestionPlan;
import ai.fabric.indexing.document.model.DocumentIngestionWarningCode;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiDocumentIndexingAdapterTest {

    private static final Instant OCCURRED_AT =
        Instant.parse("2026-07-24T09:30:00Z");

    private AIEntityConfigurationLoader configurationLoader;
    private SpringAiDocumentIndexingAdapter adapter;

    @BeforeEach
    void setUp() {
        configurationLoader = mock(AIEntityConfigurationLoader.class);
        when(configurationLoader.getEntityConfig("kb"))
            .thenReturn(indexable("kb", false));
        when(configurationLoader.getEntityConfig("tenant-kb"))
            .thenReturn(indexable("tenant-kb", true));

        AIIndexingProperties.DocumentProperties properties =
            new AIIndexingProperties.DocumentProperties();
        DocumentChunkIdentity identity = new DocumentChunkIdentity();
        DocumentEntityPolicyValidator entityPolicy =
            new DocumentEntityPolicyValidator(configurationLoader);
        adapter = new SpringAiDocumentIndexingAdapter(
            properties,
            identity,
            new DocumentMetadataNormalizer(),
            entityPolicy,
            new DocumentManifestOperations(identity, entityPolicy)
        );
    }

    @Test
    void createsDeterministicCanonicalPlanWithProtectedMetadata() {
        Document source = Document.builder()
            .id("refund-policy")
            .text("Refunds are available within 30 days.")
            .metadata(Map.of(
                "documentTitle", " Refund policy ",
                "rank", 7,
                "secretToken", "do-not-store-or-warn",
                "sourceUrl", "https://example.test/private",
                "nested", Map.of("unsafe", "shape")
            ))
            .build();
        SpringAiDocumentIndexingOptions options = options("kb", 7)
            .sourceName("Policy Handbook")
            .visibility("internal")
            .allowedMetadataKey("rank")
            .metadata("locale", "en-GB")
            .build();

        DocumentIngestionPlan first = adapter.plan(List.of(source), options);
        DocumentIngestionPlan second = adapter.plan(List.of(source), options);

        assertThat(first).isEqualTo(second);
        assertThat(first.planId()).startsWith("aiplan-");
        assertThat(first.sourceVersion()).isEqualTo(7);
        assertThat(first.chunks()).hasSize(1);
        var chunk = first.chunks().getFirst();
        assertThat(chunk.entityId()).startsWith("aidoc-");
        assertThat(chunk.chunkId()).hasSize(40);
        assertThat(chunk.indexDocument().sourceVersion()).isEqualTo(7);
        assertThat(chunk.indexDocument().sourceOperation())
            .isEqualTo(AIProcessOperation.UPDATE);
        assertThat(chunk.indexDocument().vectorMetadata())
            .containsEntry("documentTitle", "Refund policy")
            .containsEntry("rank", 7)
            .containsEntry("locale", "en-GB")
            .containsEntry(DocumentMetadataKeys.SOURCE_ID, "policy-handbook")
            .containsEntry(DocumentMetadataKeys.SOURCE_VERSION, 7L)
            .containsEntry(DocumentMetadataKeys.SOURCE_NAME, "Policy Handbook")
            .containsEntry(DocumentMetadataKeys.DOCUMENT_ID, "refund-policy")
            .containsEntry(DocumentMetadataKeys.CHUNK_ID, chunk.chunkId())
            .containsEntry(DocumentMetadataKeys.CHUNK_INDEX, 0)
            .containsEntry(DocumentMetadataKeys.CHUNK_COUNT, 1)
            .containsEntry(DocumentMetadataKeys.VISIBILITY, "internal")
            .doesNotContainKeys("secretToken", "sourceUrl", "nested");
        assertThat(first.warnings())
            .extracting(warning -> warning.code())
            .containsOnly(DocumentIngestionWarningCode.METADATA_KEY_DROPPED);
        assertThat(first.warnings())
            .extracting(warning -> warning.field())
            .doesNotContain("do-not-store-or-warn", "https://example.test/private");
    }

    @Test
    void changesChunkIdentityWhenVersionOrContentChanges() {
        Document original = document("doc", "Version one");

        DocumentIngestionPlan versionOne = adapter.plan(
            List.of(original),
            options("kb", 1).build()
        );
        DocumentIngestionPlan versionTwo = adapter.plan(
            List.of(original),
            options("kb", 2).build()
        );
        DocumentIngestionPlan changedContent = adapter.plan(
            List.of(document("doc", "Version one changed")),
            options("kb", 1).build()
        );

        assertThat(versionOne.planId()).isNotEqualTo(versionTwo.planId());
        assertThat(versionOne.chunks().getFirst().entityId())
            .isNotEqualTo(versionTwo.chunks().getFirst().entityId())
            .isNotEqualTo(changedContent.chunks().getFirst().entityId());
    }

    @Test
    void createsDeterministicContentFreeManifestWithExactChunkIds() {
        DocumentIngestionPlan plan = adapter.plan(
            List.of(document("one", "First"), document("two", "Second")),
            options("kb", 3).build()
        );

        DocumentIngestionManifest first = adapter.manifest(plan);
        DocumentIngestionManifest second = adapter.manifest(plan);

        assertThat(first).isEqualTo(second);
        assertThat(first.manifestId()).startsWith("aimanifest-");
        assertThat(first.chunks())
            .extracting(chunk -> chunk.entityId())
            .containsExactlyElementsOf(
                plan.chunks().stream().map(chunk -> chunk.entityId()).toList()
            );
        assertThat(first.toString()).doesNotContain("First", "Second");
    }

    @Test
    void appliesTransformersBeforeCanonicalPlanning() {
        SpringAiDocumentIndexingOptions options = options("kb", 1)
            .addTransformer(documents -> documents.stream()
                .map(document -> Document.builder()
                    .id(document.getId())
                    .text(document.getText().toUpperCase())
                    .metadata(document.getMetadata())
                    .build())
                .toList())
            .build();

        DocumentIngestionPlan plan = adapter.plan(
            List.of(document("doc", "prepared content")),
            options
        );

        assertThat(plan.chunks().getFirst().indexDocument().semanticSearchText())
            .isEqualTo("PREPARED CONTENT");
    }

    @Test
    void rejectsProtectedMetadataOverrides() {
        SpringAiDocumentIndexingOptions options = options("kb", 1)
            .metadata(DocumentMetadataKeys.SOURCE_ID, "spoofed")
            .build();

        assertFailure(
            () -> adapter.plan(List.of(document("doc", "content")), options),
            DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED
        );
    }

    @Test
    void requiresTenantOnlyWhenTargetPolicyRequiresIt() {
        assertFailure(
            () -> adapter.plan(
                List.of(document("doc", "content")),
                options("tenant-kb", 1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED
        );

        DocumentIngestionPlan plan = adapter.plan(
            List.of(document("doc", "content")),
            options("tenant-kb", 1).tenantId("tenant-a").build()
        );

        assertThat(plan.chunks().getFirst().indexDocument().vectorMetadata())
            .containsEntry(DocumentMetadataKeys.TENANT_ID, "tenant-a");
    }

    @Test
    void rejectsUnknownAndDisabledEntityTypesBeforePlanning() {
        when(configurationLoader.getEntityConfig("archive"))
            .thenReturn(AIEntityConfig.builder()
                .entityType("archive")
                .indexing(AIEntityIndexingPolicy.builder()
                    .enabled(false)
                    .build())
                .build());

        assertFailure(
            () -> adapter.plan(
                List.of(document("doc", "content")),
                options("missing", 1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
        );
        assertFailure(
            () -> adapter.plan(
                List.of(document("doc", "content")),
                options("archive", 1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED
        );
    }

    @Test
    void enforcesDocumentChunkAndContentLimits() {
        assertFailure(
            () -> adapter.plan(
                List.of(document("one", "A"), document("two", "B")),
                options("kb", 1).maxDocuments(1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED
        );
        assertFailure(
            () -> adapter.plan(
                List.of(document("one", "A"), document("two", "B")),
                options("kb", 1).maxChunks(1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED
        );
        assertFailure(
            () -> adapter.plan(
                List.of(document("one", "123456789")),
                options("kb", 1)
                    .maxContentLength(8)
                    .maxTotalContentLength(8)
                    .build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED
        );
        assertFailure(
            () -> adapter.plan(
                List.of(document("one", "123456"), document("two", "789012")),
                options("kb", 1)
                    .maxContentLength(10)
                    .maxTotalContentLength(10)
                    .build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_LIMIT_EXCEEDED
        );
    }

    @Test
    void mapsReaderAndTransformerFailuresToSafeParseFailures() {
        assertFailure(
            () -> adapter.plan(
                () -> {
                    throw new IllegalStateException("sensitive parser detail");
                },
                options("kb", 1).build()
            ),
            DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED
        );

        SpringAiDocumentIndexingOptions transformerFailure = options("kb", 1)
            .addTransformer(documents -> {
                throw new IllegalStateException("sensitive transform detail");
            })
            .build();
        assertThatThrownBy(() -> adapter.plan(
            List.of(document("doc", "content")),
            transformerFailure
        ))
            .isInstanceOfSatisfying(DocumentIngestionException.class, exception -> {
                assertThat(exception.getCode())
                    .isEqualTo(DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED);
                assertThat(exception.getMessage())
                    .doesNotContain("sensitive transform detail");
            });
    }

    private SpringAiDocumentIndexingOptions.Builder options(
        String entityType,
        long sourceVersion
    ) {
        return SpringAiDocumentIndexingOptions.builder()
            .entityType(entityType)
            .sourceId("policy-handbook")
            .sourceVersion(sourceVersion)
            .operation(AIProcessOperation.UPDATE)
            .splitWithTokenTextSplitter(false)
            .occurredAt(OCCURRED_AT);
    }

    private Document document(String id, String content) {
        return Document.builder().id(id).text(content).build();
    }

    private AIEntityConfig indexable(String entityType, boolean tenantRequired) {
        AIEntityConfig.AIEntityConfigBuilder builder = AIEntityConfig.builder()
            .entityType(entityType)
            .indexing(AIEntityIndexingPolicy.builder().enabled(true).build());
        if (tenantRequired) {
            builder.metadataFields(List.of(AIMetadataField.builder()
                .name("tenantId")
                .required(true)
                .build()));
        }
        return builder.build();
    }

    private void assertFailure(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
        DocumentIngestionFailureCode code
    ) {
        assertThatThrownBy(callable)
            .isInstanceOfSatisfying(
                DocumentIngestionException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code)
            );
    }
}
