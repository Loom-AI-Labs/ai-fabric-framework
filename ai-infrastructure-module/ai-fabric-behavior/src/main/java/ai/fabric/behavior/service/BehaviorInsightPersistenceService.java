package ai.fabric.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Public persistence boundary that optionally enqueues the approved insight
 * projection when the resolved behavior policy enables indexing.
 */
@Service
public class BehaviorInsightPersistenceService {

    private final BehaviorStorageAdapter storageAdapter;
    private final ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider;
    private final AIEntityDescriptorRegistry descriptorRegistry;

    public BehaviorInsightPersistenceService(
        BehaviorStorageAdapter storageAdapter,
        ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider,
        AIEntityDescriptorRegistry descriptorRegistry
    ) {
        this.storageAdapter = Objects.requireNonNull(storageAdapter);
        this.indexingGatewayProvider = Objects.requireNonNull(indexingGatewayProvider);
        this.descriptorRegistry = Objects.requireNonNull(descriptorRegistry);
    }

    @Transactional
    public BehaviorInsights save(BehaviorInsights insight) {
        Objects.requireNonNull(insight, "insight is required");
        AIProcessOperation operation = insight.getId() == null
            ? AIProcessOperation.CREATE
            : AIProcessOperation.UPDATE;
        BehaviorInsights saved = storageAdapter.save(insight);

        AIEntityIndexingGateway gateway = indexingGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return saved;
        }
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(saved);
        if (descriptor.indexingEnabled()) {
            gateway.upsert(saved, operation);
        }
        return saved;
    }
}
