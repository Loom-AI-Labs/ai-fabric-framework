package ai.fabric.deletion;

import ai.fabric.deletion.policy.UserDataDeletionProvider;
import ai.fabric.deletion.policy.UserDataDeletionProvider.UserEntityReference;
import ai.fabric.deletion.port.BehaviorDeletionPort;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.vector.VectorIndexCatalog;
import ai.fabric.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Infrastructure service orchestrating GDPR/CCPA style user deletions by delegating domain logic
 * to {@link UserDataDeletionProvider} hooks while handling infrastructure owned data stores.
 */
@Slf4j
@RequiredArgsConstructor
public class UserDataDeletionService {

    private final VectorDatabaseService vectorDatabaseService;
    private final IndexCatalog indexCatalog;
    private final Clock clock;
    private final UserDataDeletionProvider userDataDeletionProvider;
    private final BehaviorDeletionPort behaviorDeletionPort;

    /**
     * Execute a full deletion workflow for the supplied user identifier.
     *
     * @param userId unique identifier of the user whose data should be deleted
     * @return structured summary of the deletion
     */
    public UserDataDeletionResult deleteUser(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        UserDataDeletionProvider provider = requireProvider();

        if (!provider.canDeleteUser(userId)) {
            log.info("User {} cannot be deleted according to provider policy", userId);
            return UserDataDeletionResult.builder()
                .userId(userId)
                .status(UserDataDeletionResult.Status.SKIPPED)
                .timestamp(LocalDateTime.now(clock))
                .message("Deletion blocked by UserDataDeletionProvider.canDeleteUser")
                .build();
        }

        LocalDateTime timestamp = LocalDateTime.now(clock);
        List<String> failureMessages = new ArrayList<>();
        DeletionCount behaviors = deleteBehaviors(userId, failureMessages);
        IndexedDeletionStats indexedDeletionStats = deleteIndexedEntities(userId, provider, failureMessages);
        DeletionCount domain = safelyDeleteDomainData(provider, userId, failureMessages);
        DeletionCount notification = notifyProvider(provider, userId, failureMessages);
        int failures = behaviors.failures()
            + indexedDeletionStats.failures()
            + domain.failures()
            + notification.failures();
        UserDataDeletionResult.Status status = failures > 0
            ? UserDataDeletionResult.Status.PARTIAL
            : UserDataDeletionResult.Status.COMPLETED;
        logDeletionEvent(userId, status, behaviors.deleted(), indexedDeletionStats, domain.deleted(), failures, timestamp);

        return UserDataDeletionResult.builder()
            .userId(userId)
            .status(status)
            .behaviorsDeleted(behaviors.deleted())
            .indexedEntitiesDeleted(indexedDeletionStats.entitiesDeleted())
            .vectorsDeleted(indexedDeletionStats.vectorsDeleted())
            .domainRecordsDeleted(domain.deleted())
            .auditEntriesDeleted(0)
            .deletionFailures(failures)
            .failureMessages(List.copyOf(failureMessages))
            .timestamp(timestamp)
            .message(failures > 0 ? partialDeletionMessage(failures, failureMessages) : null)
            .build();
    }

    private UserDataDeletionProvider requireProvider() {
        if (userDataDeletionProvider == null) {
            throw new IllegalStateException("""
                No UserDataDeletionProvider bean available. Register an implementation of \
                ai.fabric.deletion.policy.UserDataDeletionProvider to enable deletion workflows.""");
        }
        return userDataDeletionProvider;
    }

    private DeletionCount deleteBehaviors(String userId, List<String> failureMessages) {
        if (behaviorDeletionPort == null) {
            return DeletionCount.none();
        }
        try {
            UUID userUuid = UUID.fromString(userId);
            return DeletionCount.deleted(behaviorDeletionPort.deleteUserBehaviors(userUuid));
        } catch (IllegalArgumentException ex) {
            log.debug("Skipping behavior deletion: userId {} is not a UUID", userId);
            return DeletionCount.none();
        } catch (Exception ex) {
            log.warn("Failed to delete behavior history for user {}: {}", userId, ex.getMessage());
            failureMessages.add("behavior deletion failed: " + safeMessage(ex));
            return DeletionCount.failed();
        }
    }

