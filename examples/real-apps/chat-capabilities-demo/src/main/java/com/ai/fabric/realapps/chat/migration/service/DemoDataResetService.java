package com.ai.fabric.realapps.chat.migration.service;

import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import ai.fabric.dto.VectorRecord;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.repository.IndexingQueueRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataResetService {

    private static final List<String> ENTITY_TYPES_TO_CLEAR = List.of("product", "review", "coupon", "policy");

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final CouponRepository couponRepository;
    private final PolicyRepository policyRepository;
    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;
    private final ObjectProvider<IndexingQueueRepository> indexingQueueRepositoryProvider;

    @Transactional
    public ClearResult clearDemoData(boolean clearVectors, boolean clearIndexingQueue) {
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("products", deleteAll(productRepository));
        deleted.put("reviews", deleteAll(reviewRepository));
        deleted.put("coupons", deleteAll(couponRepository));
        deleted.put("policies", deleteAll(policyRepository));

        Map<String, Object> indexingQueue = new LinkedHashMap<>();
        if (clearIndexingQueue) {
            IndexingQueueRepository indexingQueueRepository = indexingQueueRepositoryProvider.getIfAvailable();
            if (indexingQueueRepository != null) {
                long queueEntries = indexingQueueRepository.count();
                indexingQueueRepository.deleteAllInBatch();
                indexingQueue.put("deletedEntries", queueEntries);
            } else {
                indexingQueue.put("deletedEntries", 0L);
                indexingQueue.put("warning", "IndexingQueueRepository not available");
            }
        } else {
            indexingQueue.put("deletedEntries", 0L);
            indexingQueue.put("skipped", true);
        }

        Map<String, Object> vectors = new LinkedHashMap<>();
        if (clearVectors) {
            VectorDatabaseService vectorDatabaseService = vectorDatabaseServiceProvider.getIfAvailable();
            if (vectorDatabaseService == null) {
                vectors.put("deleted", Map.of());
                vectors.put("warning", "VectorDatabaseService not available");
            } else {
                Map<String, Object> deletedVectorsByType = new LinkedHashMap<>();
                for (String entityType : ENTITY_TYPES_TO_CLEAR) {
                    deletedVectorsByType.put(entityType, deleteVectors(vectorDatabaseService, entityType));
                }
                vectors.put("deleted", deletedVectorsByType);
            }
        } else {
            vectors.put("deleted", Map.of());
            vectors.put("skipped", true);
        }

        return ClearResult.builder()
            .success(true)
            .deleted(deleted)
            .vectors(vectors)
            .indexingQueue(indexingQueue)
            .build();
    }

    private long deleteAll(org.springframework.data.jpa.repository.JpaRepository<?, ?> repository) {
        long count = repository.count();
        if (count > 0) {
            repository.deleteAllInBatch();
        }
        return count;
    }

    private long deleteVectors(VectorDatabaseService vectorDatabaseService, String entityType) {
        try {
            List<VectorRecord> vectors = vectorDatabaseService.getVectorsByEntityType(entityType);
            if (vectors == null || vectors.isEmpty()) {
                return 0L;
            }
            long removed = 0;
            for (VectorRecord record : vectors) {
                if (record == null) {
                    continue;
                }
                String entityId = record.getEntityId();
                if (entityId == null || entityId.isBlank()) {
                    continue;
                }
                try {
                    if (vectorDatabaseService.removeVector(entityType, entityId)) {
                        removed++;
                    }
                } catch (Exception ex) {
                    log.debug("Failed to remove vector for {}:{}: {}", entityType, entityId, ex.getMessage());
                }
            }
            return removed;
        } catch (Exception ex) {
            log.warn("Failed to delete vectors for entityType {}: {}", entityType, ex.getMessage());
            return 0L;
        }
    }

    @Data
    @Builder
    public static class ClearResult {
        private boolean success;
        private Map<String, Object> deleted;
        private Map<String, Object> vectors;
        private Map<String, Object> indexingQueue;
    }
}
