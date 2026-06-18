package com.subscription.hub.controller;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions/plans/ai")
@RequiredArgsConstructor
@Slf4j
public class PlanAiSearchController {

    private final SubscriptionPlanRepository planRepository;
    private final Environment environment;
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
        @RequestParam String q,
        @RequestParam(defaultValue = "5") int limit
    ) {
        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            return ResponseEntity.ok(Map.of(
                "enabled", false,
                "reason", "AICoreService bean not available",
                "query", q
            ));
        }

        String normalizedQuery = q == null ? "" : q.trim();
        int effectiveLimit = Math.max(1, Math.min(limit, 50));

        AISearchRequest request = AISearchRequest.builder()
            .entityType("subscription-plan")
            .query(normalizedQuery)
            .limit(effectiveLimit)
            .threshold(0.0)
            .build();

        AISearchResponse response = aiCoreService.performSearch(request);

        List<Map<String, Object>> matches = new ArrayList<>();
        if (response != null && response.getResults() != null) {
            for (Map<String, Object> row : response.getResults()) {
                Optional<String> entityId = extractEntityId(row);
                Optional<SubscriptionPlan> plan = entityId.flatMap(this::findPlan);

                Map<String, Object> match = new LinkedHashMap<>();
                match.put("entityId", entityId.orElse(null));
                match.put("score", row != null ? row.get("score") : null);
                match.put("vectorId", row != null ? row.get("vectorId") : null);
                match.put("plan", plan.orElse(null));
                matches.add(match);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("query", normalizedQuery);
        result.put("limit", effectiveLimit);
        result.put("vectorDbType", environment.getProperty("ai.vector-db.type"));
        result.put("matches", matches);
        return ResponseEntity.ok(result);
    }

    private Optional<SubscriptionPlan> findPlan(String entityId) {
        try {
            return planRepository.findById(UUID.fromString(entityId));
        } catch (IllegalArgumentException ex) {
            log.debug("Unable to parse plan entityId '{}' as UUID", entityId);
            return Optional.empty();
        }
    }

    private Optional<String> extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        Object id = row.get("entityId");
        if (id == null) {
            id = row.get("id");
        }
        return Optional.ofNullable(id)
            .map(Objects::toString)
            .filter(value -> !value.isBlank());
    }
}
