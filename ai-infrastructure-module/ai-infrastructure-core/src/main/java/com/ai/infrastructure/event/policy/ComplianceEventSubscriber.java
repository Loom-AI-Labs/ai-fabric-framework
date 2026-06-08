package com.ai.infrastructure.event.policy;

/**
 * Infrastructure hook that allows customer systems to receive real-time compliance events.
 */
public interface ComplianceEventSubscriber {

    void onInjectionAttempted(InjectionEvent event);

    void onPromptInjectionAttempted(PromptInjectionEvent event);

    void onDataExfiltrationAttempted(DataExfiltrationEvent event);

    void onUnauthorizedAccessAttempt(UnauthorizedAccessEvent event);

    void onPIIDetected(PIIDetectedEvent event);

    void onAccessDenied(AccessDeniedEvent event);

    void onRequestProcessed(RequestProcessedEvent event);

    void onRequestFailed(RequestFailedEvent event);
}
