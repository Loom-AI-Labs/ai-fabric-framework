package com.ai.fabric.realapps.agenticresolver.controller;

import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions/plans")
@RequiredArgsConstructor
public class PlanController {

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionService subscriptionService;

    @GetMapping
    public ResponseEntity<List<SubscriptionPlan>> getAllPlans() {
        List<SubscriptionPlan> plans = planRepository.findByIsActiveTrue();
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionPlan> getPlan(@PathVariable UUID id) {
        return planRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/search")
    public ResponseEntity<List<SubscriptionPlan>> searchPlans(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit) {
        List<SubscriptionPlan> plans = subscriptionService.searchPlans(query, limit);
        return ResponseEntity.ok(plans);
    }
}
