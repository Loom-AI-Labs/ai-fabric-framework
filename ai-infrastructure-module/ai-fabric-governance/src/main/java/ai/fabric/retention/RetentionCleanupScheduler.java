package ai.fabric.retention;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import ai.fabric.governance.catalog.disabled.DisabledIndexCatalog;
import ai.fabric.governance.config.AIGovernanceProperties;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.retention.policy.RetentionPolicyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
        runCleanupByRetentionPolicy();
    }

    public RetentionCleanupResult runCleanupByRetentionPolicy() {
        LocalDateTime timestamp = LocalDateTime.now(clock);
        CleanupAccumulator accumulator = new CleanupAccumulator(timestamp);

        if (properties == null || !properties.isEnabled()
            || properties.getRetention() == null
            || !properties.getRetention().isEnabled()) {
            return accumulator.result(RetentionCleanupResult.Status.DISABLED, "Retention cleanup is disabled");
        }

        if (indexCatalog instanceof DisabledIndexCatalog) {
            String message = "Retention cleanup is enabled but IndexCatalog is DISABLED; no cleanup will run";
            log.warn(message);
            return accumulator.result(RetentionCleanupResult.Status.SKIPPED, message);
        }

        List<String> entityTypes = resolveEntityTypes(properties.getRetention());
        if (entityTypes.isEmpty()) {
            String message = "Retention cleanup is enabled but no entity types are configured (ai.governance.retention.entity-types or retention-days keys)";
            log.warn(message);
            return accumulator.result(RetentionCleanupResult.Status.SKIPPED, message);
        }

        RetentionPolicyProvider policy = retentionPolicyProvider.getIfAvailable();
        Map<String, Integer> configuredRetention = properties.getRetention().getRetentionDays();
        if ((configuredRetention == null || configuredRetention.isEmpty()) && policy == null) {
            String message = "Retention cleanup is enabled but no retention-days configured and no RetentionPolicyProvider provided";
            log.warn(message);
            return accumulator.result(RetentionCleanupResult.Status.SKIPPED, message);
        }

        for (String entityType : entityTypes) {
            cleanupEntityType(entityType, policy, accumulator);
        }

        RetentionCleanupResult.Status status = accumulator.cleanupFailures > 0
            ? RetentionCleanupResult.Status.PARTIAL
            : RetentionCleanupResult.Status.COMPLETED;
        RetentionCleanupResult result = accumulator.result(status, summaryMessage(accumulator));
        if (result.getVectorsDeleted() > 0 || result.getCleanupFailures() > 0) {
            log.info("Retention cleanup {}: entityTypes={}, evaluated={}, eligible={}, deleted={}, failures={}",
                result.getStatus(),
                result.getEntityTypesScanned(),
                result.getEntriesEvaluated(),
                result.getEntriesEligible(),
                result.getVectorsDeleted(),
                result.getCleanupFailures());
        }
        return result;
    }

    private void cleanupEntityType(String entityType, RetentionPolicyProvider policy, CleanupAccumulator accumulator) {
        if (entityType == null || entityType.isBlank()) {
            return;
        }
        accumulator.entityTypesScanned++;

        int scanLimit = properties.getRetention().getScanLimit() != null && properties.getRetention().getScanLimit() > 0
            ? properties.getRetention().getScanLimit()
            : 200;

        LocalDateTime now = LocalDateTime.now(clock);
        String cursor = null;

        while (true) {
            IndexCatalogScanPage page;
            try {
                page = indexCatalog.scan(IndexCatalogScanRequest.builder()
                    .entityType(entityType)
                    .limit(scanLimit)
                    .cursor(cursor)
                    .build());
            } catch (Exception ex) {
                accumulator.failure("catalog scan failed for " + entityType + ": " + safeMessage(ex));
                log.warn("Retention cleanup failed scanning entityType={}", entityType, ex);
                return;
            }

            if (page == null || page.getEntries() == null || page.getEntries().isEmpty()) {
                return;
            }

            for (IndexCatalogEntry entry : page.getEntries()) {
                if (entry == null || entry.getEntityId() == null) {
                    continue;
                }
                accumulator.entriesEvaluated++;
                LocalDateTime createdAt = entry.getIndexedCreatedAt() != null ? entry.getIndexedCreatedAt() : entry.getIndexedUpdatedAt();
                if (createdAt == null) {
                    continue;
                }

                Integer retentionDays = resolveRetentionDays(entityType, entry, policy, accumulator);
                if (retentionDays == null || retentionDays < 0) {
                    continue;
                }

                LocalDateTime cutoff = now.minusDays(retentionDays);
                if (!createdAt.isBefore(cutoff)) {
                    continue;
                }
                accumulator.entriesEligible++;

                if (policy != null) {
                    if (!shouldDelete(policy, entry, accumulator)) {
                        continue;
                    }
                    if (!executePolicyDelete(policy, entry, accumulator)) {
                        continue;
                    }
                }

                try {
                    boolean removed = vectorDatabaseService.removeVector(entityType, entry.getEntityId());
                    if (removed) {
                        accumulator.vectorsDeleted++;
                    }
                } catch (Exception ex) {
                    accumulator.failure("vector removal failed for " + entityType + "::" + entry.getEntityId() + ": " + safeMessage(ex));
                    log.warn("Retention cleanup failed removing vector {}:{}",
                        entityType, entry.getEntityId(), ex);
                }
            }

            if (!page.isHasMore() || page.getNextCursor() == null || page.getNextCursor().isBlank()) {
                return;
            }
            if (page.getNextCursor().equals(cursor)) {
                String message = "catalog cursor did not advance for " + entityType;
                accumulator.failure(message);
                log.warn("Retention cleanup cursor did not advance for entityType={}; stopping to avoid infinite loop", entityType);
                return;
            }
            cursor = page.getNextCursor();
        }
    }

    private Integer resolveRetentionDays(String entityType,
                                         IndexCatalogEntry entry,
                                         RetentionPolicyProvider policy,
                                         CleanupAccumulator accumulator) {
        if (policy != null) {
            String classification = null;
            if (entry != null && entry.getMetadata() != null) {
                Object raw = entry.getMetadata().get("dataClassification");
                if (raw != null) {
                    classification = raw.toString();
                }
            }
            try {
                return policy.getRetentionDays(classification != null ? classification : "default", entityType);
            } catch (Exception ex) {
                accumulator.failure("retention-days policy failed for " + entityReference(entityType, entry) + ": " + safeMessage(ex));
                log.warn("RetentionPolicyProvider.getRetentionDays failed for {}:{}", entityType, entry != null ? entry.getEntityId() : null, ex);
                return null;
            }
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

    private boolean shouldDelete(RetentionPolicyProvider policy, IndexCatalogEntry entry, CleanupAccumulator accumulator) {
        try {
            return policy.shouldDelete(entry);
        } catch (Exception ex) {
            accumulator.failure("retention shouldDelete failed for " + entityReference(entry.getEntityType(), entry) + ": " + safeMessage(ex));
            log.warn("RetentionPolicyProvider.shouldDelete failed for {}:{}", entry.getEntityType(), entry.getEntityId(), ex);
            return false;
        }
    }

    private boolean executePolicyDelete(RetentionPolicyProvider policy, IndexCatalogEntry entry, CleanupAccumulator accumulator) {
        try {
            return policy.executeDelete(entry);
        } catch (Exception ex) {
            accumulator.failure("retention executeDelete failed for " + entityReference(entry.getEntityType(), entry) + ": " + safeMessage(ex));
            log.warn("RetentionPolicyProvider.executeDelete failed for {}:{}", entry.getEntityType(), entry.getEntityId(), ex);
            return false;
        }
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

    private String summaryMessage(CleanupAccumulator accumulator) {
        if (accumulator.cleanupFailures == 0) {
            return "Retention cleanup completed";
        }
        String firstFailure = accumulator.failureMessages.isEmpty() ? "see logs for details" : accumulator.failureMessages.get(0);
        return "Retention cleanup completed with " + accumulator.cleanupFailures
            + " non-fatal failure(s); first failure: " + firstFailure;
    }

    private static String entityReference(String entityType, IndexCatalogEntry entry) {
        String id = entry != null && entry.getEntityId() != null ? entry.getEntityId() : "unknown";
        return entityType + "::" + id;
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private static final class CleanupAccumulator {
        private final LocalDateTime timestamp;
        private final List<String> failureMessages = new ArrayList<>();
        private int entityTypesScanned;
        private int entriesEvaluated;
        private int entriesEligible;
        private int vectorsDeleted;
        private int cleanupFailures;

        private CleanupAccumulator(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        private void failure(String message) {
            cleanupFailures++;
            failureMessages.add(message);
        }

        private RetentionCleanupResult result(RetentionCleanupResult.Status status, String message) {
            return RetentionCleanupResult.builder()
                .status(status)
                .entityTypesScanned(entityTypesScanned)
                .entriesEvaluated(entriesEvaluated)
                .entriesEligible(entriesEligible)
                .vectorsDeleted(vectorsDeleted)
                .cleanupFailures(cleanupFailures)
                .failureMessages(List.copyOf(failureMessages))
                .timestamp(timestamp)
                .message(message)
                .build();
        }
    }
}
