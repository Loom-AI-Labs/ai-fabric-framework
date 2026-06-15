package ai.fabric.indexing;

import ai.fabric.annotation.AIProcess;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * Entry point that decides whether to execute indexing work synchronously or enqueue it.
 */
@Slf4j
public class IndexingCoordinator {

    private final IndexingStrategyResolver strategyResolver;
    private final IndexingQueueService queueService;
    private final AIEntityConfigurationLoader configurationLoader;
    private final AIIndexingProperties properties;
    private final ObjectMapper objectMapper;
    private final AICapabilityService capabilityService;

    public IndexingCoordinator(
        IndexingStrategyResolver strategyResolver,
        IndexingQueueService queueService,
        AIEntityConfigurationLoader configurationLoader,
        AIIndexingProperties properties,
        ObjectMapper objectMapper,
        AICapabilityService capabilityService
    ) {
        this.strategyResolver = strategyResolver;
        this.queueService = queueService;
        this.configurationLoader = configurationLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.capabilityService = capabilityService;
    }

    public void handle(
        Object entity,
        String entityType,
        IndexingOperation operation,
        IndexingActionPlan actionPlan,
        AIProcess aiProcess
    ) {
        if (entity == null || !actionPlan.requiresWork()) {
            return;
        }

        Class<?> entityClass = entity.getClass();
        String entityId = capabilityService.resolveEntityId(entity);
        IndexingStrategy strategy = resolveStrategy(entityClass, operation, aiProcess);

        if (strategy == IndexingStrategy.SYNC) {
            executeNow(entity, entityType, actionPlan);
        } else {
            enqueue(entity, entityType, entityClass, entityId, operation, actionPlan, strategy);
        }
    }

    private IndexingStrategy resolveStrategy(
        Class<?> entityClass,
        IndexingOperation operation,
        AIProcess aiProcess
    ) {
        try {
            return strategyResolver.resolve(entityClass, operation, aiProcess);
        } catch (IllegalArgumentException ex) {
            log.warn("Falling back to ASYNC indexing for {} due to missing annotations: {}", entityClass.getName(), ex.getMessage());
            return IndexingStrategy.ASYNC;
        }
    }

    private void executeNow(Object entity, String entityType, IndexingActionPlan plan) {
        AIEntityConfig config = configurationLoader.getEntityConfig(entityType);
        if (config == null) {
            log.warn("Skipping synchronous indexing because no config was found for {}", entityType);
            return;
        }

        if (plan.generateEmbedding()) {
            capabilityService.generateEmbeddings(entity, config);
        }

        if (plan.indexForSearch()) {
            capabilityService.indexForSearch(entity, config);
        }

        if (plan.enableAnalysis()) {
            capabilityService.analyzeEntity(entity, config);
        }

        if (plan.removeFromSearch()) {
            capabilityService.removeFromSearch(entity, config);
        }

        if (plan.cleanupEmbeddings()) {
            capabilityService.cleanupEmbeddings(entity, config);
        }
    }

    private void enqueue(
        Object entity,
        String entityType,
        Class<?> entityClass,
        String entityId,
        IndexingOperation operation,
        IndexingActionPlan actionPlan,
        IndexingStrategy strategy
    ) {
        try {
            String payload = objectMapper.writeValueAsString(entity);
            IndexingRequest request = IndexingRequest.builder()
                .entityType(entityType)
                .entityId(entityId)
                .entityClassName(entityClass.getName())
                .operation(operation)
                .actionPlan(actionPlan)
                .strategy(strategy)
                .payload(payload)
                .maxRetries(properties.getQueue().getMaxRetries())
                .scheduledFor(LocalDateTime.now())
                .build();
            queueService.enqueue(request);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to enqueue indexing work", ex);
        }
    }

}
