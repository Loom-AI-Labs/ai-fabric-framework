package com.subscription.hub.controller;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.indexing.IndexingActionPlan;
import com.ai.infrastructure.indexing.IndexingCoordinator;
import com.ai.infrastructure.indexing.IndexingOperation;
import com.ai.infrastructure.indexing.IndexingStatus;
import com.ai.infrastructure.indexing.queue.IndexingQueueService;
import com.ai.infrastructure.indexing.worker.AsyncIndexingWorker;
import com.ai.infrastructure.indexing.worker.BatchIndexingWorker;
import com.ai.infrastructure.repository.IndexingQueueRepository;
import com.ai.infrastructure.service.AICapabilityService;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai/debug/indexing")
@RequiredArgsConstructor
public class AIDebugIndexingController {

    private final SubscriptionPlanRepository planRepository;

    private final ApplicationContext applicationContext;
    private final Environment environment;

    private final ObjectProvider<AICoreService> aiCoreServiceProvider;
    private final ObjectProvider<AICapabilityService> capabilityServiceProvider;
    private final ObjectProvider<AIEntityConfigurationLoader> configurationLoaderProvider;
    private final ObjectProvider<IndexingCoordinator> indexingCoordinatorProvider;
    private final ObjectProvider<IndexingQueueRepository> indexingQueueRepositoryProvider;
    private final ObjectProvider<AsyncIndexingWorker> asyncIndexingWorkerProvider;
    private final ObjectProvider<BatchIndexingWorker> batchIndexingWorkerProvider;

    @PostMapping("/reindex/plans")
    public ResponseEntity<Map<String, Object>> reindexPlans(
        @RequestParam(defaultValue = "async") String mode,
        @RequestParam(defaultValue = "30") int waitSeconds
    ) {
        String normalizedMode = mode == null ? "async" : mode.trim().toLowerCase(Locale.ROOT);
        List<SubscriptionPlan> plans = planRepository.findAll();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", "subscription-plan");
        payload.put("plansFound", plans.size());
        payload.put("mode", normalizedMode);

        if (plans.isEmpty()) {
            payload.put("queue", queueCounts());
            return ResponseEntity.ok(payload);
        }

        if ("sync".equals(normalizedMode)) {
            int processed = syncIndexPlans(plans);
            payload.put("processed", processed);
            payload.put("queue", queueCounts());
            return ResponseEntity.ok(payload);
        }

        int enqueued = asyncEnqueuePlans(plans);
        payload.put("enqueued", enqueued);

        Map<String, Object> queue = queueCounts();
        payload.put("queueBeforeWait", queue);

        if (waitSeconds > 0) {
            payload.put("waited", waitForQueueDrain(Duration.ofSeconds(waitSeconds)));
        }

        payload.put("queueAfterWait", queueCounts());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/demo")
    public ResponseEntity<Map<String, Object>> demoIndexingAndSearch(
        @RequestParam(defaultValue = "async") String mode,
        @RequestParam(defaultValue = "30") int waitSeconds
    ) {
        List<SubscriptionPlan> plans = planRepository.findAll();
        if (plans.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "message", "No plans found; create plans first",
                "queue", queueCounts()
            ));
        }

        SubscriptionPlan plan = plans.getFirst();
        String token = "demo-" + UUID.randomUUID();
        plan.setDescription((plan.getDescription() == null ? "" : plan.getDescription()) + " " + token);
        planRepository.save(plan);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedPlanId", plan.getId());
        result.put("token", token);
        result.put("queueBefore", queueCounts());

        String normalizedMode = mode == null ? "async" : mode.trim().toLowerCase(Locale.ROOT);
        if ("sync".equals(normalizedMode)) {
            syncIndexPlans(List.of(plan));
        } else {
            asyncEnqueuePlans(List.of(plan));
            if (waitSeconds > 0) {
                waitForQueueDrain(Duration.ofSeconds(waitSeconds));
            }
        }

        result.put("queueAfter", queueCounts());
        result.put("search", doSearchPlans(token, 5));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/queue")
    public ResponseEntity<Map<String, Object>> queueStatus() {
        return ResponseEntity.ok(queueCounts());
    }

