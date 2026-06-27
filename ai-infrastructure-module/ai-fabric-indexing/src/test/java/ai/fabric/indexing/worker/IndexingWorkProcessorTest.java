package ai.fabric.indexing.worker;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IndexingWorkProcessorTest {

    @Test
    void executesOnlyRequestedActionPlanOperations() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService capabilityService = mock(AICapabilityService.class);
        IndexingWorkProcessor processor = new IndexingWorkProcessor(
            objectMapper,
            configurationLoader,
            capabilityService
        );
        Product entity = new Product("p-1");
        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("product")
            .build();
        IndexingQueueEntry entry = entry(new IndexingActionPlan(true, false, true, false, true));

        when(configurationLoader.getEntityConfig("product")).thenReturn(config);

        processor.process(entry);

        verify(capabilityService).generateEmbeddings(entity, config);
        verify(capabilityService, never()).indexForSearch(entity, config);
        verify(capabilityService).analyzeEntity(entity, config);
        verify(capabilityService, never()).removeFromSearch(entity, config);
        verify(capabilityService).cleanupEmbeddings(entity, config);
    }

    @Test
    void ignoresVirtualGetterPropertiesSerializedIntoQueuePayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService capabilityService = mock(AICapabilityService.class);
        IndexingWorkProcessor processor = new IndexingWorkProcessor(
            objectMapper,
            configurationLoader,
            capabilityService
        );
        ProductWithVirtualGetter source = new ProductWithVirtualGetter("p-1", "Aurora");
        String payload = objectMapper.writeValueAsString(source);
        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("product")
            .build();
        IndexingQueueEntry entry = entry(
            new IndexingActionPlan(false, true, false, false, false),
            ProductWithVirtualGetter.class,
            payload
        );

        assertThat(payload).contains("displayName");
        when(configurationLoader.getEntityConfig("product")).thenReturn(config);

        processor.process(entry);

        verify(capabilityService).indexForSearch(new ProductWithVirtualGetter("p-1", "Aurora"), config);
    }

    @Test
    void throwsWhenEntityConfigurationIsMissing() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService capabilityService = mock(AICapabilityService.class);
        IndexingWorkProcessor processor = new IndexingWorkProcessor(
            objectMapper,
            configurationLoader,
            capabilityService
        );
        IndexingQueueEntry entry = entry(new IndexingActionPlan(true, true, false, false, false));

        when(configurationLoader.getEntityConfig("product")).thenReturn(null);

        assertThatThrownBy(() -> processor.process(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No AIEntityConfig registered for product");

        verifyNoInteractions(objectMapper, capabilityService);
    }

    private IndexingQueueEntry entry(IndexingActionPlan plan) {
        return entry(plan, Product.class, "{\"id\":\"p-1\"}");
    }

    private IndexingQueueEntry entry(IndexingActionPlan plan, Class<?> entityClass, String payload) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setEntityType("product");
        entry.setEntityId("p-1");
        entry.setEntityClass(entityClass.getName());
        entry.setOperation(IndexingOperation.CREATE);
        entry.setPayload(payload);
        entry.applyActionPlan(plan);
        return entry;
    }

    private record Product(String id) {
    }

    private record ProductWithVirtualGetter(String id, String name) {
        public String getDisplayName() {
            return "Product " + name;
        }
    }
}
