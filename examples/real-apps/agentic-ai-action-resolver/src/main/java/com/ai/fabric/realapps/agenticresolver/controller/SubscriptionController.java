package com.ai.fabric.realapps.agenticresolver.controller;

import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;

    @PostMapping("/subscribe")
    public ResponseEntity<Subscription> subscribe(
            @RequestParam Long userId,  // Numeric ID 1-100
            @RequestParam UUID planId,
            @RequestParam(defaultValue = "MONTHLY") Subscription.BillingCycle billingCycle) {
        UUID userUuid = userService.getUserIdFromNumeric(userId);
        Subscription subscription = subscriptionService.subscribe(userUuid, planId, billingCycle);
        return ResponseEntity.ok(subscription);
    }

    @PostMapping("/{id}/unsubscribe")
    public ResponseEntity<Subscription> unsubscribe(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        Subscription subscription = subscriptionService.unsubscribe(id, reason);
        return ResponseEntity.ok(subscription);
    }

    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Subscription> upgrade(
            @PathVariable UUID id,
            @RequestParam UUID newPlanId) {
        Subscription subscription = subscriptionService.upgrade(id, newPlanId);
        return ResponseEntity.ok(subscription);
    }

    @PostMapping("/{id}/downgrade")
    public ResponseEntity<Subscription> downgrade(
            @PathVariable UUID id,
            @RequestParam UUID newPlanId) {
        Subscription subscription = subscriptionService.downgrade(id, newPlanId);
        return ResponseEntity.ok(subscription);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Subscription> getSubscription(@PathVariable UUID id) {
        Subscription subscription = subscriptionService.findById(id);
        return ResponseEntity.ok(subscription);
    }

    @GetMapping("/user/{userId}/active")
    public ResponseEntity<Subscription> getActiveSubscription(@PathVariable Long userId) {
        UUID userUuid = userService.getUserIdFromNumeric(userId);
        return subscriptionService.getActiveSubscription(userUuid)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
