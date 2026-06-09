package com.subscription.hub.controller;

import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import com.subscription.hub.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions/plans")
@RequiredArgsConstructor
@Tag(name = "Subscription Plans", description = "APIs for browsing and searching subscription plans")
public class PlanController {

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionService subscriptionService;

    @Operation(summary = "Get all active plans", description = "Retrieves all active subscription plans")
    @ApiResponse(responseCode = "200", description = "List of active plans")
    @GetMapping
    public ResponseEntity<List<SubscriptionPlan>> getAllPlans() {
        List<SubscriptionPlan> plans = planRepository.findByIsActiveTrue();
        return ResponseEntity.ok(plans);
    }

    @Operation(summary = "Get plan by ID", description = "Retrieves a specific subscription plan by UUID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plan found"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionPlan> getPlan(
            @Parameter(description = "Plan UUID", required = true) @PathVariable UUID id) {
        return planRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Search plans", description = "Searches subscription plans by name or description")
    @ApiResponse(responseCode = "200", description = "List of matching plans")
    @PostMapping("/search")
    public ResponseEntity<List<SubscriptionPlan>> searchPlans(
            @Parameter(description = "Search query text", required = true) @RequestParam String query,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "10") int limit) {
        List<SubscriptionPlan> plans = subscriptionService.searchPlans(query, limit);
        return ResponseEntity.ok(plans);
    }
}
