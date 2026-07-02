package com.subscription.hub.service;

import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.UserEventBatch;
import ai.fabric.behavior.spi.ExternalEventProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory behavior event provider for the subscription demo.
 */
@Service
@Slf4j
public class BehaviorEventService implements ExternalEventProvider {

    private final Map<String, List<TrackedBehaviorEvent>> eventsByUser = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastServedAtByUser = new ConcurrentHashMap<>();

    /**
     * Track a subscription-related event for behavior analysis
     */
    public void trackEvent(UUID userId, String eventType, Map<String, String> eventData) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(eventType)) {
            throw new IllegalArgumentException("eventType is required");
        }

        TrackedBehaviorEvent event = new TrackedBehaviorEvent(
            userId.toString(),
            eventType.trim(),
            LocalDateTime.now(),
            copyEventData(eventData)
        );
        eventsByUser.computeIfAbsent(event.userId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
        log.info("Tracking event: userId={}, eventType={}, eventData={}", userId, eventType, eventData);
    }

    @Override
    public List<ExternalEvent> getEventsForUser(String userId, LocalDateTime since, LocalDateTime until) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LocalDateTime effectiveUntil = until != null ? until : LocalDateTime.now();
        LocalDateTime effectiveSince = since != null ? since : LocalDateTime.MIN;
        return eventsByUser.getOrDefault(userId.trim(), List.of()).stream()
            .filter(event -> !event.timestamp().isBefore(effectiveSince) && !event.timestamp().isAfter(effectiveUntil))
            .sorted(Comparator.comparing(TrackedBehaviorEvent::timestamp))
            .map(this::toExternalEvent)
            .toList();
    }

    @Override
    public UserEventBatch getNextUserEvents() {
        Optional<UserEventBatch> next = eventsByUser.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> pendingBatch(entry.getKey(), entry.getValue()))
            .flatMap(Optional::stream)
            .findFirst();

        return next.orElseGet(() -> UserEventBatch.builder()
            .events(List.of())
            .totalEventCount(0)
            .userContext(Map.of(
                "source", "subscription-hub-memory",
                "pending", false
            ))
            .build());
    }

    private Optional<UserEventBatch> pendingBatch(String userId, List<TrackedBehaviorEvent> events) {
        LocalDateTime lastServedAt = lastServedAtByUser.getOrDefault(userId, LocalDateTime.MIN);
        List<TrackedBehaviorEvent> pending = events.stream()
            .filter(event -> event.timestamp().isAfter(lastServedAt))
            .sorted(Comparator.comparing(TrackedBehaviorEvent::timestamp))
            .toList();
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        LocalDateTime newestTimestamp = pending.getLast().timestamp();
        lastServedAtByUser.put(userId, newestTimestamp);
        List<ExternalEvent> externalEvents = pending.stream()
            .map(this::toExternalEvent)
            .toList();

        return Optional.of(UserEventBatch.builder()
            .userId(userId)
            .events(externalEvents)
            .totalEventCount(externalEvents.size())
            .userContext(Map.of(
                "source", "subscription-hub-memory",
                "pending", true
            ))
            .build());
    }

    private ExternalEvent toExternalEvent(TrackedBehaviorEvent event) {
        return ExternalEvent.builder()
            .eventType(event.eventType())
            .eventData(event.eventData())
            .timestamp(event.timestamp())
            .source("subscription-hub")
            .build();
    }

    private Map<String, Object> copyEventData(Map<String, String> eventData) {
        if (eventData == null || eventData.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        eventData.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    private record TrackedBehaviorEvent(
        String userId,
        String eventType,
        LocalDateTime timestamp,
        Map<String, Object> eventData
    ) { }
}