    @PostMapping("/queue/run-once")
    public ResponseEntity<Map<String, Object>> runQueueOnce(
        @RequestParam(defaultValue = "async") String strategy
    ) {
        String normalized = strategy == null ? "async" : strategy.trim().toLowerCase(Locale.ROOT);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", normalized);
        result.put("queueBefore", queueCounts());

        if ("batch".equals(normalized)) {
            BatchIndexingWorker worker = batchIndexingWorkerProvider.getIfAvailable();
            if (worker == null) {
                result.put("ran", false);
                result.put("reason", "BatchIndexingWorker bean not available");
                result.put("queueAfter", queueCounts());
                return ResponseEntity.ok(result);
            }
            worker.run();
        } else {
            AsyncIndexingWorker worker = asyncIndexingWorkerProvider.getIfAvailable();
            if (worker == null) {
                result.put("ran", false);
                result.put("reason", "AsyncIndexingWorker bean not available");
                result.put("queueAfter", queueCounts());
                return ResponseEntity.ok(result);
            }
            worker.run();
        }

        result.put("ran", true);
        result.put("queueAfter", queueCounts());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/components")
    public ResponseEntity<Map<String, Object>> components() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ai.enabled", environment.getProperty("ai.enabled", "true"));
        result.put("ai.indexing.enabled", environment.getProperty("ai.indexing.enabled", "true"));
        result.put("ai.vector-db.type", environment.getProperty("ai.vector-db.type"));

        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("AICoreService", beanAvailable(AICoreService.class));
        beans.put("AICapabilityService", beanAvailable(AICapabilityService.class));
        beans.put("AIEntityConfigurationLoader", beanAvailable(AIEntityConfigurationLoader.class));
        beans.put("IndexingCoordinator", beanAvailable(IndexingCoordinator.class));
        beans.put("IndexingQueueRepository", beanAvailable(IndexingQueueRepository.class));
        beans.put("IndexingQueueService", beanAvailable(IndexingQueueService.class));
        beans.put("AsyncIndexingWorker", beanAvailable(AsyncIndexingWorker.class));
        beans.put("BatchIndexingWorker", beanAvailable(BatchIndexingWorker.class));
        result.put("beans", beans);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/search/plans")
    public ResponseEntity<Map<String, Object>> searchPlans(
        @RequestParam String q,
        @RequestParam(defaultValue = "5") int limit
    ) {
        return ResponseEntity.ok(doSearchPlans(q, limit));
    }

    private Map<String, Object> doSearchPlans(String query, int limit) {
        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            return Map.of(
                "enabled", false,
                "reason", "AICoreService bean not available"
            );
        }

        AISearchRequest request = AISearchRequest.builder()
            .entityType("subscription-plan")
            .query(query)
            .limit(Math.max(1, limit))
            .threshold(0.0)
            .build();

        try {
            AISearchResponse response = aiCoreService.performSearch(request);
            return Map.of(
                "enabled", true,
                "query", query,
                "limit", limit,
                "results", response != null ? response.getResults() : List.of()
            );
        } catch (Exception ex) {
            return Map.of(
                "enabled", true,
                "query", query,
                "limit", limit,
                "error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()
            );
        }
    }

    private int syncIndexPlans(List<SubscriptionPlan> plans) {
        AICapabilityService capabilityService = capabilityServiceProvider.getIfAvailable();
        AIEntityConfigurationLoader loader = configurationLoaderProvider.getIfAvailable();
        if (capabilityService == null || loader == null) {
            return 0;
        }

        AIEntityConfig config = loader.getEntityConfig("subscription-plan");
        if (config == null) {
            return 0;
        }

        int processed = 0;
        for (SubscriptionPlan plan : plans) {
            if (plan == null) {
                continue;
            }
            capabilityService.generateEmbeddings(plan, config);
            capabilityService.indexForSearch(plan, config);
            processed++;
        }
        return processed;
    }

    private int asyncEnqueuePlans(List<SubscriptionPlan> plans) {
        IndexingCoordinator coordinator = indexingCoordinatorProvider.getIfAvailable();
        if (coordinator == null) {
            return 0;
        }

        int enqueued = 0;
        for (SubscriptionPlan plan : plans) {
            if (plan == null) {
                continue;
            }
            coordinator.handle(
                plan,
                "subscription-plan",
                IndexingOperation.UPDATE,
                new IndexingActionPlan(true, true, false, false, false),
                null
            );
            enqueued++;
        }
        return enqueued;
    }

    private boolean beanAvailable(Class<?> type) {
        return applicationContext.getBeanProvider(type).getIfAvailable() != null;
    }

    private Map<String, Object> queueCounts() {
        IndexingQueueRepository repository = indexingQueueRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return Map.of(
                "enabled", false,
                "reason", "IndexingQueueRepository bean not available"
            );
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("enabled", true);
        counts.put("pending", repository.countByStatus(IndexingStatus.PENDING));
        counts.put("processing", repository.countByStatus(IndexingStatus.PROCESSING));
        counts.put("completed", repository.countByStatus(IndexingStatus.COMPLETED));
        counts.put("deadLetter", repository.countByStatus(IndexingStatus.DEAD_LETTER));
        return counts;
    }

    private Map<String, Object> waitForQueueDrain(Duration maxWait) {
        IndexingQueueRepository repository = indexingQueueRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return Map.of(
                "enabled", false,
                "reason", "IndexingQueueRepository bean not available"
            );
        }

        Instant start = Instant.now();
        long lastPending = -1;
        long lastProcessing = -1;

        while (Duration.between(start, Instant.now()).compareTo(maxWait) < 0) {
            long pending = repository.countByStatus(IndexingStatus.PENDING);
            long processing = repository.countByStatus(IndexingStatus.PROCESSING);

            if (pending == 0 && processing == 0) {
                return Map.of(
                    "drained", true,
                    "elapsedMs", Duration.between(start, Instant.now()).toMillis()
                );
            }

            lastPending = pending;
            lastProcessing = processing;

            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return Map.of(
            "drained", false,
            "elapsedMs", Duration.between(start, Instant.now()).toMillis(),
            "pending", lastPending,
            "processing", lastProcessing
        );
    }
}
