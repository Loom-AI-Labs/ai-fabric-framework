package ai.fabric.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorInsightPersistenceServiceTest {

    @Test
    void fullPolicyPersistsAndIndexesThroughTheCanonicalGateway() {
        BehaviorStorageAdapter storage = mock(BehaviorStorageAdapter.class);
        AIEntityIndexingGateway gateway = mock(AIEntityIndexingGateway.class);
        AIEntityDescriptorRegistry registry = mock(AIEntityDescriptorRegistry.class);
        AIEntityDescriptor descriptor = mock(AIEntityDescriptor.class);
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId("user-1")
            .segment("at-risk")
            .build();
        BehaviorInsights saved = BehaviorInsights.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .segment("at-risk")
            .build();
        when(storage.save(insight)).thenReturn(saved);
        when(registry.resolve(saved)).thenReturn(descriptor);
        when(descriptor.indexingEnabled()).thenReturn(true);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        BehaviorInsightPersistenceService service =
            new BehaviorInsightPersistenceService(
                storage,
                beans.getBeanProvider(AIEntityIndexingGateway.class),
                registry
            );

        assertThat(service.save(insight)).isSameAs(saved);

        verify(gateway).upsert(saved, AIProcessOperation.CREATE);
    }

    @Test
    void lightPolicyPersistsWithoutCreatingVectorWork() {
        BehaviorStorageAdapter storage = mock(BehaviorStorageAdapter.class);
        AIEntityIndexingGateway gateway = mock(AIEntityIndexingGateway.class);
        AIEntityDescriptorRegistry registry = mock(AIEntityDescriptorRegistry.class);
        AIEntityDescriptor descriptor = mock(AIEntityDescriptor.class);
        BehaviorInsights insight = BehaviorInsights.builder()
            .id(UUID.randomUUID())
            .userId("user-2")
            .segment("stable")
            .build();
        when(storage.save(insight)).thenReturn(insight);
        when(registry.resolve(insight)).thenReturn(descriptor);
        when(descriptor.indexingEnabled()).thenReturn(false);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        BehaviorInsightPersistenceService service =
            new BehaviorInsightPersistenceService(
                storage,
                beans.getBeanProvider(AIEntityIndexingGateway.class),
                registry
            );

        assertThat(service.save(insight)).isSameAs(insight);

        verify(gateway, never()).upsert(
            insight,
            AIProcessOperation.UPDATE
        );
    }
}
