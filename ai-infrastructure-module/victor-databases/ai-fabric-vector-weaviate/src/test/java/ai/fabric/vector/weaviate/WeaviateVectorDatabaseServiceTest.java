package ai.fabric.vector.weaviate;

import ai.fabric.config.AIProviderConfig;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.base.WeaviateErrorMessage;
import io.weaviate.client.base.WeaviateErrorResponse;
import io.weaviate.client.v1.data.Data;
import io.weaviate.client.v1.data.api.ObjectsGetter;
import io.weaviate.client.v1.schema.Schema;
import io.weaviate.client.v1.schema.api.ClassGetter;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
            .containsEntry("sharedStorage", true)
            .containsEntry("scopeType", "CLASS_AND_TENANT")
            .containsEntry("rootResourceValue", "tenant.weaviate.local")
            .containsEntry("tenantHandle", "tenant-retail")
            .containsEntry("scopePattern", "CustomerAcme_1f765f56<EntityType> @ tenant tenant-retail");
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
}
