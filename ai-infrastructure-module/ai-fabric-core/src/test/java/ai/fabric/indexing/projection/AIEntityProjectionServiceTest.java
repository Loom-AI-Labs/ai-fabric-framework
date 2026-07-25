package ai.fabric.indexing.projection;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIEntityProjectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void createsSeparateOrderedSearchAndContextViews() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);
        ProjectionEntity entity = new ProjectionEntity(
            "p-1",
            "  High   performance laptop ",
            "Creator\u0007 notes",
            "tenant-a",
            "internal-only",
            7L
        );

        var document = service.project(entity, AIProcessOperation.UPDATE, " trace-1 ");

        assertThat(document.workType()).isEqualTo(AIIndexWorkType.UPSERT);
        assertThat(document.entityType()).isEqualTo("projection-product");
        assertThat(document.entityId()).isEqualTo("p-1");
        assertThat(document.semanticSearchText())
            .isEqualTo("title: High performance laptop");
        assertThat(document.ragContextText())
            .isEqualTo("""
                title: High performance laptop
                notes: Creator notes""");
        assertThat(document.vectorMetadata())
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("version", 7L)
            .doesNotContainKey("privateNote");
        assertThat(document.llmContext()).containsOnlyKeys("privateNote");
        assertThat(document.responseMetadata()).doesNotContainKey("privateNote");
        assertThat(document.sourceVersion()).isEqualTo(7L);
        assertThat(document.correlationId()).isEqualTo("trace-1");
        assertThat(document.occurredAt()).isEqualTo(Instant.parse("2026-07-24T12:00:00Z"));
        assertThat(new ObjectMapper().findAndRegisterModules()
            .valueToTree(document).toString())
            .doesNotContain("must-never-enter-indexing");
    }

    @Test
    void requiredSearchValueFailsWithoutIncludingRawDataInTheError() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);
        ProjectionEntity entity = new ProjectionEntity(
            "p-2",
            " ",
            "sensitive raw content",
            "tenant-a",
            null,
            1L
        );

        assertThatThrownBy(() -> service.project(
            entity,
            AIProcessOperation.CREATE,
            "trace"
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("REQUIRED_FIELD_MISSING")
            .hasMessageNotContaining("sensitive raw content");
    }

    @Test
    void sanitizesPiiAndFailsClosedWhenProviderThrows() {
        PIIDetectionService piiService = mock(PIIDetectionService.class);
        when(piiService.detectAndProcess("email test@example.com")).thenReturn(
            PIIDetectionResult.builder()
                .piiDetected(true)
                .processedQuery("email [EMAIL]")
                .build()
        );
        AIEntityProjectionService service = service(new MockEnvironment(), piiService);

        var document = service.project(
            new SanitizedProjection("s-1", "email test@example.com"),
            AIProcessOperation.CREATE,
            null
        );
        assertThat(document.semanticSearchText()).isEqualTo("text: email [EMAIL]");

        when(piiService.detectAndProcess("failure")).thenThrow(
            new IllegalStateException("raw provider detail")
        );
        assertThatThrownBy(() -> service.project(
            new SanitizedProjection("s-2", "failure"),
            AIProcessOperation.UPDATE,
            null
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("PII_PROCESSING_FAILED")
            .hasMessageNotContaining("raw provider detail");
    }

    @Test
    void deleteProjectionContainsNoEntityPayload() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);

        var document = service.projectDelete(
            ProjectionEntity.class,
            "p-9",
            AIProcessOperation.DELETE,
            "trace-delete"
        );

        assertThat(document.workType()).isEqualTo(AIIndexWorkType.DELETE);
        assertThat(document.semanticSearchText()).isNull();
        assertThat(document.ragContextText()).isNull();
        assertThat(document.vectorMetadata()).isEmpty();
        assertThat(document.llmContext()).isEmpty();
        assertThat(document.responseMetadata()).isEmpty();
    }

    @Test
    void convertsContextValuesToClassFreeJsonShapes() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);
        UUID accountId = UUID.fromString(
            "08a5608d-6c34-4c2a-8988-81b69509b8c3"
        );

        var document = service.project(
            new TypedContextProjection(
                "typed-1",
                "Typed projection",
                Status.ACTIVE,
                accountId,
                LocalDate.of(2026, 7, 24),
                List.of(Status.ACTIVE, Status.PAUSED),
                Map.of("status", Status.PAUSED)
            ),
            AIProcessOperation.CREATE,
            null
        );

        assertThat(document.vectorMetadata())
            .containsEntry("status", "ACTIVE")
            .containsEntry("accountId", accountId.toString())
            .containsEntry("effectiveDate", "2026-07-24")
            .containsEntry("states", List.of("ACTIVE", "PAUSED"))
            .containsEntry("details", Map.of("status", "PAUSED"));
    }

    @Test
    void rejectsBlankIdentityWithoutLeakingProjectedContent() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);

        assertThatThrownBy(() -> service.project(
            new BlankIdentityProjection(" ", "private source content"),
            AIProcessOperation.CREATE,
            null
        ))
            .isInstanceOf(AIProjectionValidationException.class)
            .hasMessageContaining("IDENTITY_MISSING")
            .hasMessageNotContaining("private source content");
    }

    @Test
    void requiredSearchProjectionCanExactlyFillItsConfiguredBudget() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "ai-entities.exact-budget.indexing.max-characters",
                "9"
            );
        AIEntityProjectionService service = service(environment, null);

        var document = service.project(
            new ExactBudgetProjection("exact-1", "abc"),
            AIProcessOperation.CREATE,
            null
        );

        assertThat(document.semanticSearchText()).isEqualTo("text: abc");
    }

    @Test
    void formatsLegacyJavaDateContextUsingItsDeclaredPattern() {
        AIEntityProjectionService service = service(new MockEnvironment(), null);

        var document = service.project(
            new LegacyDateProjection(
                "date-1",
                "Legacy date",
                Date.from(Instant.parse("2026-07-24T12:00:00Z"))
            ),
            AIProcessOperation.CREATE,
            null
        );

        assertThat(document.vectorMetadata())
            .containsEntry("publishedOn", "2026-07-24");
    }

    @Test
    void appliesSearchBoundsPriorityAndContextDestinationPolicy() {
        PIIDetectionService piiService = mock(PIIDetectionService.class);
        when(piiService.detectAndProcess("private@example.com")).thenReturn(
            PIIDetectionResult.builder()
                .piiDetected(true)
                .processedQuery("[EMAIL]")
                .build()
        );
        AIEntityProjectionService service = service(
            new MockEnvironment(),
            piiService
        );

        var document = service.project(
            new CompleteAnnotationProjection(
                "complete-1",
                "second",
                "abcdefgh",
                "low",
                "high",
                "private@example.com"
            ),
            AIProcessOperation.CREATE,
            null
        );

        assertThat(document.semanticSearchText()).isEqualTo("""
            high: abcde
            low: second""");
        assertThat(new ArrayList<>(document.llmContext().keySet()))
            .containsExactly("highHint", "lowHint");
        assertThat(document.llmContext().get("highHint").description())
            .isEqualTo("High-priority guidance");
        assertThat(document.vectorMetadata())
            .containsEntry("safeEmail", "[EMAIL]")
            .doesNotContainKeys("highHint", "lowHint");
    }

    private AIEntityProjectionService service(
        MockEnvironment environment,
        PIIDetectionService piiService
    ) {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(environment);
        loader.loadConfiguration();
        ObjectProvider<PIIDetectionService> provider = provider(
            PIIDetectionService.class,
            piiService
        );
        ObjectMapper objectMapper = new ObjectMapper();
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            loader,
            List.of(),
            List.of(),
            provider,
            objectMapper
        );
        return new AIEntityProjectionService(registry, provider, objectMapper, CLOCK);
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (value != null) {
            factory.addBean("value", value);
        }
        return factory.getBeanProvider(type);
    }

    @AICapable(entityType = "projection-product")
    static class ProjectionEntity {
        @AIIdentity
        String id;

        @AISearchable(
            name = "title",
            destinations = {
                AISearchDestination.SEMANTIC_SEARCH,
                AISearchDestination.RAG_CONTEXT
            },
            priority = 100,
            required = true
        )
        String title;

        @AISearchable(
            name = "notes",
            destinations = {AISearchDestination.RAG_CONTEXT},
            preprocessing = AISearchPreprocessing.CLEAN,
            priority = 10
        )
        String notes;

        @AIContext(
            key = "tenantId",
            dataType = AIContextDataType.ID,
            destinations = {AIContextDestination.VECTOR_METADATA},
            required = true,
            priority = 100
        )
        String tenantId;

        @AIContext(
            key = "privateNote",
            destinations = {AIContextDestination.LLM_CONTEXT},
            description = "Private prompt context only",
            priority = 50
        )
        String privateNote;

        @AIContext(
            key = "version",
            dataType = AIContextDataType.NUMBER,
            destinations = {AIContextDestination.VECTOR_METADATA},
            priority = 90
        )
        Long version;

        String excludedSecret = "must-never-enter-indexing";

        ProjectionEntity(
            String id,
            String title,
            String notes,
            String tenantId,
            String privateNote,
            Long version
        ) {
            this.id = id;
            this.title = title;
            this.notes = notes;
            this.tenantId = tenantId;
            this.privateNote = privateNote;
            this.version = version;
        }
    }

    @AICapable(entityType = "sanitized-projection")
    static class SanitizedProjection {
        @AIIdentity
        String id;

        @AISearchable(
            preprocessing = AISearchPreprocessing.SANITIZE,
            required = true
        )
        String text;

        SanitizedProjection(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    @AICapable(entityType = "blank-identity-projection")
    static class BlankIdentityProjection {
        @AIIdentity
        String id;

        @AISearchable(required = true)
        String text;

        BlankIdentityProjection(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    @AICapable(entityType = "exact-budget")
    static class ExactBudgetProjection {
        @AIIdentity
        String id;

        @AISearchable(required = true)
        String text;

        ExactBudgetProjection(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    @AICapable(entityType = "legacy-date")
    static class LegacyDateProjection {
        @AIIdentity
        String id;

        @AISearchable(required = true)
        String title;

        @AIContext(
            key = "publishedOn",
            format = "yyyy-MM-dd",
            destinations = AIContextDestination.VECTOR_METADATA
        )
        Date publishedOn;

        LegacyDateProjection(String id, String title, Date publishedOn) {
            this.id = id;
            this.title = title;
            this.publishedOn = publishedOn;
        }
    }

    @AICapable(entityType = "complete-annotation")
    static class CompleteAnnotationProjection {
        @AIIdentity
        String id;

        @AISearchable(name = "low", priority = 10, required = true)
        String lowSearch;

        @AISearchable(
            name = "high",
            preprocessing = AISearchPreprocessing.NONE,
            maxLength = 5,
            priority = 90,
            required = true
        )
        String highSearch;

        @AIContext(
            key = "lowHint",
            dataType = AIContextDataType.STRING,
            destinations = AIContextDestination.LLM_CONTEXT,
            description = "Low-priority guidance",
            priority = 10,
            required = true
        )
        String lowHint;

        @AIContext(
            key = "highHint",
            dataType = AIContextDataType.STRING,
            destinations = AIContextDestination.LLM_CONTEXT,
            description = "High-priority guidance",
            priority = 90,
            required = true
        )
        String highHint;

        @AIContext(
            key = "safeEmail",
            dataType = AIContextDataType.STRING,
            destinations = AIContextDestination.VECTOR_METADATA,
            sanitizePII = true
        )
        String email;

        CompleteAnnotationProjection(
            String id,
            String lowSearch,
            String highSearch,
            String lowHint,
            String highHint,
            String email
        ) {
            this.id = id;
            this.lowSearch = lowSearch;
            this.highSearch = highSearch;
            this.lowHint = lowHint;
            this.highHint = highHint;
            this.email = email;
        }
    }

    enum Status {
        ACTIVE,
        PAUSED
    }

    @AICapable(entityType = "typed-context")
    static class TypedContextProjection {
        @AIIdentity
        String id;

        @AISearchable(required = true)
        String title;

        @AIContext(
            key = "status",
            dataType = AIContextDataType.ENUM,
            destinations = AIContextDestination.VECTOR_METADATA
        )
        Status status;

        @AIContext(
            key = "accountId",
            dataType = AIContextDataType.ID,
            destinations = AIContextDestination.VECTOR_METADATA
        )
        UUID accountId;

        @AIContext(
            key = "effectiveDate",
            dataType = AIContextDataType.DATE,
            destinations = AIContextDestination.VECTOR_METADATA
        )
        LocalDate effectiveDate;

        @AIContext(
            key = "states",
            dataType = AIContextDataType.JSON,
            destinations = AIContextDestination.VECTOR_METADATA
        )
        List<Status> states;

        @AIContext(
            key = "details",
            dataType = AIContextDataType.JSON,
            destinations = AIContextDestination.VECTOR_METADATA
        )
        Map<String, Status> details;

        TypedContextProjection(
            String id,
            String title,
            Status status,
            UUID accountId,
            LocalDate effectiveDate,
            List<Status> states,
            Map<String, Status> details
        ) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.accountId = accountId;
            this.effectiveDate = effectiveDate;
            this.states = states;
            this.details = details;
        }
    }
}
