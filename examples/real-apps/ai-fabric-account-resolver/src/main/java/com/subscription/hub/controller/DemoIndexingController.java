package com.subscription.hub.controller;

import ai.fabric.service.AICapabilityService;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.repository.SubscriptionPlanRepository;
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
    private final ObjectProvider<AICapabilityService> capabilityServiceProvider;

    @PostMapping("/reindex/plans")
    public ResponseEntity<Map<String, Object>> reindexPlans() {
        AICapabilityService capabilityService = capabilityServiceProvider.getIfAvailable();
        if (capabilityService == null) {
            return ResponseEntity.ok(Map.of(
                "enabled", false,
                "reason", "AICapabilityService bean not available"
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
                capabilityService.processEntityForAI(plan, "subscription-plan");
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
