package ai.fabric.relationship.service;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipPath;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataRelationshipTraversalServiceTest {

    @Mock
    private VectorDatabaseService vectorDatabaseService;

    private MetadataRelationshipTraversalService service;

    @BeforeEach
    void setUp() {
        service = new MetadataRelationshipTraversalService(vectorDatabaseService);
    }

    @Test
    void shouldReturnMatchingIdsWhenMetadataSatisfiesMergedFilters() {
        when(vectorDatabaseService.scan(any())).thenReturn(VectorScanPage.builder()
            .vectors(List.of(
                VectorRecord.builder()
                    .entityType("document")
                    .entityId("doc-1")
                    .metadata(Map.of(
                        "state", "published",
                        "creatorstatus", "approved",
                        "priority", 5
                    ))
                    .build(),
                VectorRecord.builder()
                    .entityType("document")
                    .entityId("doc-2")
                    .metadata(Map.of(
                        "state", "draft",
                        "creatorstatus", "denied",
                        "priority", 9
                    ))
                    .build()
            ))
            .hasMore(false)
            .nextCursor(null)
            .build());

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .directFilters(Map.of(
                "document",
                List.of(FilterCondition.builder()
                    .field("state")
                    .operator(FilterOperator.EQUALS)
                    .value("published")
                    .build())
            ))
            .relationshipPaths(List.of(RelationshipPath.builder()
                .fromEntityType("document")
                .toEntityType("user")
                .conditions(List.of(FilterCondition.builder()
                    .field("creator.status")
                    .operator(FilterOperator.EQUALS)
                    .value("approved")
                    .build()))
                .build()))
            .build();

        TraversalResult results = service.traverse(plan, JpqlQuery.builder().limit(5).build());

        assertThat(results.entityIds()).containsExactly("doc-1");
    }

    @Test
    void shouldRespectLimitWhenNoFiltersProvided() {
        when(vectorDatabaseService.scan(any())).thenReturn(VectorScanPage.builder()
            .vectors(List.of(
                VectorRecord.builder().entityType("document").entityId("doc-1").build(),
                VectorRecord.builder().entityType("document").entityId("doc-2").build()
            ))
            .hasMore(false)
            .nextCursor(null)
            .build());

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .build();

        TraversalResult results = service.traverse(plan, JpqlQuery.builder().limit(1).build());

        assertThat(results.entityIds()).containsExactly("doc-1");
    }

    @Test
    void shouldSkipEntitiesWhenMetadataDoesNotMatchFilters() {
        when(vectorDatabaseService.scan(any())).thenReturn(VectorScanPage.builder()
            .vectors(List.of(
                VectorRecord.builder().entityType("document").entityId("doc-1").metadata(null).build(),
                VectorRecord.builder().entityType("document").entityId("doc-2").metadata(Map.of()).build()
            ))
            .hasMore(false)
            .nextCursor(null)
            .build());

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .directFilters(Map.of(
                "document",
                List.of(FilterCondition.builder()
                    .field("status")
                    .operator(FilterOperator.EQUALS)
                    .value("active")
                    .build())
            ))
            .build();

        TraversalResult results = service.traverse(plan, null);

        assertThat(results.entityIds()).isEmpty();
    }
}

