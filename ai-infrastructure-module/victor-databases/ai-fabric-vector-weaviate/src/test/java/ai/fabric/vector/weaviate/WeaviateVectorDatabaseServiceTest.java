package ai.fabric.vector.weaviate;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.vector.VectorProviderMetrics;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.base.WeaviateErrorMessage;
import io.weaviate.client.base.WeaviateErrorResponse;
import io.weaviate.client.v1.data.Data;
import io.weaviate.client.v1.data.api.ObjectCreator;
import io.weaviate.client.v1.data.api.ObjectDeleter;
import io.weaviate.client.v1.data.api.ObjectsGetter;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.GraphQL;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Aggregate;
import io.weaviate.client.v1.graphql.query.Get;
import io.weaviate.client.v1.schema.Schema;
import io.weaviate.client.v1.schema.api.ClassCreator;
import io.weaviate.client.v1.schema.api.ClassGetter;
import io.weaviate.client.v1.schema.api.PropertyCreator;
import io.weaviate.client.v1.schema.api.TenantsCreator;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.Tenant;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeaviateVectorDatabaseServiceTest {

    @Test
    void scopedClassNamePrependsConfiguredPrefix() {
        assertThat(WeaviateVectorDatabaseService.scopedClassName("product", "customer_acme"))
            .startsWith("CustomerAcme_")
            .contains("_Product_");
    }

    @Test
    void constructorRejectsNativeMultiTenancyWithoutTenantName() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setNativeMultiTenancyEnabled(true);
        weaviate.setHost("example.weaviate.cloud");

        Assertions.assertThrows(
            ai.fabric.exception.AIServiceException.class,
            () -> new WeaviateVectorDatabaseService(config, null, mock(WeaviateClient.class))
        );
    }

    @Test
    void getVectorCountReturnsZeroWhenWeaviateClassDoesNotExistYet() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            404,
            null,
            WeaviateErrorResponse.builder()
                .code(404)
                .message("class not found")
                .error(List.of(WeaviateErrorMessage.builder().message("class not found").build()))
                .build()
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThat(service.getVectorCountByEntityType("product")).isZero();
    }

    @Test
    void getVectorCountUsesNativeAggregateCount() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");

        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className(className).build(),
            null
        ));

        GraphQL graphQL = mock(GraphQL.class);
        Aggregate aggregate = mock(Aggregate.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.aggregate()).thenReturn(aggregate);
        when(aggregate.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Aggregate", Map.of(className, List.of(Map.of("meta", Map.of("count", 42))))))
                .build(),
            null
        ));

        Data data = mock(Data.class);
        when(client.data()).thenReturn(data);

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThat(service.getVectorCountByEntityType("product")).isEqualTo(42L);
        verify(graphQL).aggregate();
        verify(data, never()).objectsGetter();
    }

    @Test
    void getVectorCountAppliesNativeTenantToAggregateQuery() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("tenant.weaviate.local");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");
        weaviate.setTenantName("tenant-retail");
        weaviate.setNativeMultiTenancyEnabled(true);

        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className(className).build(),
            null
        ));

        GraphQL graphQL = mock(GraphQL.class);
        Aggregate aggregate = mock(Aggregate.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.aggregate()).thenReturn(aggregate);
        when(aggregate.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Aggregate", Map.of(className, List.of(Map.of("meta", Map.of("count", "17"))))))
                .build(),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThat(service.getVectorCountByEntityType("product")).isEqualTo(17L);
        verify(aggregate).withClassName(className);
        verify(aggregate).withTenant("tenant-retail");
    }

    @Test
    void getVectorCountRecordsDiagnosticsWhenAggregateCountFallsBack() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");
        mockExistingClass(client, className);

        GraphQL graphQL = mock(GraphQL.class);
        Aggregate aggregate = mock(Aggregate.class, Answers.RETURNS_SELF);
        Get firstPage = mock(Get.class, Answers.RETURNS_SELF);
        Get secondPage = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.aggregate()).thenReturn(aggregate);
        when(graphQL.get()).thenReturn(firstPage, secondPage);
        when(aggregate.run()).thenReturn(new Result<>(
            422,
            null,
            WeaviateErrorResponse.builder()
                .code(422)
                .message("Cannot query field \"Aggregate\" on type \"GraphQL\"")
                .error(List.of(WeaviateErrorMessage.builder()
                    .message("Aggregate count unsupported: cannot query field \"Aggregate\"")
                    .build()))
                .build()
        ));
        when(firstPage.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of(className, scanRows(0, 501))))
                .build(),
            null
        ));
        when(secondPage.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of(className, scanRows(500, 2))))
                .build(),
            null
        ));

        Data data = mock(Data.class);
        when(client.data()).thenReturn(data);

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);

        try {
            assertThat(service.getVectorCountByEntityType("product")).isEqualTo(502L);
            verify(graphQL).aggregate();
            verify(graphQL, times(2)).get();
            verify(firstPage).withLimit(501);
            verify(firstPage).withOffset(0);
            verify(secondPage).withOffset(500);
            verify(data, never()).objectsGetter();
            assertThat(service.adminDiagnostics())
                .containsEntry("countMode", "native-aggregate-with-safe-fallback")
                .containsEntry("aggregateCountFallbacks", Map.of(className, 1));
            assertThat(service.adminDiagnostics().get("aggregateCountFallbackReasons"))
                .isInstanceOfSatisfying(Map.class, reasons ->
                    assertThat(reasons.get(className)).asString().contains("cannot query field"));
            assertThat(registry.counter(
                VectorProviderMetrics.FALLBACK_COUNTER,
                "provider", "weaviate",
                "operation", "count",
                "reason", "aggregate_unsupported"
            ).count()).isEqualTo(1.0d);
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    private static List<Map<String, Object>> scanRows(int startInclusive, int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int index = startInclusive; index < startInclusive + count; index++) {
            String id = "vector-" + index;
            rows.add(Map.of(
                "entityType", "product",
                "entityId", "p-" + index,
                "raw", "{}",
                "_additional", Map.of("id", id)
            ));
        }
        return rows;
    }

    @Test
    void storeVectorCreatesClassTenantMetadataPropertyAndUpsertsObject() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("tenant.weaviate.local");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");
        weaviate.setClassPrefix("customer_acme");
        weaviate.setTenantName("tenant-retail");
        weaviate.setNativeMultiTenancyEnabled(true);

        String className = WeaviateVectorDatabaseService.scopedClassName("product", "customer_acme");
        String vectorId = UUID.nameUUIDFromBytes("product::p-1".getBytes(StandardCharsets.UTF_8)).toString();

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        ClassCreator classCreator = mock(ClassCreator.class, Answers.RETURNS_SELF);
        TenantsCreator tenantsCreator = mock(TenantsCreator.class, Answers.RETURNS_SELF);
        PropertyCreator propertyCreator = mock(PropertyCreator.class, Answers.RETURNS_SELF);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(schema.classCreator()).thenReturn(classCreator);
        when(schema.tenantsCreator()).thenReturn(tenantsCreator);
        when(schema.propertyCreator()).thenReturn(propertyCreator);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(
            new Result<>(
                404,
                null,
                WeaviateErrorResponse.builder()
                    .code(404)
                    .message("class not found")
                    .error(List.of(WeaviateErrorMessage.builder().message("class not found").build()))
                    .build()
            ),
            new Result<>(
                200,
                WeaviateClass.builder()
                    .className(className)
                    .properties(List.of(Property.builder().name("entityType").build()))
                    .build(),
                null
            )
        );
        when(classCreator.run()).thenReturn(new Result<>(200, true, null));
        when(tenantsCreator.run()).thenReturn(new Result<>(200, true, null));
        when(propertyCreator.run()).thenReturn(new Result<>(200, true, null));

        Data data = mock(Data.class);
        ObjectDeleter deleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        ObjectCreator creator = mock(ObjectCreator.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.deleter()).thenReturn(deleter);
        when(data.creator()).thenReturn(creator);
        when(deleter.run()).thenReturn(new Result<>(200, true, null));
        when(creator.run()).thenReturn(new Result<>(
            200,
            WeaviateObject.builder().id(vectorId).build(),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThat(service.storeVector(
            "product",
            "p-1",
            "Waterproof shell jacket",
            List.of(0.1d, 0.2d, 0.3d),
            Map.of("category", "outerwear")
        )).isEqualTo(vectorId);

        ArgumentCaptor<WeaviateClass> classCaptor = ArgumentCaptor.forClass(WeaviateClass.class);
        verify(classCreator).withClass(classCaptor.capture());
        assertThat(classCaptor.getValue().getClassName()).isEqualTo(className);
        assertThat(classCaptor.getValue().getVectorizer()).isEqualTo("none");
        assertThat(classCaptor.getValue().getMultiTenancyConfig().getEnabled()).isTrue();

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantsCreator).withClassName(className);
        verify(tenantsCreator).withTenants(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getName()).isEqualTo("tenant-retail");

        ArgumentCaptor<Property> propertyCaptor = ArgumentCaptor.forClass(Property.class);
        verify(propertyCreator, times(3)).withClassName(className);
        verify(propertyCreator, times(3)).withProperty(propertyCaptor.capture());
        assertThat(propertyCaptor.getAllValues())
            .anySatisfy(property -> assertThat(property.getName()).startsWith("meta_category_"))
            .anySatisfy(property -> assertThat(property.getName()).startsWith("meta_indexedcreatedat_"))
            .anySatisfy(property -> assertThat(property.getName()).startsWith("meta_indexedupdatedat_"))
            .allSatisfy(property -> assertThat(property.getDataType()).containsExactly("text"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propertiesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Float[]> vectorCaptor = ArgumentCaptor.forClass(Float[].class);
        verify(deleter).withClassName(className);
        verify(deleter).withID(vectorId);
        verify(deleter).withTenant("tenant-retail");
        verify(creator).withClassName(className);
        verify(creator).withID(vectorId);
        verify(creator).withTenant("tenant-retail");
        verify(creator).withProperties(propertiesCaptor.capture());
        verify(creator).withVector(vectorCaptor.capture());

        assertThat(propertiesCaptor.getValue())
            .containsEntry("entityType", "product")
            .containsEntry("entityId", "p-1")
            .containsEntry("content", "Waterproof shell jacket");
        assertThat(propertiesCaptor.getValue().keySet()).anyMatch(key -> key.startsWith("meta_category_"));
        assertThat(propertiesCaptor.getValue().keySet()).anyMatch(key -> key.startsWith("meta_indexedcreatedat_"));
        assertThat(propertiesCaptor.getValue().keySet()).anyMatch(key -> key.startsWith("meta_indexedupdatedat_"));
        assertThat(propertiesCaptor.getValue().get("raw").toString())
            .contains("_indexedCreatedAt")
            .contains("_indexedUpdatedAt");
        assertThat(vectorCaptor.getValue()).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void adminDiagnosticsExposeNativeTenantScope() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("tenant.weaviate.local");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");
        weaviate.setClassPrefix("customer_acme_");
        weaviate.setTenantName("tenant-retail");
        weaviate.setNativeMultiTenancyEnabled(true);

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, mock(WeaviateClient.class));

        assertThat(service.adminDiagnostics())
            .containsEntry("provider", "weaviate")
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "CLASS_AND_TENANT")
            .containsEntry("rootResourceValue", "tenant.weaviate.local")
            .containsEntry("tenantHandle", "tenant-retail")
            .containsEntry("scopePattern", "CustomerAcme_1f765f56<EntityType> @ tenant tenant-retail")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", true)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true)
            .containsEntry("searchFilterMode", "weaviate-where-filter-text-backed")
            .containsEntry("scanFilterMode", "weaviate-where-filter-text-backed")
            .containsEntry("countMode", "native-aggregate-with-safe-fallback")
            .containsEntry("clearMode", "weaviate-delete-class-or-tenant-objects")
            .containsEntry("aggregateCountFallbacks", Map.of())
            .containsEntry("aggregateCountFallbackReasons", Map.of())
            .containsEntry("countFallbacks", Map.of())
            .containsEntry("countFallbackReasons", Map.of());
        assertThat(service.adminDiagnostics().get("scopePrefix"))
            .asString()
            .startsWith("CustomerAcme_");
    }

    @Test
    void vectorExistsTreatsTenantNotFoundAsMissingVector() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("tenant.weaviate.local");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");
        weaviate.setClassPrefix("customer_acme_");
        weaviate.setTenantName("tenant-beta");
        weaviate.setNativeMultiTenancyEnabled(true);

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className("CustomerAcme_Product").build(),
            null
        ));

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(getter.run()).thenReturn(new Result<>(
            422,
            null,
            WeaviateErrorResponse.builder()
                .code(422)
                .message("tenant not found")
                .error(List.of(
                    WeaviateErrorMessage.builder()
                        .message("determine shard: tenant not found: \"tenant-beta\"")
                        .build()
                ))
                .build()
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThat(service.vectorExists("product", "missing-id")).isFalse();
        assertThat(service.getVectorByEntity("product", "missing-id")).isEmpty();
        verify(classGetter, times(1)).run();
    }

    @Test
    void getVectorByEntityMapsObjectMetadataTimestampsAndEmbedding() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className("Product").build(),
            null
        ));

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(getter.run()).thenReturn(new Result<>(
            200,
            List.of(WeaviateObject.builder()
                .id("vector-id")
                .properties(Map.of(
                    "entityType", "product",
                    "entityId", "p-1",
                    "content", "Luxury watch",
                    "raw", "{\"category\":\"watches\",\"_indexedCreatedAt\":\"2024-01-01T00:00:00\",\"_indexedUpdatedAt\":\"bad-date\"}"
                ))
                .vector(new Float[]{1.0f, 0.5f})
                .build()),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        VectorRecord record = service.getVectorByEntity("product", "p-1").orElseThrow();

        assertThat(record.getVectorId()).isEqualTo("vector-id");
        assertThat(record.getEntityType()).isEqualTo("product");
        assertThat(record.getEntityId()).isEqualTo("p-1");
        assertThat(record.getContent()).isEqualTo("Luxury watch");
        assertThat(record.getEmbedding()).containsExactly(1.0, 0.5);
        assertThat(record.getMetadata())
            .containsEntry("category", "watches")
            .containsKey("raw");
        assertThat(record.getCreatedAt()).isEqualTo(LocalDateTime.parse("2024-01-01T00:00:00"));
        assertThat(record.getUpdatedAt()).isNull();
    }

    @Test
    void searchBuildsTextBackedWhereFilterForPortableScalarMetadata() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");
        mockExistingClass(client, className);

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of("Product", List.of())))
                .build(),
            null
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("featured", true);
        metadata.put("rank", 7);

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("jackets")
            .entityType("product")
            .metadata(metadata)
            .limit(5)
            .build());

        ArgumentCaptor<WhereFilter> whereCaptor = ArgumentCaptor.forClass(WhereFilter.class);
        verify(get).withWhere(whereCaptor.capture());
        WhereFilter where = whereCaptor.getValue();

        assertThat(where.getOperator()).isEqualTo("And");
        assertThat(where.getOperands()).hasSize(3);
        assertThat(where.getOperands())
            .extracting(WhereFilter::getValueText)
            .containsExactly("retail", "true", "7");
        assertThat(where.getOperands())
            .allSatisfy(operand -> {
                assertThat(operand.getOperator()).isEqualTo("Equal");
                assertThat(operand.getValueBoolean()).isNull();
                assertThat(operand.getValueInt()).isNull();
                assertThat(operand.getPath()).singleElement().asString().startsWith("meta_");
            });
    }

    @Test
    void searchAppliesPortableMetadataPostFilterForExactScalarSemantics() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");
        mockExistingClass(client, className);

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of(className, List.of(
                    Map.of(
                        "entityType", "product",
                        "entityId", "integral-rank",
                        "content", "Integral rank",
                        "raw", "{\"rank\":7}",
                        "_additional", Map.of("id", "doc-integral-rank", "certainty", 0.99d)
                    ),
                    Map.of(
                        "entityType", "product",
                        "entityId", "decimal-rank",
                        "content", "Decimal rank",
                        "raw", "{\"rank\":7.9}",
                        "_additional", Map.of("id", "doc-decimal-rank", "certainty", 0.98d)
                    )
                ))))
                .build(),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        AISearchResponse response = service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("ranked")
            .entityType("product")
            .metadata(Map.of("rank", 7))
            .limit(5)
            .build());

        assertThat(response.getResults())
            .extracting(row -> row.get("vectorId"))
            .containsExactly("doc-integral-rank");
    }

    @Test
    void scanUsesSentinelForEmptyStringMetadataFilterValue() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of("Product", List.of())))
                .build(),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(Map.of("status", ""))
            .limit(5)
            .build());

        ArgumentCaptor<WhereFilter> whereCaptor = ArgumentCaptor.forClass(WhereFilter.class);
        verify(get).withWhere(whereCaptor.capture());
        assertThat(whereCaptor.getValue().getPath()).singleElement().asString().startsWith("meta_status_");
        assertThat(whereCaptor.getValue().getOperator()).isEqualTo("Equal");
        assertThat(whereCaptor.getValue().getValueText()).isEqualTo("__ai_fabric_empty_string__");
        assertThat(whereCaptor.getValue().getValueBoolean()).isNull();
        assertThat(whereCaptor.getValue().getValueInt()).isNull();
    }

    @Test
    void scanProjectionReturnsNullForSuppressedFields() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        String className = WeaviateVectorDatabaseService.scopedClassName("product", "");
        mockExistingClass(client, className);

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of(className, List.of(Map.of(
                    "entityType", "product",
                    "entityId", "product-1",
                    "content", "Waterproof shell jacket",
                    "raw", "{\"category\":\"outerwear\"}",
                    "_additional", Map.of(
                        "id", "product-vector-1",
                        "vector", List.of(0.1d, 0.2d)
                    )
                )))))
                .build(),
            null
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        VectorScanPage page = service.scan(VectorScanRequest.builder()
            .entityType("product")
            .limit(1)
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(false)
            .build());

        assertThat(page.getVectors()).hasSize(1);
        assertThat(page.getVectors().getFirst().getContent()).isNull();
        assertThat(page.getVectors().getFirst().getEmbedding()).isNull();
        assertThat(page.getVectors().getFirst().getMetadata()).isNull();
    }

    @Test
    void searchUsesImpossibleWhereFilterForUnsupportedMetadata() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of("Product", List.of())))
                .build(),
            null
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("tags", List.of("public"));
        metadata.put("sessionId", null);

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        service.search(List.of(0.1d, 0.2d), AISearchRequest.builder()
            .query("jackets")
            .entityType("product")
            .metadata(metadata)
            .limit(5)
            .build());

        ArgumentCaptor<WhereFilter> whereCaptor = ArgumentCaptor.forClass(WhereFilter.class);
        verify(get).withWhere(whereCaptor.capture());
        assertThat(whereCaptor.getValue().getPath()).containsExactly("raw");
        assertThat(whereCaptor.getValue().getOperator()).isEqualTo("Equal");
        assertThat(whereCaptor.getValue().getValueText()).isEqualTo("__ai_fabric_no_match__");
    }

    @Test
    void scanUsesImpossibleWhereFilterForUnsupportedMetadata() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");

        GraphQL graphQL = mock(GraphQL.class);
        Get get = mock(Get.class, Answers.RETURNS_SELF);
        when(client.graphQL()).thenReturn(graphQL);
        when(graphQL.get()).thenReturn(get);
        when(get.run()).thenReturn(new Result<>(
            200,
            GraphQLResponse.builder()
                .data(Map.of("Get", Map.of("Product", List.of())))
                .build(),
            null
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant", "retail");
        metadata.put("providerOperator", Map.of("$eq", "retail"));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        service.scan(VectorScanRequest.builder()
            .entityType("product")
            .metadataEquals(metadata)
            .limit(5)
            .build());

        ArgumentCaptor<WhereFilter> whereCaptor = ArgumentCaptor.forClass(WhereFilter.class);
        verify(get).withWhere(whereCaptor.capture());
        assertThat(whereCaptor.getValue().getPath()).containsExactly("raw");
        assertThat(whereCaptor.getValue().getOperator()).isEqualTo("Equal");
        assertThat(whereCaptor.getValue().getValueText()).isEqualTo("__ai_fabric_no_match__");
    }

    @Test
    void updateVectorReturnsFalseWhenTargetObjectIsMissing() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");

        WeaviateClient client = mock(WeaviateClient.class);
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className("Product").build(),
            null
        ));

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(getter.run()).thenReturn(new Result<>(200, List.of(), null));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        boolean updated = service.updateVector(
            "missing-vector",
            "product",
            "missing-product",
            "Updated product",
            List.of(0.1d, 0.2d),
            Map.of("category", "watches")
        );

        assertThat(updated).isFalse();
        verify(data).objectsGetter();
        verify(data, never()).creator();
        verify(data, never()).deleter();
    }

    @Test
    void updateVectorPropagatesExistenceCheckFailures() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        Schema schema = client.schema();
        PropertyCreator propertyCreator = mock(PropertyCreator.class, Answers.RETURNS_SELF);
        when(schema.propertyCreator()).thenReturn(propertyCreator);
        when(propertyCreator.run()).thenReturn(new Result<>(200, true, null));

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(getter.run()).thenReturn(new Result<>(
            503,
            null,
            errorResponse(503, "backend unavailable during object lookup")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.updateVector(
            "vector-1",
            "product",
            "product-1",
            "Updated product",
            List.of(0.1d, 0.2d),
            Map.of()
        ))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("update existence check");
    }

    @Test
    void updateVectorPropagatesUpsertCreateFailures() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        mockPropertyCreator(client);

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        ObjectDeleter deleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        ObjectCreator creator = mock(ObjectCreator.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(data.deleter()).thenReturn(deleter);
        when(data.creator()).thenReturn(creator);
        when(getter.run()).thenReturn(new Result<>(
            200,
            List.of(WeaviateObject.builder().id("vector-1").build()),
            null
        ));
        when(deleter.run()).thenReturn(new Result<>(200, true, null));
        when(creator.run()).thenReturn(new Result<>(
            500,
            null,
            errorResponse(500, "create failed")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.updateVector(
            "vector-1",
            "product",
            "product-1",
            "Updated product",
            List.of(0.1d, 0.2d),
            Map.of()
        ))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("Failed to upsert Weaviate object");
    }

    @Test
    void storeVectorPropagatesUpsertPrepareDeleteFailures() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        mockPropertyCreator(client);

        Data data = mock(Data.class);
        ObjectDeleter deleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.deleter()).thenReturn(deleter);
        when(deleter.run()).thenReturn(new Result<>(
            503,
            null,
            errorResponse(503, "delete-before-create failed")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.storeVector(
            "product",
            "product-1",
            "Waterproof shell jacket",
            List.of(0.1d, 0.2d),
            Map.of()
        ))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("Failed to prepare Weaviate upsert");
    }

    @Test
    void removeVectorPropagatesClassQualifiedDeleteFailures() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        mockPropertyCreator(client);

        Data data = mock(Data.class);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        ObjectDeleter deleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.objectsGetter()).thenReturn(getter);
        when(data.deleter()).thenReturn(deleter);
        when(getter.run()).thenReturn(new Result<>(
            200,
            List.of(WeaviateObject.builder().id("vector-1").build()),
            null
        ));
        when(deleter.run()).thenReturn(new Result<>(
            500,
            null,
            errorResponse(500, "delete denied")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.removeVector("product", "product-1"))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("Weaviate delete failed for class Product");
    }

    @Test
    void removeVectorByIdReturnsFalseWhenKnownClassObjectIsMissing() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        mockPropertyCreator(client);

        Data data = mock(Data.class);
        ObjectDeleter upsertDeleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        ObjectCreator creator = mock(ObjectCreator.class, Answers.RETURNS_SELF);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.deleter()).thenReturn(upsertDeleter);
        when(data.creator()).thenReturn(creator);
        when(data.objectsGetter()).thenReturn(getter);
        when(upsertDeleter.run()).thenReturn(new Result<>(200, true, null));
        when(creator.run()).thenReturn(new Result<>(200, WeaviateObject.builder().id("vector-1").build(), null));
        when(getter.run()).thenReturn(new Result<>(200, List.of(), null));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);
        service.storeVector("product", "product-1", "Waterproof shell jacket", List.of(0.1d, 0.2d), Map.of());

        assertThat(service.removeVectorById("missing-vector")).isFalse();
        verify(data).objectsGetter();
    }

    @Test
    void getVectorDoesNotFallBackToClasslessLookupWhenKnownClassesMiss() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);
        mockExistingClass(client, "Product");
        mockPropertyCreator(client);

        Data data = mock(Data.class);
        ObjectDeleter upsertDeleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        ObjectCreator creator = mock(ObjectCreator.class, Answers.RETURNS_SELF);
        ObjectsGetter getter = mock(ObjectsGetter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.deleter()).thenReturn(upsertDeleter);
        when(data.creator()).thenReturn(creator);
        when(data.objectsGetter()).thenReturn(getter);
        when(upsertDeleter.run()).thenReturn(new Result<>(200, true, null));
        when(creator.run()).thenReturn(new Result<>(200, WeaviateObject.builder().id("vector-1").build(), null));
        when(getter.run()).thenReturn(new Result<>(
            404,
            null,
            errorResponse(404, "no object with id 'other-scope-vector'")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);
        service.storeVector("product", "product-1", "Waterproof shell jacket", List.of(0.1d, 0.2d), Map.of());

        assertThat(service.getVector("other-scope-vector")).isEmpty();
        verify(data, times(1)).objectsGetter();
        verify(getter).withClassName(WeaviateVectorDatabaseService.scopedClassName("product", ""));
        verify(getter).withID("other-scope-vector");
    }

    @Test
    void removeVectorByIdPropagatesFallbackDeleteFailures() {
        AIProviderConfig config = baseConfig();
        WeaviateClient client = mock(WeaviateClient.class);

        Data data = mock(Data.class);
        ObjectDeleter deleter = mock(ObjectDeleter.class, Answers.RETURNS_SELF);
        when(client.data()).thenReturn(data);
        when(data.deleter()).thenReturn(deleter);
        when(deleter.run()).thenReturn(new Result<>(
            500,
            null,
            errorResponse(500, "fallback delete denied")
        ));

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, client);

        assertThatThrownBy(() -> service.removeVectorById("vector-1"))
            .isInstanceOf(ai.fabric.exception.AIServiceException.class)
            .hasMessageContaining("Weaviate delete failed for vector vector-1");
    }

    @Test
    void batchOperationsIgnoreNullAndBlankInputs() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");

        WeaviateVectorDatabaseService service = new WeaviateVectorDatabaseService(config, null, mock(WeaviateClient.class));

        assertThat(service.batchStoreVectors(null)).isEmpty();
        assertThat(service.batchUpdateVectors(Arrays.asList(
            null,
            VectorRecord.builder().entityType("").entityId("p-1").build(),
            VectorRecord.builder().entityType("product").entityId("").build()
        ))).isZero();
        assertThat(service.batchRemoveVectors(Arrays.asList(null, " ", ""))).isZero();
        assertThat(service.removeVectorById(" ")).isFalse();
    }

    private static AIProviderConfig baseConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("https");
        weaviate.setHost("example.weaviate.cloud");
        weaviate.setPort(443);
        weaviate.setApiKey("test-key");
        return config;
    }

    private static void mockExistingClass(WeaviateClient client, String className) {
        Schema schema = mock(Schema.class);
        ClassGetter classGetter = mock(ClassGetter.class);
        when(client.schema()).thenReturn(schema);
        when(schema.classGetter()).thenReturn(classGetter);
        when(classGetter.withClassName(anyString())).thenReturn(classGetter);
        when(classGetter.run()).thenReturn(new Result<>(
            200,
            WeaviateClass.builder().className(className).build(),
            null
        ));
    }

    private static void mockPropertyCreator(WeaviateClient client) {
        Schema schema = client.schema();
        PropertyCreator propertyCreator = mock(PropertyCreator.class, Answers.RETURNS_SELF);
        when(schema.propertyCreator()).thenReturn(propertyCreator);
        when(propertyCreator.run()).thenReturn(new Result<>(200, true, null));
    }

    private static WeaviateErrorResponse errorResponse(int code, String message) {
        return WeaviateErrorResponse.builder()
            .code(code)
            .message(message)
            .error(List.of(WeaviateErrorMessage.builder().message(message).build()))
            .build();
    }
}
