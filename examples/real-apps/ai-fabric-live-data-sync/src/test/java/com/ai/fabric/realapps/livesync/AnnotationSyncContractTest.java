package com.ai.fabric.realapps.livesync;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIProcess;
import ai.fabric.annotation.AISearchable;
import ai.fabric.annotation.EnableAIInfrastructure;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.service.SyncGuideService;
import com.ai.fabric.realapps.livesync.service.SyncPolicyService;
import com.ai.fabric.realapps.livesync.service.SyncProductService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationSyncContractTest {

    @Test
    void applicationAndAllEntityTypesExposeTheAnnotationDrivenSyncContract() {
        assertThat(LiveDataSyncApplication.class).hasAnnotation(EnableAIInfrastructure.class);

        for (Class<?> entityClass : List.of(SyncProduct.class, SyncPolicy.class, SyncGuide.class)) {
            AICapable capable = entityClass.getAnnotation(AICapable.class);
            assertThat(capable).isNotNull();
            assertThat(capable.entityType()).isNotBlank();
            assertThat(capable.features()).contains("embedding", "search", "rag");
            assertThat(capable.indexingStrategy()).isEqualTo(IndexingStrategy.SYNC);
            assertThat(capable.migrationRepository().getSimpleName()).endsWith("Repository");

            List<Field> fields = Arrays.asList(entityClass.getDeclaredFields());
            assertThat(fields).anyMatch(field -> field.isAnnotationPresent(AISearchable.class));
            assertThat(fields).anyMatch(field -> field.isAnnotationPresent(AIContext.class));
            assertThat(fields)
                .filteredOn(field -> field.isAnnotationPresent(AISearchable.class))
                .allMatch(field -> field.getAnnotation(AISearchable.class).maxLength() > 0);
        }
    }

    @Test
    void createUpdateAndDeleteMethodsAreAllRoutedThroughAiProcess() {
        assertLifecycle(SyncProductService.class, "createProduct", "updateProduct", "deleteProduct");
        assertLifecycle(SyncPolicyService.class, "createPolicy", "updatePolicy", "deletePolicy");
        assertLifecycle(SyncGuideService.class, "createGuide", "updateGuide", "deleteGuide");
    }

    private void assertLifecycle(
        Class<?> serviceClass,
        String createMethod,
        String updateMethod,
        String deleteMethod
    ) {
        assertProcess(serviceClass, createMethod, "create", true);
        assertProcess(serviceClass, updateMethod, "update", true);
        assertProcess(serviceClass, deleteMethod, "delete", false);
    }

    private void assertProcess(
        Class<?> serviceClass,
        String methodName,
        String processType,
        boolean indexForSearch
    ) {
        Method method = Arrays.stream(serviceClass.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        AIProcess process = method.getAnnotation(AIProcess.class);
        assertThat(process).isNotNull();
        assertThat(process.entityType()).isNotBlank();
        assertThat(process.processType()).isEqualTo(processType);
        assertThat(process.indexForSearch()).isEqualTo(indexForSearch);
    }
}
