package ai.fabric.indexing;

import ai.fabric.annotation.AICapable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IndexingCoordinatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-18T10:15:30Z"),
        ZoneOffset.UTC
    );

    @Test
    void executesSynchronousWorkImmediately() {
        Dependencies dependencies = dependencies();
        SyncProduct entity = new SyncProduct("p-100");
        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("product")
            .build();
        IndexingActionPlan plan = new IndexingActionPlan(true, true, true, true, true);

        when(dependencies.capabilityService.resolveEntityId(entity)).thenReturn("p-100");
        when(dependencies.configurationLoader.getEntityConfig("product")).thenReturn(config);

        dependencies.coordinator.handle(entity, "product", IndexingOperation.CREATE, plan, null);

        verify(dependencies.capabilityService).generateEmbeddings(entity, config);
        verify(dependencies.capabilityService).indexForSearch(entity, config);
        verify(dependencies.capabilityService).analyzeEntity(entity, config);
        verify(dependencies.capabilityService).removeFromSearch(entity, config);
        verify(dependencies.capabilityService).cleanupEmbeddings(entity, config);
        verifyNoInteractions(dependencies.queueService);
    }

    @Test
    void enqueuesResolvedBatchWorkWithSerializedPayload() throws Exception {
        Dependencies dependencies = dependencies();
        BatchOrder entity = new BatchOrder("o-200");
        IndexingActionPlan plan = new IndexingActionPlan(true, true, false, false, false);
        dependencies.properties.getQueue().setMaxRetries(7);

        when(dependencies.capabilityService.resolveEntityId(entity)).thenReturn("o-200");
        when(dependencies.objectMapper.writeValueAsString(entity)).thenReturn("{\"id\":\"o-200\"}");

        dependencies.coordinator.handle(entity, "order", IndexingOperation.UPDATE, plan, null);

        ArgumentCaptor<IndexingRequest> requestCaptor = ArgumentCaptor.forClass(IndexingRequest.class);
        verify(dependencies.queueService).enqueue(requestCaptor.capture());
        IndexingRequest request = requestCaptor.getValue();

        assertThat(request.entityType()).isEqualTo("order");
        assertThat(request.entityId()).isEqualTo("o-200");
        assertThat(request.entityClassName()).isEqualTo(BatchOrder.class.getName());
        assertThat(request.operation()).isEqualTo(IndexingOperation.UPDATE);
        assertThat(request.strategy()).isEqualTo(IndexingStrategy.BATCH);
        assertThat(request.actionPlan()).isEqualTo(plan);
        assertThat(request.payload()).isEqualTo("{\"id\":\"o-200\"}");
        assertThat(request.maxRetries()).isEqualTo(7);
        assertThat(request.scheduledFor()).isEqualTo(LocalDateTime.of(2026, 6, 18, 10, 15, 30));
        verify(dependencies.configurationLoader, never()).getEntityConfig("order");
    }

    @Test
    void skipsWorkWhenActionPlanIsEmpty() {
        Dependencies dependencies = dependencies();
        SyncProduct entity = new SyncProduct("p-300");
        IndexingActionPlan plan = new IndexingActionPlan(false, false, false, false, false);

        dependencies.coordinator.handle(entity, "product", IndexingOperation.CREATE, plan, null);

        verifyNoInteractions(
            dependencies.capabilityService,
            dependencies.configurationLoader,
            dependencies.queueService,
            dependencies.objectMapper
        );
    }

    private Dependencies dependencies() {
        IndexingStrategyResolver strategyResolver = new IndexingStrategyResolver();
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AICapabilityService capabilityService = mock(AICapabilityService.class);
        IndexingCoordinator coordinator = new IndexingCoordinator(
            strategyResolver,
            queueService,
            configurationLoader,
            properties,
            objectMapper,
            capabilityService,
            FIXED_CLOCK
        );
        return new Dependencies(
            coordinator,
            queueService,
            configurationLoader,
            properties,
            objectMapper,
            capabilityService
        );
    }

    private record Dependencies(
        IndexingCoordinator coordinator,
        IndexingQueueService queueService,
        AIEntityConfigurationLoader configurationLoader,
        AIIndexingProperties properties,
        ObjectMapper objectMapper,
        AICapabilityService capabilityService
    ) {
    }

    @AICapable(entityType = "product", indexingStrategy = IndexingStrategy.SYNC)
    private static class SyncProduct {
        @SuppressWarnings("unused")
        private final String id;

        private SyncProduct(String id) {
            this.id = id;
        }
    }

    @AICapable(entityType = "order", onUpdateStrategy = IndexingStrategy.BATCH)
    private static class BatchOrder {
        @SuppressWarnings("unused")
        private final String id;

        private BatchOrder(String id) {
            this.id = id;
        }
    }
}
