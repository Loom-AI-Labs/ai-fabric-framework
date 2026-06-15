package ai.fabric.relationship.action;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.service.ReliableRelationshipQueryService;
import ai.fabric.relationship.spi.RelationshipQueryAccessControlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelationshipQueryActionHandlerTest {

    @Mock
    private ReliableRelationshipQueryService queryService;

    @Mock
    private RelationshipQueryAccessControlPolicy accessControlPolicy;

    @InjectMocks
    private RelationshipQueryActionHandler handler;

    @BeforeEach
    void setUp() {
        when(accessControlPolicy.canExecuteRelationshipQueries(any())).thenReturn(true);
        when(accessControlPolicy.canQueryEntityType(any(), anyString())).thenReturn(true);
        when(accessControlPolicy.getAllowedEntityTypes(any())).thenReturn(List.of());
    }

    @Test
    void actionAnnotationShouldDeclareRelationshipQuery() {
        AIAction annotation = RelationshipQueryActionHandler.class.getAnnotation(AIAction.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("relationship_query");
        assertThat(annotation.requiresConfirmation()).isFalse();
    }

    @Test
    void executeShouldPassQueryOptionsAndSucceed() {
        RAGResponse response = RAGResponse.builder()
            .success(true)
            .totalResults(3)
            .processingTimeMs(123L)
            .build();
        when(queryService.execute(any(), anyList(), any(QueryOptions.class))).thenReturn(response);

        ActionResult result = handler.execute(
            "Find premium users",
            List.of("user"),
            25,
            ReturnMode.FULL,
            0.42,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("Found");

        ArgumentCaptor<QueryOptions> optionsCaptor = ArgumentCaptor.forClass(QueryOptions.class);
        verify(queryService).execute(eq("Find premium users"), eq(List.of("user")), optionsCaptor.capture());

        QueryOptions options = optionsCaptor.getValue();
        assertThat(options.getLimit()).isEqualTo(25);
        assertThat(options.getReturnMode()).isEqualTo(ReturnMode.FULL);
        assertThat(options.getSimilarityThreshold()).isEqualTo(0.42);
    }

    @Test
    void executeShouldAllowAutoDetectWhenEntityTypesEmpty() {
        when(accessControlPolicy.getAllowedEntityTypes(argThat(subject("user-123"))))
            .thenReturn(List.of("document", "product"));

        when(queryService.execute(any(), anyList(), any(QueryOptions.class)))
            .thenReturn(RAGResponse.builder().success(true).build());

        ActionResult result = handler.execute(
            "Find documents about onboarding",
            List.of(),
            null,
            null,
            null,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isTrue();
        verify(accessControlPolicy).getAllowedEntityTypes(argThat(subject("user-123")));
        verify(queryService).execute(
            eq("Find documents about onboarding"),
            eq(List.of("document", "product")),
            any(QueryOptions.class)
        );
    }

    @Test
    void executeShouldFailWhenQueryServiceThrows() {
        when(queryService.execute(any(), anyList(), any(QueryOptions.class)))
            .thenThrow(new RuntimeException("boom"));

        ActionResult result = handler.execute(
            "Find users",
            List.of("user"),
            null,
            null,
            null,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("EXECUTION_FAILED");
    }

    @Test
    void allowedRequiresUserId() {
        assertThat(handler.allowed(actionContext(null))).isFalse();
        assertThat(handler.allowed(actionContext(""))).isFalse();
        assertThat(handler.allowed(actionContext("   "))).isFalse();
    }

    @Test
    void allowedDelegatesToAccessControlPolicy() {
        when(accessControlPolicy.canExecuteRelationshipQueries(argThat(subject("user-123")))).thenReturn(true);
        when(accessControlPolicy.canExecuteRelationshipQueries(argThat(subject("user-456")))).thenReturn(false);

        assertThat(handler.allowed(actionContext("user-123"))).isTrue();
        assertThat(handler.allowed(actionContext("user-456"))).isFalse();

        verify(accessControlPolicy).canExecuteRelationshipQueries(argThat(subject("user-123")));
        verify(accessControlPolicy).canExecuteRelationshipQueries(argThat(subject("user-456")));
    }

    @Test
    void executeShouldDenyWhenSomeEntityTypesNotAllowed() {
        when(accessControlPolicy.canQueryEntityType(argThat(subject("user-123")), eq("user"))).thenReturn(true);
        when(accessControlPolicy.canQueryEntityType(argThat(subject("user-123")), eq("order"))).thenReturn(false);

        ActionResult result = handler.execute(
            "Find users and orders",
            List.of("user", "order"),
            null,
            null,
            null,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(result.getMessage()).contains("Access denied");
        Map<String, Object> data = result.getData().toMap();
        assertThat(data).containsKeys("requestedEntityTypes", "allowedEntityTypes", "deniedEntityTypes");
        assertThat(data.get("deniedEntityTypes")).isEqualTo(List.of("order"));

        verify(queryService, never()).execute(any(), anyList(), any(QueryOptions.class));
    }

    @Test
    void executeShouldDenyWhenNoEntityTypesAllowed() {
        when(accessControlPolicy.canQueryEntityType(argThat(subject("user-123")), eq("user"))).thenReturn(false);
        when(accessControlPolicy.canQueryEntityType(argThat(subject("user-123")), eq("order"))).thenReturn(false);

        ActionResult result = handler.execute(
            "Find users and orders",
            List.of("user", "order"),
            null,
            null,
            null,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(result.getMessage()).contains("Access denied");

        verify(queryService, never()).execute(any(), anyList(), any(QueryOptions.class));
    }

    @Test
    void executeShouldUseAllowedEntityTypesWhenEmptyListProvided() {
        when(accessControlPolicy.getAllowedEntityTypes(argThat(subject("user-123"))))
            .thenReturn(List.of("user", "product"));

        when(queryService.execute(any(), anyList(), any(QueryOptions.class)))
            .thenReturn(RAGResponse.builder().success(true).build());

        ActionResult result = handler.execute(
            "Find stuff",
            List.of(),
            null,
            null,
            null,
            actionContext("user-123")
        );

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<List<String>> entityTypesCaptor = ArgumentCaptor.forClass(List.class);
        verify(queryService).execute(any(), entityTypesCaptor.capture(), any(QueryOptions.class));
        assertThat(entityTypesCaptor.getValue()).containsExactly("user", "product");
    }

    @Test
    void buildFactsShouldExposeCompactDocumentMetadataForGeneration() {
        RAGResponse.RAGDocument document = RAGResponse.RAGDocument.builder()
            .id("commerce://resource/Product/1")
            .title("The Collection Snowboard: Liquid")
            .type("product")
            .content("The Collection Snowboard: Liquid")
            .metadata(Map.of(
                "handle", "the-collection-snowboard-liquid",
                "vendor", "Hydrogen Vendor",
                "productType", "snowboard",
                "available", true,
                "price", "749.95",
                "inventoryQuantity", 50,
                "primarySku", "",
                "storefrontUrl", "https://demo-store.example.com/products/the-collection-snowboard-liquid",
                "raw", "large internal payload"
            ))
            .build();
        when(queryService.execute(any(), anyList(), any(QueryOptions.class)))
            .thenReturn(RAGResponse.builder()
                .success(true)
                .totalResults(1)
                .returnedResults(1)
                .entityType("product")
                .documents(List.of(document))
                .build());

        ActionResult result = handler.execute(
            "Find similar available snowboards under 800 dollars",
            List.of("product"),
            10,
            ReturnMode.FULL,
            null,
            actionContext("user-123")
        );

        Map<String, Object> facts = handler.buildFacts(result, actionContext("user-123"));

        assertThat(facts)
            .containsEntry("success", true)
            .containsEntry("totalResults", 1)
            .containsEntry("returnedResults", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) facts.get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst())
            .containsEntry("title", "The Collection Snowboard: Liquid")
            .containsEntry("price", "749.95")
            .containsEntry("available", true)
            .containsEntry("inventoryQuantity", 50)
            .doesNotContainKey("handle")
            .doesNotContainKey("sourceUrl")
            .doesNotContainKey("id")
            .doesNotContainKey("content")
            .doesNotContainKey("raw");
    }

    private static ActionContext actionContext(String userId) {
        OrchestrationContext context = userId == null
            ? OrchestrationContext.builder().build()
            : OrchestrationContext.builder()
                .userId(userId)
                .metadata(Map.of(
                    OrchestrationContextMetadataKeys.SUBJECT_ID, userId,
                    OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER"))
                .build();
        return new ActionContext(context, null);
    }

    private static AIAccessSubjectContext authContext(String userId) {
        return AIAccessSubjectContext.builder()
            .subjectId(userId)
            .subjectType("END_USER")
            .grantedScopes(List.of())
            .audiences(List.of())
            .build();
    }

    private static org.mockito.ArgumentMatcher<AIAccessSubjectContext> subject(String userId) {
        AIAccessSubjectContext expected = authContext(userId);
        return actual -> expected.equals(normalize(actual));
    }

    private static AIAccessSubjectContext normalize(AIAccessSubjectContext authContext) {
        if (authContext == null) {
            return null;
        }
        String subjectId = authContext.getSubjectId();
        if ((subjectId == null || subjectId.isBlank()) && authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            subjectId = authContext.getSessionId();
        }
        return authContext(subjectId);
    }
}
