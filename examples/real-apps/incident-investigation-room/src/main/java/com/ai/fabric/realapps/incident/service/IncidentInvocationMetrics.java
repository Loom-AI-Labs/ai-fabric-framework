package com.ai.fabric.realapps.incident.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class IncidentInvocationMetrics {

    private final AtomicLong modelCalls = new AtomicLong();
    private final Map<String, AtomicLong> actionCalls =
        new ConcurrentHashMap<>();

    public void recordModelCall() {
        modelCalls.incrementAndGet();
    }

    public void recordActionCall(String actionName) {
        actionCalls.computeIfAbsent(actionName, ignored -> new AtomicLong())
            .incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            modelCalls.get(),
            actionCalls.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().get()
                )
            )
        );
    }

    public record Snapshot(long modelCalls, Map<String, Long> actionCalls) {
        public long totalActionCalls() {
            return actionCalls.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
