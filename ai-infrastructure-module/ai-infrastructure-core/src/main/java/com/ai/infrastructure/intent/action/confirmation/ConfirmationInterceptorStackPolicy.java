package com.ai.infrastructure.intent.action.confirmation;

import java.util.List;

public record ConfirmationInterceptorStackPolicy(
    boolean popCurrent,
    List<String> popPreviousIfActionIn
) {
    public static final ConfirmationInterceptorStackPolicy NONE =
        new ConfirmationInterceptorStackPolicy(false, List.of());

    public ConfirmationInterceptorStackPolicy {
        popPreviousIfActionIn = popPreviousIfActionIn != null ? List.copyOf(popPreviousIfActionIn) : List.of();
    }
}