    private IndexedDeletionStats deleteIndexedEntities(String userId,
                                                       UserDataDeletionProvider provider,
                                                       List<String> failureMessages) {
        Set<String> processedKeys = new HashSet<>();
        AtomicInteger entitiesDeleted = new AtomicInteger();
        AtomicInteger vectorsDeleted = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<UserEntityReference> references = findProviderIndexedEntities(userId, provider, failures, failureMessages);
        references.forEach(ref -> removeReference(ref, processedKeys, entitiesDeleted, vectorsDeleted, failures, failureMessages));

        if (references.isEmpty()) {
            findCatalogIndexedEntities(userId, failures, failureMessages)
                .forEach(entry -> removeCatalogEntry(entry, processedKeys, entitiesDeleted, vectorsDeleted, failures, failureMessages));
        }

        return new IndexedDeletionStats(entitiesDeleted.get(), vectorsDeleted.get(), failures.get());
    }

    private List<UserEntityReference> findProviderIndexedEntities(String userId,
                                                                  UserDataDeletionProvider provider,
                                                                  AtomicInteger failures,
                                                                  List<String> failureMessages) {
        try {
            return Optional.ofNullable(provider.findIndexedEntities(userId))
                .orElse(List.of());
        } catch (Exception ex) {
            failures.incrementAndGet();
            log.warn("UserDataDeletionProvider.findIndexedEntities failed for user {}: {}", userId, ex.getMessage());
            failureMessages.add("indexed entity discovery failed: " + safeMessage(ex));
            return List.of();
        }
    }

    private List<IndexCatalogEntry> findCatalogIndexedEntities(String userId,
                                                               AtomicInteger failures,
                                                               List<String> failureMessages) {
        if (indexCatalog == null) {
            return List.of();
        }
        try {
            return Optional.ofNullable(indexCatalog.findByMetadataContainingSnippet("\"" + userId + "\"", 2_000))
                .orElse(List.of());
        } catch (Exception ex) {
            failures.incrementAndGet();
            log.warn("IndexCatalog metadata discovery failed for user {}: {}", userId, ex.getMessage());
            failureMessages.add("catalog metadata discovery failed: " + safeMessage(ex));
            return List.of();
        }
    }

    private void removeReference(UserEntityReference reference,
                                 Set<String> processedKeys,
                                 AtomicInteger entitiesDeleted,
                                 AtomicInteger vectorsDeleted,
                                 AtomicInteger failures,
                                 List<String> failureMessages) {
        if (reference == null || !StringUtils.hasText(reference.entityType()) || !StringUtils.hasText(reference.entityId())) {
            return;
        }
        removeByEntity(reference.entityType(), reference.entityId(), processedKeys, entitiesDeleted, vectorsDeleted, failures, failureMessages);
    }

    private void removeCatalogEntry(IndexCatalogEntry entry,
                                    Set<String> processedKeys,
                                    AtomicInteger entitiesDeleted,
                                    AtomicInteger vectorsDeleted,
                                    AtomicInteger failures,
                                    List<String> failureMessages) {
        if (entry == null || !StringUtils.hasText(entry.getEntityType()) || !StringUtils.hasText(entry.getEntityId())) {
            return;
        }
        removeByEntity(entry.getEntityType(), entry.getEntityId(), processedKeys, entitiesDeleted, vectorsDeleted, failures, failureMessages);
    }

