package ai.fabric.behavior.it.support;

import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.UserEventBatch;
import ai.fabric.behavior.spi.ExternalEventProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TestEventProvider implements ExternalEventProvider {

    private final AtomicReference<List<ExternalEvent>> targetedEvents = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<UserEventBatch> nextBatch = new AtomicReference<>(null);

    @Override
    public List<ExternalEvent> getEventsForUser(String userId, LocalDateTime since, LocalDateTime until) {
        return targetedEvents.get();
    }

    @Override
    public UserEventBatch getNextUserEvents() {
        return nextBatch.get();
    }

    public void setTargetedEvents(List<ExternalEvent> events) {
        targetedEvents.set(events);
    }

    public void setNextBatch(String userId, List<ExternalEvent> events, Map<String, Object> userContext) {
        nextBatch.set(UserEventBatch.builder()
            .userId(userId)
            .events(events)
            .totalEventCount(events != null ? events.size() : 0)
            .userContext(userContext)
            .build());
    }

    public void clear() {
        targetedEvents.set(Collections.emptyList());
        nextBatch.set(null);
    }
}
