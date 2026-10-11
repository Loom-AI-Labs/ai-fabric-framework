package ai.fabric.filter;

import ai.fabric.dto.AIContentFilterRequest;

/**
 * Typed content-moderation boundary supplied by an application or provider adapter.
 */
public interface ContentModerationProvider {

    /**
     * Stable provider identifier used by configuration and diagnostics.
     */
    String providerId();

    /**
     * Evaluate content and return a complete typed decision.
     */
    ContentModerationResult moderate(AIContentFilterRequest request);
}
