package com.ai.infrastructure.indexing.worker;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.entity.IndexingQueueEntry;
import com.ai.infrastructure.indexing.IndexingActionPlan;
import com.ai.infrastructure.service.AICapabilityService;
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
