package com.ai.fabric.realapps.behavior.spi;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.UserEventBatch;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbExternalEventProviderTest {

    private final AppBehaviorEventRepository eventRepository = mock(AppBehaviorEventRepository.class);
    private final BehaviorInsightsRepository insightsRepository = mock(BehaviorInsightsRepository.class);
    private final DbExternalEventProvider provider = new DbExternalEventProvider(
        eventRepository,
        insightsRepository,
        new ObjectMapper()
    );

    @Test
    void returnsEmptyEventListForBlankUserId() {
        assertThat(provider.getEventsForUser(" ", null, null)).isEmpty();
    }

    @Test
    void convertsStoredEventsAndPreservesInvalidJsonAsRawPayload() {
        LocalDateTime now = LocalDateTime.now();
        AppBehaviorEvent valid = event("u1", "login", now.minusMinutes(5), "{\"plan\":\"pro\"}");
        AppBehaviorEvent invalid = event("u1", "complaint", now.minusMinutes(1), "{bad-json");
        when(eventRepository.findWindow("u1", now.minusDays(1), now)).thenReturn(List.of(valid, invalid));

        List<ExternalEvent> events = provider.getEventsForUser("u1", now.minusDays(1), now);

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().getEventData()).containsEntry("plan", "pro");
        assertThat(events.get(1).getEventData()).containsEntry("raw", "{bad-json");
    }

    @Test
    void returnsNoPendingBatchWhenNoUsersNeedAnalysis() {
        when(eventRepository.findDistinctUserIds()).thenReturn(List.of());

        UserEventBatch batch = provider.getNextUserEvents();

        assertThat(batch).isNotNull();
        assertThat(batch.getUserId()).isNull();
        assertThat(batch.getEvents()).isEmpty();
        assertThat(batch.getTotalEventCount()).isZero();
        assertThat(batch.getUserContext()).containsEntry("pending", false);
    }

    @Test
    void selectsOldestPendingUserWithEvents() {
        LocalDateTime now = LocalDateTime.now();
        when(eventRepository.findDistinctUserIds()).thenReturn(List.of("u2", "u1"));
        when(eventRepository.findLatestTimestamp("u1")).thenReturn(now.minusHours(1));
        when(eventRepository.findLatestTimestamp("u2")).thenReturn(now.minusHours(2));
        when(insightsRepository.findByUserId("u1")).thenReturn(Optional.empty());
        when(insightsRepository.findByUserId("u2")).thenReturn(Optional.of(BehaviorInsights.builder()
            .analyzedAt(now)
            .build()));
        when(eventRepository.findWindow(eq("u1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(event("u1", "login", now, "{}")));

        UserEventBatch batch = provider.getNextUserEvents();

        assertThat(batch.getUserId()).isEqualTo("u1");
        assertThat(batch.getEvents()).hasSize(1);
        assertThat(batch.getTotalEventCount()).isEqualTo(1);
        assertThat(batch.getUserContext()).containsAllEntriesOf(Map.of(
            "source", "db",
            "eventCount", 1
        ));
    }

    private static AppBehaviorEvent event(String userId, String eventType, LocalDateTime timestamp, String eventData) {
        AppBehaviorEvent event = new AppBehaviorEvent();
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventTimestamp(timestamp);
        event.setEventData(eventData);
        event.setSource("test");
        return event;
    }
}
