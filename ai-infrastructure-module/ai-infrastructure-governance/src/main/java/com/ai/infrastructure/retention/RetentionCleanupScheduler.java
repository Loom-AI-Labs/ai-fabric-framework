package com.ai.infrastructure.retention;

import com.ai.infrastructure.governance.catalog.IndexCatalog;
import com.ai.infrastructure.governance.catalog.IndexCatalogEntry;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanPage;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanRequest;
import com.ai.infrastructure.governance.catalog.noop.NoopIndexCatalog;
import com.ai.infrastructure.governance.config.AIGovernanceProperties;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.retention.policy.RetentionPolicyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    private final AIGovernanceProperties properties;
    private final IndexCatalog indexCatalog;
    private final VectorDatabaseService vectorDatabaseService;
    private final ObjectProvider<RetentionPolicyProvider> retentionPolicyProvider;
    private final Clock clock;

    @Scheduled(cron = "${ai.governance.retention.cron:0 30 3 * * *}")
    public void cleanupByRetentionPolicy() {
        if (!properties.isEnabled() || properties.getRetention() == null || !properties.getRetention().isEnabled()) {
            return;
        }
        if (indexCatalog instanceof NoopIndexCatalog) {
            log.warn("Retention cleanup is enabled but IndexCatalog is DISABLED; no cleanup will run");
            return;
        }

        List<String> entityTypes = resolveEntityTypes(properties.getRetention());
        if (entityTypes.isEmpty()) {
            log.warn("Retention cleanup is enabled but no entity types are configured (ai.governance.retention.entity-types or retention-days keys)");
            return;
        }

        RetentionPolicyProvider policy = retentionPolicyProvider.getIfAvailable();
        Map<String, Integer> configuredRetention = properties.getRetention().getRetentionDays();
        if ((configuredRetention == null || configuredRetention.isEmpty()) && policy == null) {
            log.warn("Retention cleanup is enabled but no retention-days configured and no RetentionPolicyProvider provided");
            return;
        }

        int totalDeleted = 0;
        for (String entityType : entityTypes) {
            totalDeleted += cleanupEntityType(entityType, policy);
        }

        if (totalDeleted > 0) {
            log.info("Retention cleanup deleted {} vectors", totalDeleted);
        }
    }

    private int cleanupEntityType(String entityType, RetentionPolicyProvider policy) {
        if (entityType == null || entityType.isBlank()) {
            return 0;
        }

        int scanLimit = properties.getRetention().getScanLimit() != null && properties.getRetention().getScanLimit() > 0
            ? properties.getRetention().getScanLimit()
            : 200;

        LocalDateTime now = LocalDateTime.now(clock);
        String cursor = null;
        int deleted = 0;

        while (true) {
            IndexCatalogScanPage page = indexCatalog.scan(IndexCatalogScanRequest.builder()
                .entityType(entityType)
                .limit(scanLimit)
                .cursor(cursor)
                .build());

            if (page == null || page.getEntries() == null || page.getEntries().isEmpty()) {
                return deleted;
            }

            for (IndexCatalogEntry entry : page.getEntries()) {
                if (entry == null || entry.getEntityId() == null) {
                    continue;
                }
                LocalDateTime createdAt = entry.getIndexedCreatedAt() != null ? entry.getIndexedCreatedAt() : entry.getIndexedUpdatedAt();
                if (createdAt == null) {
                    continue;
                }

                Integer retentionDays = resolveRetentionDays(entityType, entry, policy);
                if (retentionDays == null || retentionDays < 0) {
                    continue;
                }

                LocalDateTime cutoff = now.minusDays(retentionDays);
                if (!createdAt.isBefore(cutoff)) {
                    continue;
                }

                if (policy != null) {
                    if (!policy.shouldDelete(entry)) {
                        continue;
                    }
                    if (!policy.executeDelete(entry)) {
                        continue;
                    }
                }

                try {
                    boolean removed = vectorDatabaseService.removeVector(entityType, entry.getEntityId());
                    if (removed) {
                        deleted++;
                    }
                } catch (Exception ex) {
                    log.warn("Retention cleanup failed removing vector {}:{}",
                        entityType, entry.getEntityId(), ex);
                }
            }

            if (!page.isHasMore() || page.getNextCursor() == null || page.getNextCursor().isBlank()) {
                return deleted;
            }
            if (page.getNextCursor().equals(cursor)) {
                log.warn("Retention cleanup cursor did not advance for entityType={}; stopping to avoid infinite loop", entityType);
                return deleted;
            }
            cursor = page.getNextCursor();
        }
    }

    private Integer resolveRetentionDays(String entityType, IndexCatalogEntry entry, RetentionPolicyProvider policy) {
        if (policy != null) {
            String classification = null;
            if (entry != null && entry.getMetadata() != null) {
                Object raw = entry.getMetadata().get("dataClassification");
                if (raw != null) {
                    classification = raw.toString();
                }
            }
            return policy.getRetentionDays(classification != null ? classification : "default", entityType);
        }

        Map<String, Integer> retentionDays = properties.getRetention().getRetentionDays();
        if (retentionDays == null || retentionDays.isEmpty()) {
            return null;
        }
        Integer byType = retentionDays.get(entityType);
        if (byType != null) {
            return byType;
        }
        return retentionDays.get("default");
    }

    private static List<String> resolveEntityTypes(AIGovernanceProperties.RetentionProperties retention) {
        if (retention == null) {
            return List.of();
        }
        if (retention.getEntityTypes() != null && !retention.getEntityTypes().isEmpty()) {
            return retention.getEntityTypes().stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        }
        if (retention.getRetentionDays() == null || retention.getRetentionDays().isEmpty()) {
            return List.of();
        }
        return retention.getRetentionDays().keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .filter(key -> !"default".equalsIgnoreCase(key))
            .toList();
    }
}

