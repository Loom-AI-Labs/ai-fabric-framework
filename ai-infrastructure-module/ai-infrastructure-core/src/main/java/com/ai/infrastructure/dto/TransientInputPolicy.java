package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Policy for provider-native transient inputs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransientInputPolicy {

    @Builder.Default
    private boolean persistInputs = false;

    @Builder.Default
    private boolean logInputs = false;

    @Builder.Default
    private boolean indexInputs = false;

    @Builder.Default
    private boolean failClosedWhenUnsupported = true;

    @Builder.Default
    private boolean requireDocumentUsage = true;

    public static TransientInputPolicy providerFileUrlDefaults() {
        return TransientInputPolicy.builder().build();
    }
}
