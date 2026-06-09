package com.subscription.hub.controller;

import com.subscription.hub.entity.Subscription;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Subscription Management", description = "APIs for managing user subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;

    @Operation(summary = "Subscribe to a plan", description = "Creates a new subscription for a user to a specific plan")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or user already has active subscription")
    })
    @PostMapping("/subscribe")
    public ResponseEntity<Subscription> subscribe(
            @Parameter(description = "Numeric user ID (1-100)", required = true) @RequestParam Long userId,
            @Parameter(description = "Plan UUID", required = true) @RequestParam UUID planId,
            @Parameter(description = "Billing cycle (MONTHLY or ANNUAL)") @RequestParam(defaultValue = "MONTHLY") Subscription.BillingCycle billingCycle) {
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

    @Operation(summary = "Upgrade subscription", description = "Upgrades a subscription to a higher tier plan")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription upgraded successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid upgrade path"),
        @ApiResponse(responseCode = "404", description = "Subscription or plan not found")
    })
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Subscription> upgrade(
            @Parameter(description = "Subscription UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "New plan UUID", required = true) @RequestParam UUID newPlanId) {
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

    @Operation(summary = "Get subscription by ID", description = "Retrieves subscription details by subscription UUID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription found"),
        @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Subscription> getSubscription(
            @Parameter(description = "Subscription UUID", required = true) @PathVariable UUID id) {
        Subscription subscription = subscriptionService.findById(id);
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Get active subscription for user", description = "Retrieves the active subscription for a user by numeric ID (1-100)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "No active subscription found")
    })
    @GetMapping("/user/{userId}/active")
    public ResponseEntity<Subscription> getActiveSubscription(
            @Parameter(description = "Numeric user ID (1-100)", required = true) @PathVariable Long userId) {
        UUID userUuid = userService.getUserIdFromNumeric(userId);
        return subscriptionService.getActiveSubscription(userUuid)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
