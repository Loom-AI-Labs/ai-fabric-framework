package com.subscription.hub.service;

import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.UserEventBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BehaviorEventServiceTest {

    private final BehaviorEventService service = new BehaviorEventService();

    @Test
    void tracksEventsAndExposesThemThroughExternalEventProvider() {
        UUID userId = UUID.randomUUID();

        service.trackEvent(userId, "SUBSCRIBE", Map.of("planName", "Pro"));

        List<ExternalEvent> events = service.getEventsForUser(userId.toString(), null, null);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo("SUBSCRIBE");
        assertThat(events.getFirst().getEventData()).containsEntry("planName", "Pro");
        assertThat(events.getFirst().getSource()).isEqualTo("subscription-hub");
    }

    @Test
    void discoveryReturnsPendingBatchOnce() {
        UUID userId = UUID.randomUUID();
        service.trackEvent(userId, "UPGRADE", Map.of("newTier", "ENTERPRISE"));

        UserEventBatch first = service.getNextUserEvents();
        UserEventBatch second = service.getNextUserEvents();

        assertThat(first.getUserId()).isEqualTo(userId.toString());
        assertThat(first.getEvents()).hasSize(1);
        assertThat(first.getUserContext()).containsEntry("pending", true);
        assertThat(second.getUserId()).isNull();
        assertThat(second.getEvents()).isEmpty();
        assertThat(second.getUserContext()).containsEntry("pending", false);
    }

    @Test
    void rejectsInvalidEvents() {
        assertThatThrownBy(() -> service.trackEvent(null, "SUBSCRIBE", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trackEvent(UUID.randomUUID(), " ", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
