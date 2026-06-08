package com.ai.infrastructure.intent.orchestration.targets;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Deterministically resolved target reference for follow-ups ("this", "it", "both") and action grounding.
 */
@Value
@Builder(toBuilder = true)
public class ResolvedTarget {

    String id;

    String vectorSpace;

    String contentText;

    @Builder.Default
    boolean contentTextTruncated = false;

    @Builder.Default
    Map<String, String> metadata = Map.of();

    ResolvedTargetSource source;
}
