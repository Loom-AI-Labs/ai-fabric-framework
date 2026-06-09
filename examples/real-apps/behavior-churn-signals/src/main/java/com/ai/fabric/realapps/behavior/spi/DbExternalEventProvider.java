package com.ai.fabric.realapps.behavior.spi;

import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.model.ExternalEvent;
import com.ai.infrastructure.behavior.model.UserEventBatch;
import com.ai.infrastructure.behavior.repository.BehaviorInsightsRepository;
import com.ai.infrastructure.behavior.spi.ExternalEventProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbExternalEventProvider implements ExternalEventProvider {

    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightsRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExternalEvent> getEventsForUser(String userId, LocalDateTime since, LocalDateTime until) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        LocalDateTime effectiveUntil = until != null ? until : LocalDateTime.now();
        LocalDateTime effectiveSince = since != null ? since : effectiveUntil.minusDays(30);

        return eventRepository.findWindow(userId, effectiveSince, effectiveUntil).stream()
            .map(this::toExternalEvent)
            .toList();
    }

    @Override
    public UserEventBatch getNextUserEvents() {
        List<String> userIds = eventRepository.findDistinctUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return null;
        }

        Optional<String> next = userIds.stream()
            .filter(userId -> {
                LocalDateTime latestEvent = eventRepository.findLatestTimestamp(userId);
                if (latestEvent == null) {
                    return false;
                }
                return insightsRepository.findByUserId(userId)
                    .map(BehaviorInsights::getAnalyzedAt)
                    .map(analyzedAt -> analyzedAt == null || latestEvent.isAfter(analyzedAt))
                    .orElse(true);
            })
            .sorted(Comparator.naturalOrder())
            .findFirst();

        if (next.isEmpty()) {
            return null;
        }

        String userId = next.get();
        List<ExternalEvent> events = getEventsForUser(userId, null, null);

        Map<String, Object> context = Map.of(
            "source", "db",
            "eventCount", events.size()
        );

        return UserEventBatch.builder()
            .userId(userId)
            .events(events)
            .totalEventCount(events.size())
            .userContext(context)
            .build();
    }

    private ExternalEvent toExternalEvent(AppBehaviorEvent event) {
        return ExternalEvent.builder()
            .eventType(event.getEventType())
            .eventData(parseEventData(event.getEventData()))
            .timestamp(event.getEventTimestamp())
            .source(event.getSource())
            .build();
    }

    private Map<String, Object> parseEventData(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(raw, Map.class);
            return map != null ? map : Map.of();
        } catch (Exception ex) {
            log.debug("Failed to parse event_data JSON; returning raw", ex);
            return Map.of("raw", raw);
        }
    }
}
