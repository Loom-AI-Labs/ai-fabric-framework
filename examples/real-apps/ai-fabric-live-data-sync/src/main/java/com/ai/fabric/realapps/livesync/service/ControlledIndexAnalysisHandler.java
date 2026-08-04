package com.ai.fabric.realapps.livesync.service;

import ai.fabric.indexing.api.AIIndexAnalysisHandler;
import ai.fabric.indexing.model.AIIndexDocument;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Isolated failure gate for the public indexing lifecycle canaries.
 * It never replaces generation or embedding intelligence.
 */
@Component
public class ControlledIndexAnalysisHandler
    implements AIIndexAnalysisHandler {

    private final ConcurrentHashMap<String, AtomicInteger> failures =
        new ConcurrentHashMap<>();

    public void failNext(String entityId, int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        failures.put(entityId, new AtomicInteger(attempts));
    }

    public void clearWorkspace(String workspaceId) {
        String prefix = workspaceId + ":";
        failures.keySet().removeIf(entityId -> entityId.startsWith(prefix));
    }

    @Override
    public String analyze(AIIndexDocument document) {
        AtomicInteger remaining = failures.get(document.entityId());
        if (remaining != null && remaining.getAndUpdate(value ->
            Math.max(0, value - 1)
        ) > 0) {
            throw new IllegalStateException(
                "Controlled lifecycle dependency is unavailable"
            );
        }
        failures.remove(document.entityId(), remaining);
        return "Controlled lifecycle dependency accepted source revision "
            + document.sourceVersion();
    }
}
