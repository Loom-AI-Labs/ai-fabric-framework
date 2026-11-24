package ai.fabric.dto;

/** Explicit provider diagnostics only. Unknown metadata stays null; no raw bodies or private reasoning. */
public record ProviderOutcome(String callPurpose, String model, Integer effectiveOutputCap,
                              String providerRequestId, String finishReason, Long inputTokens,
                              Long outputTokens, Long reasoningTokens, Integer returnedContentSize,
                              String transportClassification, Long retryAfterMillis) {
    public ProviderOutcome(String callPurpose, String model, Integer effectiveOutputCap, String providerRequestId,
        String finishReason, Long inputTokens, Long outputTokens, Long reasoningTokens, Integer returnedContentSize,
        String transportClassification) {
        this(callPurpose, model, effectiveOutputCap, providerRequestId, finishReason, inputTokens, outputTokens,
            reasoningTokens, returnedContentSize, transportClassification, null);
    }
    public boolean outputLimited() {
        return "length".equals(finishReason) || "max_tokens".equals(finishReason)
            || "MAX_TOKENS".equals(finishReason);
    }
    public ProviderOutcome withoutProviderRequestId() {
        return new ProviderOutcome(callPurpose, model, effectiveOutputCap, null, finishReason, inputTokens,
            outputTokens, reasoningTokens, returnedContentSize, transportClassification, retryAfterMillis);
    }
}
