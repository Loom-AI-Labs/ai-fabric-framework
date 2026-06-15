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
        int behaviorsDeleted = deleteBehaviors(userId);
        IndexedDeletionStats indexedDeletionStats = deleteIndexedEntities(userId, provider);
        int domainRecordsDeleted = safelyDeleteDomainData(provider, userId);
        notifyProvider(provider, userId);
        logDeletionEvent(userId, behaviorsDeleted, indexedDeletionStats, domainRecordsDeleted, timestamp);

        return UserDataDeletionResult.builder()
            .userId(userId)
            .status(UserDataDeletionResult.Status.COMPLETED)
            .behaviorsDeleted(behaviorsDeleted)
            .indexedEntitiesDeleted(indexedDeletionStats.entitiesDeleted())
            .vectorsDeleted(indexedDeletionStats.vectorsDeleted())
            .domainRecordsDeleted(domainRecordsDeleted)
            .auditEntriesDeleted(0)
            .timestamp(timestamp)
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

    private int deleteBehaviors(String userId) {
        if (behaviorDeletionPort == null) {
            return 0;
        }
        try {
            UUID userUuid = UUID.fromString(userId);
            return behaviorDeletionPort.deleteUserBehaviors(userUuid);
        } catch (IllegalArgumentException ex) {
            log.debug("Skipping behavior deletion: userId {} is not a UUID", userId);
            return 0;
        } catch (Exception ex) {
            log.warn("Failed to delete behavior history for user {}: {}", userId, ex.getMessage());
            return 0;
        }
    }

    private IndexedDeletionStats deleteIndexedEntities(String userId, UserDataDeletionProvider provider) {
        Set<String> processedKeys = new HashSet<>();
        AtomicInteger entitiesDeleted = new AtomicInteger();
        AtomicInteger vectorsDeleted = new AtomicInteger();

        List<UserEntityReference> references = Optional.ofNullable(provider.findIndexedEntities(userId))
            .orElse(List.of());
        references.forEach(ref -> removeReference(ref, processedKeys, entitiesDeleted, vectorsDeleted));

        if (references.isEmpty()) {
            List<IndexCatalogEntry> metadataMatches = indexCatalog.findByMetadataContainingSnippet("\"" + userId + "\"", 2_000);
            metadataMatches.forEach(entry -> removeCatalogEntry(entry, processedKeys, entitiesDeleted, vectorsDeleted));
        }

        return new IndexedDeletionStats(entitiesDeleted.get(), vectorsDeleted.get());
    }

    private void removeReference(UserEntityReference reference,
                                 Set<String> processedKeys,
                                 AtomicInteger entitiesDeleted,
                                 AtomicInteger vectorsDeleted) {
        if (reference == null || !StringUtils.hasText(reference.entityType()) || !StringUtils.hasText(reference.entityId())) {
            return;
        }
        removeByEntity(reference.entityType(), reference.entityId(), processedKeys, entitiesDeleted, vectorsDeleted);
    }

    private void removeCatalogEntry(IndexCatalogEntry entry,
                                    Set<String> processedKeys,
                                    AtomicInteger entitiesDeleted,
                                    AtomicInteger vectorsDeleted) {
        if (entry == null || !StringUtils.hasText(entry.getEntityType()) || !StringUtils.hasText(entry.getEntityId())) {
            return;
        }
        removeByEntity(entry.getEntityType(), entry.getEntityId(), processedKeys, entitiesDeleted, vectorsDeleted);
    }

    private void removeByEntity(String entityType,
                                String entityId,
                                Set<String> processedKeys,
                                AtomicInteger entitiesDeleted,
                                AtomicInteger vectorsDeleted) {
        String cacheKey = entityType + "::" + entityId;
        if (!processedKeys.add(cacheKey)) {
            return;
        }

        try {
            if (vectorDatabaseService.removeVector(entityType, entityId)) {
                vectorsDeleted.incrementAndGet();
            }
        } catch (Exception ex) {
            log.debug("Vector removal failed for {}:{} - {}", entityType, entityId, ex.getMessage());
        }

        if (!(indexCatalog instanceof VectorIndexCatalog)) {
            try {
                indexCatalog.delete(entityType, entityId);
                entitiesDeleted.incrementAndGet();
            } catch (Exception ex) {
                log.debug("Catalog deletion failed for {}:{} - {}", entityType, entityId, ex.getMessage());
            }
        } else {
            entitiesDeleted.incrementAndGet();
        }
    }

    private int safelyDeleteDomainData(UserDataDeletionProvider provider, String userId) {
        try {
            return provider.deleteUserDomainData(userId);
        } catch (Exception ex) {
            log.warn("UserDataDeletionProvider.deleteUserDomainData failed for user {}: {}", userId, ex.getMessage());
            return 0;
        }
    }

    private void notifyProvider(UserDataDeletionProvider provider, String userId) {
        try {
            provider.notifyAfterDeletion(userId);
        } catch (Exception ex) {
            log.debug("UserDataDeletionProvider.notifyAfterDeletion threw for user {}: {}", userId, ex.getMessage());
        }
    }

    private void logDeletionEvent(String userId,
                                  int behaviorsDeleted,
                                  IndexedDeletionStats indexedDeletionStats,
                                  int domainRecordsDeleted,
                                  LocalDateTime timestamp) {
        log.info("User {} deletion completed: behaviors={}, indexedEntities={}, vectors={}, domainRecords={}",
            userId,
            behaviorsDeleted,
            indexedDeletionStats.entitiesDeleted(),
            indexedDeletionStats.vectorsDeleted(),
            domainRecordsDeleted);
    }

    private record IndexedDeletionStats(int entitiesDeleted, int vectorsDeleted) { }
}
