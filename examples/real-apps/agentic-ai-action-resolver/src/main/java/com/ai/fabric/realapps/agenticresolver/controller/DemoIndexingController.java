package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo/indexing")
@RequiredArgsConstructor
@Slf4j
public class DemoIndexingController {

    private final SubscriptionPlanRepository planRepository;
    private final ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider;

    @PostMapping("/reindex/plans")
    public ResponseEntity<Map<String, Object>> reindexPlans() {
        AIEntityIndexingGateway indexingGateway = indexingGatewayProvider.getIfAvailable();
        if (indexingGateway == null) {
            return ResponseEntity.ok(Map.of(
                "enabled", false,
                "reason", "AIEntityIndexingGateway bean not available"
            ));
        }

        List<SubscriptionPlan> plans = planRepository.findAll();
        int processed = 0;
        Instant startedAt = Instant.now();

        for (SubscriptionPlan plan : plans) {
            if (plan == null) {
                continue;
            }
            try {
                indexingGateway.upsert(plan, AIProcessOperation.UPDATE);
                processed++;
            } catch (Exception ex) {
                log.warn("Failed to process plan {} for AI indexing", plan.getId(), ex);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("entityType", "subscription-plan");
        result.put("plansFound", plans.size());
        result.put("processed", processed);
        result.put("startedAt", startedAt.toString());
        result.put("finishedAt", Instant.now().toString());
        return ResponseEntity.ok(result);
    }
}
