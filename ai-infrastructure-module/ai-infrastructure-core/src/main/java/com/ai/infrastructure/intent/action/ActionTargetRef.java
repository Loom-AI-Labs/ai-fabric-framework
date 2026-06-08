package com.ai.infrastructure.intent.action;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Minimal, domain-agnostic reference to an entity returned from an action.
 *
 * <p>This is intended to be used inside {@link ActionListPayload} results so the orchestrator can
 * deterministically pin "working set" targets for follow-up turns ("it", "them", "this").</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionTargetRef(
    String id,
    String vectorSpace,
    String contentText,
    Map<String, String> metadata
) {
    public ActionTargetRef {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id is required");
        }
        id = id.trim();
        vectorSpace = vectorSpace != null && !vectorSpace.trim().isEmpty() ? vectorSpace.trim() : null;
        contentText = contentText != null && !contentText.trim().isEmpty() ? contentText.trim() : null;
        metadata = metadata != null && !metadata.isEmpty() ? Map.copyOf(metadata) : Map.of();
    }
}
