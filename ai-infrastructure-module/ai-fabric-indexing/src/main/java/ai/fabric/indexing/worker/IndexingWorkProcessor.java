package ai.fabric.indexing.worker;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes the actual indexing work for a leased queue entry.
 */
@Slf4j
public class IndexingWorkProcessor {

    private final ObjectMapper objectMapper;
    private final AIEntityConfigurationLoader configurationLoader;
    private final AICapabilityService capabilityService;

    public IndexingWorkProcessor(
        ObjectMapper objectMapper,
        AIEntityConfigurationLoader configurationLoader,
        AICapabilityService capabilityService
    ) {
        this.objectMapper = objectMapper;
        this.configurationLoader = configurationLoader;
        this.capabilityService = capabilityService;
    }

    public void process(IndexingQueueEntry entry) throws Exception {
        AIEntityConfig config = configurationLoader.getEntityConfig(entry.getEntityType());
        if (config == null) {
            throw new IllegalStateException("No AIEntityConfig registered for " + entry.getEntityType());
        }

        Object entity = deserialize(entry);
        IndexingActionPlan plan = entry.toActionPlan();

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

    private Object deserialize(IndexingQueueEntry entry) throws Exception {
        Class<?> entityClass = Class.forName(entry.getEntityClass());
        return objectMapper.readValue(entry.getPayload(), entityClass);
    }
}
