package ai.fabric.vector.weaviate;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.GraphQL;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Get;
import io.weaviate.client.v1.graphql.query.argument.Bm25Argument;
import io.weaviate.client.v1.schema.Schema;
import io.weaviate.client.v1.schema.api.ClassGetter;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WeaviateLexicalSearchTest {
    private final WeaviateClient client = mock(WeaviateClient.class);
    private final Get query = mock(Get.class, Answers.RETURNS_SELF);
    private final ClassGetter getter = mock(ClassGetter.class, Answers.RETURNS_SELF);

    @Test
    void readinessInspectsActualContentIndexWithoutMutatingSchema() {
        var service = service();
        schema(true);
        assertThat(service.retrievalCapabilities("product").lexicalReady()).isTrue();
        schema(false);
        assertThat(service.retrievalCapabilities("product").lexicalReady()).isFalse();
        verify(client.schema(), never()).classCreator();
        verify(client.schema(), never()).propertyCreator();
    }

    @Test
    void lexicalUsesContentOnlyTenantAndIdenticalOwnerFilterWithoutDenseThreshold() {
        var service = service();
        String className = WeaviateVectorDatabaseService.scopedClassName("product", "scope");
        when(query.run()).thenReturn(new Result<>(200, GraphQLResponse.builder()
            .data(Map.of("Get", Map.of(className, List.of(
                row("owned", "{\"customerId\":\"owner\"}", "0.02"),
                row("foreign", "{\"customerId\":\"other\"}", "99")))))
            .build(), null));
        var response = service.keywordSearch("SKU-7", AISearchRequest.builder().entityType("product")
            .limit(3).threshold(.9).metadata(Map.of("customerId", "owner")).build());
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst()).containsEntry("id", "owned").containsEntry("score", .02);
        assertThat(response.getResults().getFirst()).doesNotContainKey("similarity");
        verify(query).withTenant("tenant-owner");
        verify(query).withLimit(3);
        var bm25 = ArgumentCaptor.forClass(Bm25Argument.class);
        verify(query).withBm25(bm25.capture());
        assertThat(bm25.getValue().getQuery()).isEqualTo("SKU-7");
        assertThat(bm25.getValue().getProperties()).containsExactly("content");
        verify(query).withWhere(any(WhereFilter.class));
        verify(query, never()).withNearVector(any());
    }

    @Test
    void malformedScoreIsNotFabricated() {
        var service = service();
        String name = WeaviateVectorDatabaseService.scopedClassName("product", "scope");
        when(query.run()).thenReturn(new Result<>(200, GraphQLResponse.builder()
            .data(Map.of("Get", Map.of(name, List.of(row("owned", "{}", "NaN"))))).build(), null));
        assertThatThrownBy(() -> service.keywordSearch("SKU-7", AISearchRequest.builder().entityType("product").build()))
            .hasMessageContaining("Invalid BM25 score");
    }

    @Test
    void missingScopeCannotSearchEveryKnownClass() {
        var service = service();
        assertThatThrownBy(() -> service.keywordSearch("SKU-7", AISearchRequest.builder().build()))
            .hasMessageContaining("scoped entity type");
        verifyNoInteractions(query);
    }

    @Test
    void authorizationFailureCannotBeMisreportedAsOptionalAvailabilityFailure() {
        var service = service();
        when(query.run()).thenReturn(new Result<>(403, null,
            io.weaviate.client.base.WeaviateErrorResponse.builder().code(403).message("denied").build()));
        assertThatThrownBy(() -> service.keywordSearch("SKU-7", AISearchRequest.builder().entityType("product").build()))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .isNotInstanceOf(ai.fabric.rag.LexicalSearchUnavailableException.class);
        when(query.run()).thenReturn(new Result<>(503, null,
            io.weaviate.client.base.WeaviateErrorResponse.builder().code(503).message("unavailable").build()));
        assertThatThrownBy(() -> service.keywordSearch("SKU-7", AISearchRequest.builder().entityType("product").build()))
            .isInstanceOf(ai.fabric.rag.LexicalSearchUnavailableException.class);
    }

    private WeaviateVectorDatabaseService service() {
        var config = new AIProviderConfig();
        config.getWeaviate().setEnabled(true);
        config.getWeaviate().setHost("localhost");
        config.getWeaviate().setClassPrefix("scope");
        config.getWeaviate().setNativeMultiTenancyEnabled(true);
        config.getWeaviate().setTenantName("tenant-owner");
        GraphQL graphQL = mock(GraphQL.class);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(query);
        Schema schema = mock(Schema.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(getter);
        return new WeaviateVectorDatabaseService(config, null, client);
    }

    private void schema(boolean searchable) {
        when(getter.run()).thenReturn(new Result<>(200, WeaviateClass.builder()
            .properties(List.of(Property.builder().name("content").dataType(List.of("text"))
                .indexSearchable(searchable).build())).build(), null));
    }

    private static Map<String, Object> row(String id, String metadata, String score) {
        return Map.of("entityType", "product", "entityId", id, "content", "SKU-7", "raw", metadata,
            "_additional", Map.of("id", "vector-" + id, "score", score));
    }
}
