package com.ai.fabric.realapps.livesync.service;

import com.ai.fabric.realapps.livesync.web.DemoModels.SyncEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Service;

@Service
public class SyncAuditService {

    private static final int MAX_EVENTS = 30;
    private final ConcurrentHashMap<String, Deque<SyncEvent>> events = new ConcurrentHashMap<>();

    public SyncEvent record(
        String workspaceId,
        String operation,
        EntityKind kind,
        String recordKey,
        String title,
        Integer revision,
        boolean sourcePresent,
        boolean vectorPresent,
        boolean inSync,
        long elapsedMs,
        String message
    ) {
        SyncEvent event = new SyncEvent(
            UUID.randomUUID().toString(),
            operation,
            kind.name(),
            kind.entityType(),
            recordKey,
            title,
            revision,
            sourcePresent,
            vectorPresent,
            inSync,
            elapsedMs,
            message,
            Instant.now()
        );
        Deque<SyncEvent> workspaceEvents = events.computeIfAbsent(workspaceId, ignored -> new ConcurrentLinkedDeque<>());
        workspaceEvents.addFirst(event);
        while (workspaceEvents.size() > MAX_EVENTS) {
            workspaceEvents.pollLast();
        }
        return event;
    }

    public List<SyncEvent> events(String workspaceId) {
        return List.copyOf(new ArrayList<>(events.getOrDefault(workspaceId, new ConcurrentLinkedDeque<>())));
    }

    public void clear(String workspaceId) {
        events.remove(workspaceId);
    }
}