    private void removeByEntity(String entityType,
                                String entityId,
                                Set<String> processedKeys,
                                AtomicInteger entitiesDeleted,
                                AtomicInteger vectorsDeleted,
                                AtomicInteger failures,
                                List<String> failureMessages) {
        String cacheKey = entityType + "::" + entityId;
        if (!processedKeys.add(cacheKey)) {
            return;
        }

        boolean vectorRemoved = false;
        if (vectorDatabaseService == null) {
            failures.incrementAndGet();
            String message = "VectorDatabaseService bean is not available";
            log.warn("Vector removal failed for {}:{} - {}", entityType, entityId, message);
            failureMessages.add("vector removal failed for " + cacheKey + ": " + message);
        } else {
            try {
                if (vectorDatabaseService.removeVector(entityType, entityId)) {
                    vectorsDeleted.incrementAndGet();
                    vectorRemoved = true;
                }
            } catch (Exception ex) {
                failures.incrementAndGet();
                log.warn("Vector removal failed for {}:{} - {}", entityType, entityId, ex.getMessage());
                failureMessages.add("vector removal failed for " + cacheKey + ": " + safeMessage(ex));
            }
        }

        if (indexCatalog == null || indexCatalog instanceof VectorIndexCatalog) {
            if (vectorRemoved) {
                entitiesDeleted.incrementAndGet();
            }
        } else {
            try {
                indexCatalog.delete(entityType, entityId);
                entitiesDeleted.incrementAndGet();
            } catch (Exception ex) {
                failures.incrementAndGet();
                log.warn("Catalog deletion failed for {}:{} - {}", entityType, entityId, ex.getMessage());
                failureMessages.add("catalog deletion failed for " + cacheKey + ": " + safeMessage(ex));
            }
        }
    }

    private DeletionCount safelyDeleteDomainData(UserDataDeletionProvider provider,
                                                 String userId,
                                                 List<String> failureMessages) {
        try {
            return DeletionCount.deleted(provider.deleteUserDomainData(userId));
        } catch (Exception ex) {
            log.warn("UserDataDeletionProvider.deleteUserDomainData failed for user {}: {}", userId, ex.getMessage());
            failureMessages.add("domain deletion failed: " + safeMessage(ex));
            return DeletionCount.failed();
        }
    }

    private DeletionCount notifyProvider(UserDataDeletionProvider provider,
                                         String userId,
                                         List<String> failureMessages) {
        try {
            provider.notifyAfterDeletion(userId);
            return DeletionCount.none();
        } catch (Exception ex) {
            log.warn("UserDataDeletionProvider.notifyAfterDeletion failed for user {}: {}", userId, ex.getMessage());
            failureMessages.add("post-deletion notification failed: " + safeMessage(ex));
            return DeletionCount.failed();
        }
    }

    private void logDeletionEvent(String userId,
                                  UserDataDeletionResult.Status status,
                                  int behaviorsDeleted,
                                  IndexedDeletionStats indexedDeletionStats,
                                  int domainRecordsDeleted,
                                  int failures,
                                  LocalDateTime timestamp) {
        log.info("User {} deletion {}: behaviors={}, indexedEntities={}, vectors={}, domainRecords={}, failures={}",
            userId,
            status,
            behaviorsDeleted,
            indexedDeletionStats.entitiesDeleted(),
            indexedDeletionStats.vectorsDeleted(),
            domainRecordsDeleted,
            failures);
    }

    private String partialDeletionMessage(int failures, List<String> failureMessages) {
        String firstFailure = failureMessages.isEmpty() ? "see logs for details" : failureMessages.get(0);
        return "Deletion completed with " + failures + " non-fatal failure(s); first failure: " + firstFailure;
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private record DeletionCount(int deleted, int failures) {
        static DeletionCount none() {
            return new DeletionCount(0, 0);
        }

        static DeletionCount deleted(int count) {
            return new DeletionCount(Math.max(0, count), 0);
        }

        static DeletionCount failed() {
            return new DeletionCount(0, 1);
        }
    }

    private record IndexedDeletionStats(int entitiesDeleted, int vectorsDeleted, int failures) { }
}
