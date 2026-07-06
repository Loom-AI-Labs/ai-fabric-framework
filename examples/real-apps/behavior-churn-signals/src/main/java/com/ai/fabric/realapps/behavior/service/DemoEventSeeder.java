package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DemoEventSeeder {

    private static final Map<String, String> CANONICAL_USERS = Map.of(
        "billing-cancellation-risk", "user-1001",
        "expansion-ready-account", "user-1002",
        "onboarding-friction", "user-1003",
        "release-regression", "user-1004",
        "silent-churn", "user-1005"
    );

    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightsRepository;

    @Transactional
    public long seed() {
        insightsRepository.deleteAll();
        eventRepository.deleteAll();

        CANONICAL_USERS.forEach((scenarioId, userId) ->
            seedScenario(userId, scenarioId, "demo", LocalDateTime.now().minusDays(5)));

        return eventRepository.count();
    }

    @Transactional
    public long seedScenario(String userId, String scenarioId, String source, LocalDateTime baseTime) {
        List<SeedEvent> events = scenarioEvents().get(scenarioId);
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Unknown behavior demo scenario: " + scenarioId);
        }
        eventRepository.deleteByUserId(userId);
        insightsRepository.deleteByUserId(userId);
        seedUser(userId, events, source, baseTime != null ? baseTime : LocalDateTime.now().minusHours(events.size()));
        return events.size();
    }

    public Map<String, String> canonicalUsers() {
        return CANONICAL_USERS;
    }

    public int scenarioEventCount(String scenarioId) {
        return scenarioEvents().getOrDefault(scenarioId, List.of()).size();
    }

    private void seedUser(String userId, List<SeedEvent> events, String source, LocalDateTime base) {
        for (int i = 0; i < events.size(); i++) {
            SeedEvent seed = events.get(i);
            AppBehaviorEvent event = new AppBehaviorEvent();
            event.setUserId(userId);
            event.setEventType(seed.type());
            event.setEventTimestamp(base.plusMinutes(i * 10L));
            event.setEventData(seed.dataJson());
            event.setSource(source != null && !source.isBlank() ? source : "demo");
            eventRepository.save(event);
        }
    }

    private Map<String, List<SeedEvent>> scenarioEvents() {
        Map<String, List<SeedEvent>> scenarios = new LinkedHashMap<>();
        scenarios.put("billing-cancellation-risk", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("PAYMENT_FAILED", Map.of("reason", "card_declined")),
            event("SUPPORT_COMPLAINT", Map.of("topic", "billing", "message", "charged twice")),
            event("USAGE_DROP", Map.of("metric", "weekly_active_users", "dropPercent", "62")),
            event("PAYMENT_FAILED", Map.of("reason", "expired_card")),
            event("CANCEL_INTENT", Map.of("reason", "renewal failed twice"))
        ));
        scenarios.put("expansion-ready-account", List.of(
            event("LOGIN", Map.of("channel", "mobile")),
            event("FEATURE_USED", Map.of("feature", "reports")),
            event("SEAT_ADDED", Map.of("count", "12")),
            event("UPGRADE", Map.of("from", "starter", "to", "pro")),
            event("LOGIN", Map.of("channel", "web")),
            event("POSITIVE_FEEDBACK", Map.of("message", "team loves automated reporting")),
            event("UPGRADE", Map.of("from", "pro", "to", "enterprise")),
            event("LOGIN", Map.of("channel", "mobile"))
        ));
        scenarios.put("onboarding-friction", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("HELP_CENTER_SEARCH", Map.of("query", "invite team members")),
            event("SUPPORT_TICKET", Map.of("topic", "mfa_reset")),
            event("HELP_CENTER_SEARCH", Map.of("query", "billing permissions")),
            event("SUPPORT_COMPLAINT", Map.of("topic", "onboarding", "message", "team cannot find admin workflow")),
            event("LOW_FEATURE_ADOPTION", Map.of("feature", "admin_setup", "completion", "35"))
        ));
        scenarios.put("release-regression", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("FEATURE_USED", Map.of("feature", "dashboard")),
            event("RELEASE_DEPLOYED", Map.of("version", "2026.07.dashboard")),
            event("FEATURE_ERROR", Map.of("feature", "dashboard", "code", "REPORT_WIDGET_TIMEOUT")),
            event("SUPPORT_COMPLAINT", Map.of("topic", "dashboard", "message", "reports stopped loading after the release")),
            event("USAGE_DROP", Map.of("metric", "report_exports", "dropPercent", "58")),
            event("FEATURE_ERROR", Map.of("feature", "dashboard", "code", "REPORT_WIDGET_TIMEOUT"))
        ));
        scenarios.put("silent-churn", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("FEATURE_USED", Map.of("feature", "weekly_report")),
            event("USAGE_DROP", Map.of("metric", "weekly_active_users", "dropPercent", "22")),
            event("NO_LOGIN_7D", Map.of("days", "7")),
            event("USAGE_DROP", Map.of("metric", "weekly_active_users", "dropPercent", "41")),
            event("NO_LOGIN_14D", Map.of("days", "14"))
        ));
        return scenarios;
    }

    private SeedEvent event(String type, Map<String, Object> data) {
        String json = "{}";
        if (data != null && !data.isEmpty()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":\"")
                    .append(String.valueOf(entry.getValue()).replace("\"", "'"))
                    .append("\"");
            }
            sb.append("}");
            json = sb.toString();
        }
        return new SeedEvent(type, json);
    }

    private record SeedEvent(String type, String dataJson) {}
}
