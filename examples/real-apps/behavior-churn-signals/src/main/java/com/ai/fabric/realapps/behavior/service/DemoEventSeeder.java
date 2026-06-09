package com.ai.fabric.realapps.behavior.service;

import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DemoEventSeeder {

    private final AppBehaviorEventRepository eventRepository;

    @Transactional
    public long seed() {
        eventRepository.deleteAll();

        seedUser("user-1001", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("PAYMENT_FAILED", Map.of("reason", "card_declined")),
            event("SUPPORT_COMPLAINT", Map.of("topic", "billing", "message", "charged twice")),
            event("CANCEL_INTENT", Map.of("reason", "too expensive"))
        ));

        seedUser("user-1002", List.of(
            event("LOGIN", Map.of("channel", "mobile")),
            event("FEATURE_USED", Map.of("feature", "reports")),
            event("UPGRADE", Map.of("from", "starter", "to", "pro")),
            event("LOGIN", Map.of("channel", "web"))
        ));

        seedUser("user-1003", List.of(
            event("LOGIN", Map.of("channel", "web")),
            event("SUPPORT_TICKET", Map.of("topic", "mfa_reset")),
            event("PAYMENT_FAILED", Map.of("reason", "insufficient_funds"))
        ));

        return eventRepository.count();
    }

    private void seedUser(String userId, List<SeedEvent> events) {
        LocalDateTime base = LocalDateTime.now().minusDays(5);
        for (int i = 0; i < events.size(); i++) {
            SeedEvent seed = events.get(i);
            AppBehaviorEvent event = new AppBehaviorEvent();
            event.setUserId(userId);
            event.setEventType(seed.type());
            event.setEventTimestamp(base.plusHours(i * 6L));
            event.setEventData(seed.dataJson());
            event.setSource("demo");
            eventRepository.save(event);
        }
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
